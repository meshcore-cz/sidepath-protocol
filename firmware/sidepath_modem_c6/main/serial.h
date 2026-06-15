// USB-serial command/event interface for the Sidepath modem.
//
// Line-oriented ASCII over the ESP32-C6 USB Serial/JTAG port at any baud (USB
// CDC ignores baud). The host sends one command per line; the modem replies
// with OK/ERR and emits asynchronous RX/RELAY events. See PROTOCOL.md.

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "relay.h"

// sp_serial_init installs the USB-serial driver and starts the reader task.
// The relay state pointer is shared with main for STATS reporting.
void sp_serial_init(sp_relay_t *relay);

// sp_serial_relay_enabled reports the RELAY_ON/RELAY_OFF toggle state, read by
// the BLE rx handler to decide whether to re-advertise received frames.
bool sp_serial_relay_enabled(void);

// Event emitters (thread-safe). Called from the BLE host task and main.
void sp_serial_emit_rx(int8_t rssi, const char *phy, uint8_t ttl,
                       const uint8_t *content, size_t len);
void sp_serial_emit_relay(uint32_t hash, uint8_t ttl);
void sp_serial_emit_err(const char *msg);
void sp_serial_printf(const char *fmt, ...);
