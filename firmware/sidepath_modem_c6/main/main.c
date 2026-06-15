// Sidepath BLE modem — ESP32-C6 firmware entry point.
//
// The board is an external BLE radio for a host (macOS/Linux/Android) over USB
// serial. It does exactly one thing: move opaque Sidepath packets over BLE
// advertisements. No chat logic, no routing database, no MeshCore logic — those
// live on the host. Received frames are reported to the host and, when relay
// mode is on, re-flooded with a decremented TTL after a dedup check.

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"

#include "ble.h"
#include "frame.h"
#include "relay.h"
#include "serial.h"

static const char *TAG = "sp.modem";

static sp_relay_t s_relay;

static uint32_t now_ms(void) { return (uint32_t)(esp_timer_get_time() / 1000); }

// on_rx runs on the NimBLE host task for each well-formed frame off the air. It
// always reports the frame to the host; when relay mode is on it applies the
// connectionless relay rules (dedup by content hash, drop on TTL=0, decrement,
// re-advertise) and emits a RELAY event for each re-flooded frame.
static void on_rx(int8_t rssi, bool coded, uint8_t ttl, const uint8_t *content,
                  size_t len) {
    s_relay.rx++;
    sp_serial_emit_rx(rssi, coded ? "CODED" : "1M", ttl, content, len);

    if (!sp_serial_relay_enabled()) return;

    uint32_t hash = sp_hash(content, len);
    uint8_t out_ttl = 0;
    switch (sp_relay_consider(&s_relay, hash, ttl, now_ms(), &out_ttl)) {
        case SP_RELAY_FORWARD:
            sp_ble_transmit(content, len, out_ttl);
            sp_serial_emit_relay(hash, out_ttl);
            break;
        case SP_RELAY_DUP:
        case SP_RELAY_DEAD:
            break; // silently dropped; counters track these
    }
}

void app_main(void) {
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    sp_relay_init(&s_relay);
    sp_serial_init(&s_relay);

    if (sp_ble_init(on_rx) != 0) {
        ESP_LOGE(TAG, "BLE init failed");
        return;
    }

    // Boot banner: the host uses this to detect a ready modem.
    sp_serial_printf("READY sidepath-modem ext_adv=%d\n", sp_ble_ext_adv() ? 1 : 0);
    ESP_LOGI(TAG, "sidepath modem up");
}
