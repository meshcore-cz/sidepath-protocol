// sidepath-macos-ble-helper
//
// A native CoreBluetooth helper process driven by the Go `macos` Sidepath node over a
// length-prefixed stdio protocol. It owns ALL BLE: scanning + connecting (central role) and
// advertising + GATT server (peripheral role). Doing it in Swift gives us CoreBluetooth's real
// flow-control callback (peripheralManagerIsReadyToUpdateSubscribers), so PACKET_OUT indications
// are delivered reliably and at full rate — which go-ble could not do.
//
// Wire framing (both directions): [uint32 BE total][uint16 BE jsonLen][JSON header][raw payload].
// The JSON "type" selects the message; the trailing payload carries frame bytes (empty otherwise).

import CoreBluetooth
import Foundation

// MARK: - Framed stdio transport

/// Reads/writes length-prefixed messages on stdin/stdout. Writes are serialized.
final class FrameIO {
    private let outHandle = FileHandle.standardOutput
    private let inHandle = FileHandle.standardInput
    private let writeLock = NSLock()
    private var buffer = Data()

    /// Sends one message: a JSON header plus optional raw binary payload.
    func send(_ header: [String: Any], payload: Data = Data()) {
        guard let json = try? JSONSerialization.data(withJSONObject: header, options: []) else { return }
        var msg = Data()
        var total = UInt32(2 + json.count + payload.count).bigEndian
        var jlen = UInt16(json.count).bigEndian
        withUnsafeBytes(of: &total) { msg.append(contentsOf: $0) }
        withUnsafeBytes(of: &jlen) { msg.append(contentsOf: $0) }
        msg.append(json)
        msg.append(payload)
        writeLock.lock()
        outHandle.write(msg)
        writeLock.unlock()
    }

    func log(_ s: String) { send(["type": "log", "message": s]) }

    /// Blocks reading stdin, invoking `onMessage(header, payload)` per decoded frame. Returns on EOF.
    func readLoop(_ onMessage: @escaping ([String: Any], Data) -> Void) {
        while true {
            let chunk = inHandle.availableData
            if chunk.isEmpty {
                // EOF — parent went away.
                exit(0)
            }
            buffer.append(chunk)
            while true {
                guard buffer.count >= 4 else { break }
                let total = buffer.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
                guard buffer.count >= 4 + Int(total) else { break }
                let body = buffer.subdata(in: 4 ..< 4 + Int(total))
                buffer.removeSubrange(0 ..< 4 + Int(total))
                guard body.count >= 2 else { continue }
                let jlen = (UInt16(body[body.startIndex]) << 8) | UInt16(body[body.startIndex + 1])
                guard body.count >= 2 + Int(jlen) else { continue }
                let jsonData = body.subdata(in: body.startIndex + 2 ..< body.startIndex + 2 + Int(jlen))
                let payload = body.subdata(in: body.startIndex + 2 + Int(jlen) ..< body.endIndex)
                if let obj = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] {
                    onMessage(obj, payload)
                }
            }
        }
    }
}

// MARK: - BLE helper

final class Helper: NSObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {
    private let io: FrameIO
    private let queue = DispatchQueue(label: "cz.meshcore.sidepath.ble")

    private var central: CBCentralManager!
    private var peripheral: CBPeripheralManager!

    private var serviceUUID: CBUUID!
    private var nodeInfoUUID: CBUUID!
    private var packetInUUID: CBUUID!
    private var packetOutUUID: CBUUID!

    private var nodeInfo = Data()
    private var packetOutChar: CBMutableCharacteristic?

    // Central state: peripherals we've discovered/connected, keyed by identifier string.
    private var discovered: [String: CBPeripheral] = [:]
    private var connected: [String: CBPeripheral] = [:]
    private var piChars: [String: CBCharacteristic] = [:] // addr -> PACKET_IN (for writes)
    private var poChars: [String: CBCharacteristic] = [:] // addr -> PACKET_OUT (for subscribe)
    private var niChars: [String: CBCharacteristic] = [:] // addr -> NODE_INFO (pending read)
    private var pendingWriteStarts: [String: [Date]] = [:] // addr -> reliable PACKET_IN writes

    // Peripheral state: centrals subscribed to our PACKET_OUT.
    private var subscribers: [String: CBCentral] = [:]
    // Single global outbound queue for indications (CoreBluetooth's tx queue is global).
    private var pendingOut: [(central: CBCentral, data: Data)] = []

    private var centralReady = false
    private var peripheralReady = false
    private var readyAnnounced = false
    private var started = false

    init(io: FrameIO) {
        self.io = io
        super.init()
        central = CBCentralManager(delegate: self, queue: queue)
        peripheral = CBPeripheralManager(delegate: self, queue: queue)
    }

    // Run all command handling on the CB queue so we never touch CoreBluetooth from another thread.
    func handle(_ header: [String: Any], _ payload: Data) {
        queue.async { self.handleOnQueue(header, payload) }
    }

    private func handleOnQueue(_ header: [String: Any], _ payload: Data) {
        guard let type = header["type"] as? String else { return }
        switch type {
        case "start":
            serviceUUID = CBUUID(string: header["service_uuid"] as! String)
            nodeInfoUUID = CBUUID(string: header["node_info_uuid"] as! String)
            packetInUUID = CBUUID(string: header["packet_in_uuid"] as! String)
            packetOutUUID = CBUUID(string: header["packet_out_uuid"] as! String)
            nodeInfo = payload
            started = true
            startIfReady()
        case "set_node_info":
            nodeInfo = payload
        case "connect":
            if let addr = header["addr"] as? String { connect(addr) }
        case "disconnect":
            if let addr = header["addr"] as? String { disconnect(addr) }
        case "send_central":
            if let addr = header["addr"] as? String {
                let reliable = (header["reliable"] as? Bool) ?? false
                sendCentral(addr, payload, reliable: reliable)
            }
        case "send_peripheral":
            if let cid = header["central_id"] as? String { sendPeripheral(cid, payload) }
        default:
            io.log("unknown command type=\(type)")
        }
    }

    // MARK: Startup

    private func startIfReady() {
        guard started, centralReady, peripheralReady else { return }
        addServiceAndAdvertise()
        startScanning()
        if !readyAnnounced {
            readyAnnounced = true
            io.send(["type": "ready"])
        }
    }

    private func addServiceAndAdvertise() {
        let svc = CBMutableService(type: serviceUUID, primary: true)
        let ni = CBMutableCharacteristic(type: nodeInfoUUID, properties: [.read], value: nil, permissions: [.readable])
        let pin = CBMutableCharacteristic(type: packetInUUID, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
        // Indicate (ATT-acknowledged) so peer→central delivery is reliable.
        let pout = CBMutableCharacteristic(type: packetOutUUID, properties: [.indicate], value: nil, permissions: [.readable])
        packetOutChar = pout
        svc.characteristics = [ni, pin, pout]
        peripheral.add(svc)
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID!],
            CBAdvertisementDataLocalNameKey: "Sidepath",
        ])
    }

    private func startScanning() {
        central.scanForPeripherals(withServices: [serviceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: true,
        ])
    }

    // MARK: Central role

    private func connect(_ addr: String) {
        guard let p = discovered[addr] else {
            io.send(["type": "peer_failed", "addr": addr, "error": "unknown peripheral"])
            return
        }
        p.delegate = self
        central.connect(p, options: nil)
    }

    private func disconnect(_ addr: String) {
        if let p = connected[addr] ?? discovered[addr] {
            central.cancelPeripheralConnection(p)
        }
    }

    private func sendCentral(_ addr: String, _ data: Data, reliable: Bool) {
        guard let p = connected[addr], let ch = piChars[addr] else { return }
        if reliable {
            pendingWriteStarts[addr, default: []].append(Date())
        }
        p.writeValue(data, for: ch, type: reliable ? .withResponse : .withoutResponse)
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        centralReady = (c.state == .poweredOn)
        if centralReady { startIfReady() }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let addr = p.identifier.uuidString
        discovered[addr] = p
        io.send(["type": "scan", "addr": addr, "rssi": RSSI.intValue])
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        connected[p.identifier.uuidString] = p
        p.discoverServices([serviceUUID])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        io.send(["type": "peer_failed", "addr": p.identifier.uuidString, "error": error?.localizedDescription ?? "connect failed"])
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        let addr = p.identifier.uuidString
        connected.removeValue(forKey: addr)
        piChars.removeValue(forKey: addr)
        poChars.removeValue(forKey: addr)
        niChars.removeValue(forKey: addr)
        let pending = pendingWriteStarts.removeValue(forKey: addr) ?? []
        for startedAt in pending {
            io.send([
                "type": "link_sample",
                "role": "central",
                "addr": addr,
                "latency_ms": elapsedMs(since: startedAt),
                "ok": false,
            ])
        }
        // error is nil for a clean local disconnect; otherwise it carries the BLE reason
        // (supervision timeout, peer-initiated close, etc.) — forward it so the node can log why.
        io.send(["type": "peer_disconnected", "addr": addr, "reason": error?.localizedDescription ?? "clean"])
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = p.services?.first(where: { $0.uuid == serviceUUID }) else {
            io.send(["type": "peer_failed", "addr": p.identifier.uuidString, "error": "no Sidepath service"])
            central.cancelPeripheralConnection(p)
            return
        }
        p.discoverCharacteristics([nodeInfoUUID, packetInUUID, packetOutUUID], for: svc)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let addr = p.identifier.uuidString
        for ch in service.characteristics ?? [] {
            switch ch.uuid {
            case nodeInfoUUID: niChars[addr] = ch
            case packetInUUID: piChars[addr] = ch
            case packetOutUUID: poChars[addr] = ch
            default: break
            }
        }
        guard piChars[addr] != nil else {
            io.send(["type": "peer_failed", "addr": addr, "error": "missing PACKET_IN"])
            central.cancelPeripheralConnection(p)
            return
        }
        // Read NODE_INFO; peer_connected is emitted once we have it (didUpdateValueFor below).
        if let ni = niChars[addr] {
            p.readValue(for: ni)
        } else {
            emitPeerConnected(p, nodeInfo: Data())
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let addr = p.identifier.uuidString
        if characteristic.uuid == nodeInfoUUID {
            emitPeerConnected(p, nodeInfo: characteristic.value ?? Data())
            // Subscribe to PACKET_OUT for indications now that the peer is known.
            if let po = poChars[addr] { p.setNotifyValue(true, for: po) }
        } else if characteristic.uuid == packetOutUUID {
            io.send(["type": "central_frame", "addr": addr], payload: characteristic.value ?? Data())
        }
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == packetInUUID else { return }
        let addr = p.identifier.uuidString
        let startedAt = popPendingWriteStart(for: addr) ?? Date()
        io.send([
            "type": "link_sample",
            "role": "central",
            "addr": addr,
            "latency_ms": elapsedMs(since: startedAt),
            "ok": error == nil,
        ])
    }

    private func emitPeerConnected(_ p: CBPeripheral, nodeInfo: Data) {
        let mtu = p.maximumWriteValueLength(for: .withResponse)
        io.send(["type": "peer_connected", "addr": p.identifier.uuidString, "mtu": mtu], payload: nodeInfo)
    }

    // MARK: Peripheral role

    func peripheralManagerDidUpdateState(_ pm: CBPeripheralManager) {
        peripheralReady = (pm.state == .poweredOn)
        if peripheralReady { startIfReady() }
    }

    func peripheralManager(_ pm: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == nodeInfoUUID {
            if request.offset > nodeInfo.count {
                pm.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = nodeInfo.subdata(in: request.offset ..< nodeInfo.count)
            pm.respond(to: request, withResult: .success)
        } else {
            pm.respond(to: request, withResult: .requestNotSupported)
        }
    }

    func peripheralManager(_ pm: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            if req.characteristic.uuid == packetInUUID, let v = req.value {
                io.send(["type": "peripheral_frame", "central_id": req.central.identifier.uuidString], payload: v)
            }
        }
        if let first = requests.first { pm.respond(to: first, withResult: .success) }
    }

    func peripheralManager(_ pm: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        let cid = central.identifier.uuidString
        subscribers[cid] = central
        io.send(["type": "subscribed", "central_id": cid, "mtu": central.maximumUpdateValueLength])
    }

    func peripheralManager(_ pm: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        let cid = central.identifier.uuidString
        subscribers.removeValue(forKey: cid)
        pendingOut.removeAll { $0.central.identifier.uuidString == cid }
        io.send(["type": "unsubscribed", "central_id": cid])
    }

    /// CoreBluetooth signals its transmit queue has drained — resume flushing indications.
    func peripheralManagerIsReady(toUpdateSubscribers pm: CBPeripheralManager) {
        flushOut()
    }

    private func sendPeripheral(_ centralID: String, _ data: Data) {
        if centralID == "*" {
            for (_, c) in subscribers { pendingOut.append((c, data)) }
        } else if let c = subscribers[centralID] {
            pendingOut.append((c, data))
        } else {
            return
        }
        flushOut()
    }

    /// Drains pendingOut via updateValue; stops when CoreBluetooth's queue is full (resumes on ready).
    private func flushOut() {
        guard let ch = packetOutChar else { return }
        while let head = pendingOut.first {
            let ok = peripheral.updateValue(head.data, for: ch, onSubscribedCentrals: [head.central])
            if ok {
                pendingOut.removeFirst()
            } else {
                // Queue full — wait for peripheralManagerIsReadyToUpdateSubscribers.
                return
            }
        }
    }

    private func elapsedMs(since startedAt: Date) -> Int {
        max(1, min(Int(Date().timeIntervalSince(startedAt) * 1000), Int(Int32.max)))
    }

    private func popPendingWriteStart(for addr: String) -> Date? {
        guard var starts = pendingWriteStarts[addr], !starts.isEmpty else { return nil }
        let startedAt = starts.removeFirst()
        pendingWriteStarts[addr] = starts.isEmpty ? nil : starts
        return startedAt
    }
}

// MARK: - main

let io = FrameIO()
let helper = Helper(io: io)

// Read stdin on a background thread so the main RunLoop drives CoreBluetooth callbacks.
Thread.detachNewThread {
    io.readLoop { header, payload in helper.handle(header, payload) }
}

RunLoop.main.run()
