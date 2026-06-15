#include "ble.h"

#include <string.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "frame.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nimble/hci_common.h"
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/util/util.h"
#include "os/os_mbuf.h"

static const char *TAG = "sp.ble";

// We use a single extended-advertising instance for the connectionless flood.
#define SP_ADV_INSTANCE 0
// Number of advertising events per burst. A handful gives a packet a fair shot
// at being heard without the instance hogging the radio.
#define SP_ADV_BURST_EVENTS 3
// Advertising interval (0.625ms units). Tight for low latency floods.
#define SP_ADV_ITVL_MIN 0x30 // 30ms
#define SP_ADV_ITVL_MAX 0x40 // 40ms
// Scan interval/window (0.625ms units): scan ~continuously.
#define SP_SCAN_ITVL 0x50
#define SP_SCAN_WINDOW 0x50

typedef struct {
    uint8_t ttl;
    uint8_t len;
    uint8_t content[SP_MAX_CONTENT];
} sp_tx_job_t;

static sp_ble_rx_fn s_rx_cb;
static QueueHandle_t s_tx_queue;
static SemaphoreHandle_t s_adv_done; // given on ADV_COMPLETE

static uint8_t s_own_addr_type;
static sp_phy_t s_phy = SP_PHY_1M;
static sp_tx_power_t s_tx_power = SP_TX_MEDIUM;
static bool s_scanning;
static bool s_ext_adv = true;
static bool s_ready; // host sync complete: safe to call GAP

// Map the TX tier to a controller dBm hint. The controller clamps to what it
// supports; the configured value is advisory.
static int8_t tx_dbm(sp_tx_power_t lvl) {
    switch (lvl) {
        case SP_TX_LOW: return -12;
        case SP_TX_HIGH: return 9;
        case SP_TX_MEDIUM:
        default: return 0;
    }
}

static uint8_t phy_hci(sp_phy_t phy) {
    return phy == SP_PHY_CODED ? BLE_HCI_LE_PHY_CODED : BLE_HCI_LE_PHY_1M;
}

const char *sp_phy_name(sp_phy_t phy) {
    return phy == SP_PHY_CODED ? "CODED" : "1M";
}

sp_phy_t sp_ble_phy(void) { return s_phy; }
bool sp_ble_ext_adv(void) { return s_ext_adv; }
bool sp_ble_scanning(void) { return s_scanning; }

// --- advertising instance configuration ----------------------------------

static int gap_event(struct ble_gap_event *event, void *arg);

// configure_adv_instance (re)configures SP_ADV_INSTANCE for the given PHY and
// the current TX power. Returns 0 on success. Non-connectable, non-scannable,
// non-legacy (extended) PDUs.
static int configure_adv_instance(sp_phy_t phy) {
    struct ble_gap_ext_adv_params params = {0};
    params.scannable = 0;
    params.connectable = 0;
    params.legacy_pdu = 0; // extended PDUs
    params.own_addr_type = s_own_addr_type;
    params.primary_phy = phy_hci(phy);
    params.secondary_phy = phy_hci(phy);
    params.tx_power = tx_dbm(s_tx_power);
    params.sid = 0;
    params.itvl_min = SP_ADV_ITVL_MIN;
    params.itvl_max = SP_ADV_ITVL_MAX;
    params.channel_map = 0; // all three primary channels

    int8_t selected = 0;
    // Remove any prior instance config first; ignore "not found".
    ble_gap_ext_adv_remove(SP_ADV_INSTANCE);
    return ble_gap_ext_adv_configure(SP_ADV_INSTANCE, &params, &selected,
                                     gap_event, NULL);
}

// ensure_adv_phy configures the instance for `phy`, falling back to 1M if the
// controller rejects a coded configuration. Writes the PHY actually applied.
static int ensure_adv_phy(sp_phy_t want, sp_phy_t *applied) {
    int rc = configure_adv_instance(want);
    if (rc != 0 && want == SP_PHY_CODED) {
        ESP_LOGW(TAG, "coded adv config failed rc=%d, falling back to 1M", rc);
        rc = configure_adv_instance(SP_PHY_1M);
        if (rc == 0) {
            *applied = SP_PHY_1M;
            return 0;
        }
    }
    if (rc == 0) {
        *applied = want;
    }
    return rc;
}

int sp_ble_set_phy(sp_phy_t phy, sp_phy_t *active) {
    if (!s_ready) {
        s_phy = phy; // applied at sync
        if (active) *active = phy;
        return 0;
    }
    bool was_scanning = s_scanning;
    if (was_scanning) ble_gap_disc_cancel();

    sp_phy_t applied = phy;
    int rc = ensure_adv_phy(phy, &applied);
    if (rc != 0) {
        ESP_LOGE(TAG, "set_phy failed rc=%d", rc);
        return rc;
    }
    s_phy = applied;
    if (active) *active = applied;

    if (was_scanning) sp_ble_start_scan();
    return 0;
}

int sp_ble_set_tx_power(sp_tx_power_t level) {
    s_tx_power = level;
    if (!s_ready) return 0;
    sp_phy_t applied = s_phy;
    return ensure_adv_phy(s_phy, &applied);
}

// --- transmit -------------------------------------------------------------

// build_adv_mbuf formats one Manufacturer-Specific-Data AD structure carrying
// the frame and returns it as an mbuf ready for ble_gap_ext_adv_set_data.
static struct os_mbuf *build_adv_mbuf(const sp_tx_job_t *job) {
    uint8_t ad[2 + SP_MFG_OVERHEAD + SP_MAX_CONTENT];
    size_t n = 0;
    uint8_t body_len = SP_MFG_OVERHEAD + job->len; // mfg payload length
    ad[n++] = (uint8_t)(1 + body_len);             // AD length (type + body)
    ad[n++] = 0xFF;                                // Manufacturer Specific Data
    ad[n++] = (uint8_t)(SP_COMPANY_ID & 0xFF);     // company id, little-endian
    ad[n++] = (uint8_t)(SP_COMPANY_ID >> 8);
    ad[n++] = SP_FRAME_VERSION;
    ad[n++] = job->ttl;
    memcpy(&ad[n], job->content, job->len);
    n += job->len;

    // ble_hs_mbuf_from_flat copies the formatted AD payload into an mbuf the
    // GAP layer takes ownership of.
    return ble_hs_mbuf_from_flat(ad, (uint16_t)n);
}

// tx_task serializes advertising: one burst per job, waiting for the
// ADV_COMPLETE event (or a timeout) before starting the next so jobs never
// clobber each other's data.
static void tx_task(void *arg) {
    sp_tx_job_t job;
    for (;;) {
        if (xQueueReceive(s_tx_queue, &job, portMAX_DELAY) != pdTRUE) continue;
        if (!s_ready) continue;

        ble_gap_ext_adv_stop(SP_ADV_INSTANCE); // ensure idle before set_data

        struct os_mbuf *om = build_adv_mbuf(&job);
        if (!om) {
            ESP_LOGE(TAG, "tx: out of mbufs");
            continue;
        }
        int rc = ble_gap_ext_adv_set_data(SP_ADV_INSTANCE, om);
        if (rc != 0) {
            ESP_LOGE(TAG, "tx: set_data rc=%d", rc);
            continue;
        }
        // Drain any stale completion signal, then start the burst.
        xSemaphoreTake(s_adv_done, 0);
        rc = ble_gap_ext_adv_start(SP_ADV_INSTANCE, 0, SP_ADV_BURST_EVENTS);
        if (rc != 0) {
            ESP_LOGE(TAG, "tx: adv_start rc=%d", rc);
            continue;
        }
        // Wait for the burst to finish (bounded so a missed event can't wedge us).
        xSemaphoreTake(s_adv_done, pdMS_TO_TICKS(500));
    }
}

int sp_ble_transmit(const uint8_t *content, size_t len, uint8_t ttl) {
    if (len > SP_MAX_CONTENT) return BLE_HS_EMSGSIZE;
    sp_tx_job_t job;
    job.ttl = ttl;
    job.len = (uint8_t)len;
    memcpy(job.content, content, len);
    if (xQueueSend(s_tx_queue, &job, 0) != pdTRUE) {
        ESP_LOGW(TAG, "tx queue full, dropping frame");
        return BLE_HS_ENOMEM;
    }
    return 0;
}

// --- scanning -------------------------------------------------------------

int sp_ble_start_scan(void) {
    if (!s_ready) {
        s_scanning = true; // applied at sync
        return 0;
    }
    struct ble_gap_ext_disc_params uncoded = {
        .itvl = SP_SCAN_ITVL, .window = SP_SCAN_WINDOW, .passive = 1,
    };
    struct ble_gap_ext_disc_params coded = uncoded;

    const struct ble_gap_ext_disc_params *up = NULL;
    const struct ble_gap_ext_disc_params *cp = NULL;
    if (s_phy == SP_PHY_CODED) {
        cp = &coded; // scan the coded primary channels
    } else {
        up = &uncoded;
    }

    // duration/period 0 = scan forever; filter_duplicates 0 = surface re-floods
    // (we dedup ourselves so the controller must not hide repeats).
    int rc = ble_gap_ext_disc(s_own_addr_type, 0, 0, 0, 0, 0, up, cp,
                              gap_event, NULL);
    if (rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGE(TAG, "ext_disc rc=%d", rc);
        return rc;
    }
    s_scanning = true;
    return 0;
}

int sp_ble_stop_scan(void) {
    s_scanning = false;
    if (!s_ready) return 0;
    int rc = ble_gap_disc_cancel();
    if (rc != 0 && rc != BLE_HS_EALREADY) return rc;
    return 0;
}

// handle_report parses a discovery report's manufacturer data and, if it is one
// of ours, hands the opaque frame to the rx callback.
static void handle_report(int8_t rssi, uint8_t prim_phy, const uint8_t *data,
                          uint8_t len) {
    struct ble_hs_adv_fields fields;
    if (ble_hs_adv_parse_fields(&fields, data, len) != 0) return;
    if (fields.mfg_data == NULL || fields.mfg_data_len < SP_MFG_OVERHEAD) return;

    const uint8_t *m = fields.mfg_data;
    uint16_t company = (uint16_t)m[0] | ((uint16_t)m[1] << 8);
    if (company != SP_COMPANY_ID) return;
    if (m[2] != SP_FRAME_VERSION) return;

    uint8_t ttl = m[3];
    const uint8_t *content = m + SP_MFG_OVERHEAD;
    size_t clen = fields.mfg_data_len - SP_MFG_OVERHEAD;
    bool coded = (prim_phy == BLE_HCI_LE_PHY_CODED);
    if (s_rx_cb) s_rx_cb(rssi, coded, ttl, content, clen);
}

// --- GAP event dispatch ---------------------------------------------------

static int gap_event(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
        case BLE_GAP_EVENT_EXT_DISC:
            handle_report(event->ext_disc.rssi, event->ext_disc.prim_phy,
                          event->ext_disc.data, event->ext_disc.length_data);
            return 0;
        case BLE_GAP_EVENT_DISC_COMPLETE:
            // Discovery ended (e.g. cancelled for a reconfigure); restart if the
            // host still wants to scan.
            if (s_scanning) sp_ble_start_scan();
            return 0;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            xSemaphoreGive(s_adv_done);
            return 0;
        default:
            return 0;
    }
}

// --- host stack lifecycle -------------------------------------------------

static void on_reset(int reason) { ESP_LOGW(TAG, "nimble reset reason=%d", reason); }

static void on_sync(void) {
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "ensure_addr rc=%d", rc);
        return;
    }
    rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "infer_auto rc=%d", rc);
        return;
    }
    s_ready = true;

    // Apply the desired PHY (with coded->1M fallback) and TX power, then start
    // scanning if it was requested before sync.
    sp_phy_t applied = s_phy;
    if (ensure_adv_phy(s_phy, &applied) == 0) {
        s_phy = applied;
    } else {
        ESP_LOGE(TAG, "adv instance config failed at sync");
    }
    if (s_scanning) {
        s_scanning = false; // force a real start
        sp_ble_start_scan();
    }
    ESP_LOGI(TAG, "BLE ready, phy=%s ext_adv=%d", sp_phy_name(s_phy), s_ext_adv);
}

static void host_task(void *arg) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

int sp_ble_init(sp_ble_rx_fn rx_cb) {
    s_rx_cb = rx_cb;
    s_tx_queue = xQueueCreate(8, sizeof(sp_tx_job_t));
    s_adv_done = xSemaphoreCreateBinary();
    if (!s_tx_queue || !s_adv_done) return -1;

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init: %d", err);
        return -1;
    }

    // Connectionless-only: no GATT/GAP service registration needed (and the
    // peripheral role that ble_svc_gap_init wants is disabled in sdkconfig).
    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;

    xTaskCreate(tx_task, "sp_tx", 3072, NULL, 5, NULL);
    nimble_port_freertos_init(host_task);
    return 0;
}
