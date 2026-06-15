// BLE radio glue for the Sidepath modem: NimBLE init, connectionless extended
// advertising of outgoing frames, and extended scanning for incoming ones.
//
// Everything here is connectionless — there is no GATT, no connections. Frames
// ride in Manufacturer-Specific-Data (see frame.h). BLE 5 extended advertising
// is preferred; LE Coded PHY (Long Range) can be selected, with an automatic
// fallback to the 1M PHY if the controller rejects the coded configuration.

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// PHY selection for both advertising and scanning.
typedef enum {
    SP_PHY_1M = 0,
    SP_PHY_CODED = 1,
} sp_phy_t;

// TX power tiers mapped to controller dBm in ble.c.
typedef enum {
    SP_TX_LOW = 0,
    SP_TX_MEDIUM = 1,
    SP_TX_HIGH = 2,
} sp_tx_power_t;

// Callback invoked (on the NimBLE host task) for every well-formed Sidepath
// frame received off the air. `content`/`len` is the opaque payload; `ttl` its
// hop limit; `coded` whether it arrived on the Coded PHY.
typedef void (*sp_ble_rx_fn)(int8_t rssi, bool coded, uint8_t ttl,
                             const uint8_t *content, size_t len);

// sp_ble_init brings up the NimBLE stack and TX worker. rx_cb is retained.
// Returns 0 on success.
int sp_ble_init(sp_ble_rx_fn rx_cb);

// sp_ble_set_phy requests the advertising/scanning PHY. If CODED is requested
// but the controller rejects it, the modem falls back to 1M and *active
// reflects what is really in use. Returns 0 on success (even on fallback).
int sp_ble_set_phy(sp_phy_t phy, sp_phy_t *active);

// sp_ble_set_tx_power applies a TX power tier to the advertising instance.
int sp_ble_set_tx_power(sp_tx_power_t level);

// sp_ble_start_scan / sp_ble_stop_scan toggle extended discovery.
int sp_ble_start_scan(void);
int sp_ble_stop_scan(void);
bool sp_ble_scanning(void);

// sp_ble_transmit queues one frame (opaque content + TTL) for a short
// advertising burst. Used both for host SEND and for relay re-advertise.
// Returns 0 if queued.
int sp_ble_transmit(const uint8_t *content, size_t len, uint8_t ttl);

// Current state, for INFO/STATS.
sp_phy_t sp_ble_phy(void);
bool sp_ble_ext_adv(void); // true if extended advertising is in use
const char *sp_phy_name(sp_phy_t phy);
