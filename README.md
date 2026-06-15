# Sidepath Protocol

> **A nearby mesh for when the usual path is not enough.**

[Sidepath](docs/PROTOCOL.md) is an experimental local-first mesh protocol for nearby devices.

Phones, computers, embedded boards, and small relays can discover each other and exchange packets directly over [Bluetooth Low Energy](https://www.bluetooth.com/learn-about-bluetooth/tech-overview/). Messages can travel across multiple hops without requiring internet access, cloud infrastructure, or a central server.

```text
phone  ↔  phone  ↔  relay  ↔  laptop  ↔  phone
```

Sidepath is useful on its own. It can power nearby chat, local services, sensors, and offline applications.

It can also run alongside another network. A gateway may connect the nearby Sidepath mesh to a longer-range system such as [MeshCore](https://github.com/meshcore-dev):

```text
nearby Sidepath mesh  ↔  gateway  ↔  MeshCore LoRa mesh
```

The first implementation focuses on **BLE** and **MeshCore**, but the routing layer is designed to carry other [payload protocols](docs/PROTOCOL.md) as well.

<img src="docs/diagram.png" width="75%" />

---

## What can Sidepath do?

### Nearby communication without the cloud

Devices can exchange messages directly with nearby peers and relay them across multiple hops.

```text
Alice  ↔  relay  ↔  Bob  ↔  Carol
```

This can be useful during outages, at events, inside buildings, while travelling, or anywhere a local network should remain useful without internet access.

### Extend access to MeshCore

A Sidepath gateway can connect nearby devices to a MeshCore LoRa network.

```text
phone  ↔  relay  ↔  gateway  ↔  MeshCore
```

A user may reach the wider MeshCore network without carrying a directly connected LoRa radio.

### Add lightweight relays

A small [BLE relay](firmware/xiao_esp32c6/) can extend the local mesh around a corner, through a building, or across an area with weak coverage.

Relays can be placed in homes, vehicles, cafés, hackerspaces, event venues, or other useful locations. They do not need to be full LoRa nodes.

### Connect network islands

Where useful, Sidepath can also [bridge](bridge/meshcore/) separate MeshCore coverage areas.

```text
MeshCore  ↔  gateway  ↔  Sidepath mesh  ↔  gateway  ↔  MeshCore
```

### Carry new applications

Sidepath forwards opaque [payloads](docs/PROTOCOL.md). The routing layer does not need to understand the application data it carries.

The current protocol includes:

* Sidepath control messages
* complete MeshCore packets
* native [chat messages](docs/CHAT_PROTOCOL.md)
* space for future and experimental payload protocols

---

## A node can run almost anywhere

A Sidepath node is not tied to one type of hardware.

It can run on:

* phones and tablets
* laptops and desktop computers
* Linux servers and small gateways
* Raspberry Pi and similar boards
* routers and home-automation hosts
* BLE-capable microcontrollers
* battery-powered or solar-powered relays
* sensors and other embedded devices

Different devices can contribute in different ways. A phone may provide chat and routing. A fixed gateway may bridge into MeshCore. A constrained embedded board may act as a simple relay.

The first embedded implementation currently targets the [XIAO ESP32-C6](firmware/xiao_esp32c6/), but the protocol is not specific to that board.

---

## How it works

Sidepath has three layers:

```text
BLE GATT frame
└── Sidepath datagram
    └── Payload protocol
        ├── Sidepath control
        ├── MeshCore packet
        ├── Sidepath chat
        └── application-defined protocol
```

| Layer             | Responsibility                                            |
| ----------------- | --------------------------------------------------------- |
| BLE GATT frame    | Fragmentation, reassembly, and CRC validation             |
| Sidepath datagram | Identity, routing, TTL, deduplication, and path recording |
| Payload protocol  | Chat messages, encapsulated packets, or application data  |

Sidepath currently supports:

* TTL-limited flood routing
* source routing over known topology
* signed node announcements
* compact Ed25519-based identities
* acknowledgements and route diagnostics
* forwarding of unknown payload protocols

---

## MeshCore integration

MeshCore is the first external protocol carried over Sidepath.

A [gateway](bridge/meshcore/) can encapsulate a complete MeshCore packet, relay it through nearby Sidepath nodes, and inject it back into MeshCore at another reachable node.

```text
MeshCore packet
      ↓
Sidepath datagram
      ↓
BLE relay path
      ↓
MeshCore packet
```

Sidepath does not partially reimplement MeshCore or modify its inner packet semantics. It simply gives the packet another way through.

---

## Current implementation

| Component                                          | Purpose                                    |
| -------------------------------------------------- | ------------------------------------------ |
| [`core/`](core/)                                   | Pure-Go routing engine                     |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md)             | Sidepath v3 specification                  |
| [`docs/CHAT_PROTOCOL.md`](docs/CHAT_PROTOCOL.md)   | Native chat payload specification          |
| [`bridge/meshcore/`](bridge/meshcore/)             | MeshCore packet bridge                     |
| [`android/`](android/)                             | Kotlin protocol, chat, MeshCore, and networking libraries |
| [`macos/`](macos/)                                 | macOS transport used by `sp daemon run`    |
| [`firmware/xiao_esp32c6/`](firmware/xiao_esp32c6/) | ESP32-C6 relay firmware                    |
| [`bots/`](bots/)                                   | JavaScript and TypeScript bot examples     |

The default transport uses the broadly compatible BLE **1M PHY**.

BLE Coded PHY can be enabled experimentally on supported hardware for longer-range tests. Hardware and operating-system support varies between devices.

---

## Useful links

* [Sidepath Protocol repository](https://github.com/meshcore-cz/sidepath-protocol)
* [Sidepath protocol specification](docs/PROTOCOL.md)
* [Sidepath chat payload specification](docs/CHAT_PROTOCOL.md)
* [meshcore-cz/meshward](https://github.com/meshcore-cz/meshward)
* [Android protocol libraries](android/)
* [ESP32-C6 relay firmware](firmware/xiao_esp32c6/)
* [MeshCore project](https://github.com/meshcore-dev)
* [MeshCore CZ organization](https://github.com/meshcore-cz)

---

## Test

Run the Go test suite:

```bash
go test ./...
```

---

## Meshward

[**meshcore-cz/meshward**](https://github.com/meshcore-cz/meshward) is the first chat app built on Sidepath.

It combines nearby Sidepath communication with optional MeshCore connectivity. Meshward uses the native [Sidepath chat payload](docs/CHAT_PROTOCOL.md).

---

## Status

Sidepath is experimental and under active development.

The initial scope is intentionally focused:

```text
Sidepath Protocol
├── nearby multi-hop communication
├── Bluetooth Low Energy transport
├── native chat payloads
├── MeshCore packet bridging
├── Android and macOS nodes
└── ESP32-C6 relay firmware
```

Expect protocol changes and hardware-specific limitations while the project evolves.

---

## Vision

A useful network should not depend on one radio, one route, one class of device, or a permanent connection to the cloud.

A phone can relay a message.
A tiny board can extend the path.
A gateway can connect the local mesh to MeshCore.

> **Sidepath gives packets another way through.**
