#include "serial.h"

#include <ctype.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "driver/usb_serial_jtag.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "ble.h"
#include "frame.h"

static const char *TAG = "sp.serial";

#define SP_SERIAL_LINE_MAX (2 * (SP_MFG_OVERHEAD + SP_MAX_CONTENT) + 32)
#define USB_RX_BUF 512
#define USB_TX_BUF 1024

static sp_relay_t *s_relay;
static bool s_relay_enabled = true;
static SemaphoreHandle_t s_write_mu; // serialize multi-byte event writes

bool sp_serial_relay_enabled(void) { return s_relay_enabled; }

// --- low-level write ------------------------------------------------------

static void write_all(const char *s, size_t n) {
    size_t off = 0;
    while (off < n) {
        int w = usb_serial_jtag_write_bytes(s + off, n - off, pdMS_TO_TICKS(100));
        if (w <= 0) break; // host not draining; drop the rest rather than block
        off += (size_t)w;
    }
}

void sp_serial_printf(const char *fmt, ...) {
    char buf[SP_SERIAL_LINE_MAX];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof(buf)) n = sizeof(buf);
    xSemaphoreTake(s_write_mu, portMAX_DELAY);
    write_all(buf, (size_t)n);
    xSemaphoreGive(s_write_mu);
}

// --- hex helpers ----------------------------------------------------------

static int hex_nib(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

// hex_decode parses an even-length hex string into out. Returns the byte count,
// or -1 on a bad character / odd length / overflow.
static int hex_decode(const char *s, uint8_t *out, size_t cap) {
    size_t n = strlen(s);
    if (n % 2 != 0) return -1;
    size_t bytes = n / 2;
    if (bytes > cap) return -1;
    for (size_t i = 0; i < bytes; i++) {
        int hi = hex_nib(s[2 * i]);
        int lo = hex_nib(s[2 * i + 1]);
        if (hi < 0 || lo < 0) return -1;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return (int)bytes;
}

static void emit_hex(char *dst, const uint8_t *data, size_t len) {
    static const char H[] = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        dst[2 * i] = H[data[i] >> 4];
        dst[2 * i + 1] = H[data[i] & 0xF];
    }
    dst[2 * len] = '\0';
}

// --- async event emitters -------------------------------------------------

void sp_serial_emit_rx(int8_t rssi, const char *phy, uint8_t ttl,
                       const uint8_t *content, size_t len) {
    char hex[2 * (SP_MAX_CONTENT + 1) + 1];
    // The serial "packet" is ttl || content, so the host sees what it sent.
    uint8_t pkt[SP_MAX_CONTENT + 1];
    pkt[0] = ttl;
    memcpy(pkt + 1, content, len);
    emit_hex(hex, pkt, len + 1);
    sp_serial_printf("RX %d %s %s\n", rssi, phy, hex);
}

void sp_serial_emit_relay(uint32_t hash, uint8_t ttl) {
    sp_serial_printf("RELAY %08x %u\n", hash, ttl);
}

void sp_serial_emit_err(const char *msg) {
    sp_serial_printf("ERR %s\n", msg);
}

// --- command handlers -----------------------------------------------------

static void cmd_info(void) {
    const esp_app_desc_t *app = esp_app_get_description();
    sp_serial_printf("INFO fw=%s chip=esp32c6 phy=%s txpower=%s relay=%s scan=%s ext_adv=%d maxlen=%d\n",
                     app->version, sp_phy_name(sp_ble_phy()),
                     "n/a", // tx power tier is write-only on the controller
                     s_relay_enabled ? "on" : "off",
                     sp_ble_scanning() ? "on" : "off",
                     sp_ble_ext_adv() ? 1 : 0, SP_MAX_CONTENT);
    sp_serial_printf("OK INFO\n");
}

static void cmd_stats(void) {
    uint32_t up = (uint32_t)(esp_timer_get_time() / 1000000);
    sp_serial_printf("STATS rx=%u tx=%u relayed=%u dup=%u ttl_drop=%u seen=%u scan=%s relay=%s uptime=%u\n",
                     s_relay->rx, s_relay->tx, s_relay->relayed, s_relay->dup,
                     s_relay->ttl_drop, s_relay->seen,
                     sp_ble_scanning() ? "on" : "off",
                     s_relay_enabled ? "on" : "off", up);
    sp_serial_printf("OK STATS\n");
}

static void cmd_set_phy(const char *arg) {
    sp_phy_t want;
    if (strcasecmp(arg, "1M") == 0) {
        want = SP_PHY_1M;
    } else if (strcasecmp(arg, "CODED") == 0) {
        want = SP_PHY_CODED;
    } else {
        sp_serial_emit_err("SET_PHY expects 1M|CODED");
        return;
    }
    sp_phy_t active;
    if (sp_ble_set_phy(want, &active) != 0) {
        sp_serial_emit_err("SET_PHY failed");
        return;
    }
    // Report the PHY actually in use, so a coded->1M fallback is visible.
    sp_serial_printf("OK SET_PHY %s\n", sp_phy_name(active));
}

static void cmd_set_tx_power(const char *arg) {
    sp_tx_power_t lvl;
    if (strcasecmp(arg, "LOW") == 0) {
        lvl = SP_TX_LOW;
    } else if (strcasecmp(arg, "MEDIUM") == 0) {
        lvl = SP_TX_MEDIUM;
    } else if (strcasecmp(arg, "HIGH") == 0) {
        lvl = SP_TX_HIGH;
    } else {
        sp_serial_emit_err("SET_TX_POWER expects LOW|MEDIUM|HIGH");
        return;
    }
    if (sp_ble_set_tx_power(lvl) != 0) {
        sp_serial_emit_err("SET_TX_POWER failed");
        return;
    }
    sp_serial_printf("OK SET_TX_POWER %s\n", arg);
}

// cmd_send transmits a host packet. The hex is ttl || content; a bare content
// (no leading byte recognisable as TTL) is impossible to distinguish, so the
// host must always include the leading TTL byte. An empty packet is rejected.
static void cmd_send(const char *arg) {
    uint8_t pkt[SP_MAX_CONTENT + 1];
    int n = hex_decode(arg, pkt, sizeof(pkt));
    if (n < 1) {
        sp_serial_emit_err("SEND expects hex ttl||content (>=1 byte)");
        return;
    }
    uint8_t ttl = pkt[0];
    const uint8_t *content = pkt + 1;
    size_t clen = (size_t)n - 1;
    if (sp_ble_transmit(content, clen, ttl) != 0) {
        sp_serial_emit_err("SEND transmit failed");
        return;
    }
    s_relay->tx++;
    sp_serial_printf("OK SEND %08x\n", sp_hash(content, clen));
}

static void dispatch(char *line) {
    // Split the command word from its (optional) argument.
    char *cmd = line;
    char *arg = strchr(line, ' ');
    if (arg) {
        *arg = '\0';
        arg++;
        while (*arg == ' ') arg++;
    } else {
        arg = line + strlen(line); // empty
    }
    if (*cmd == '\0') return;

    if (strcasecmp(cmd, "PING") == 0) {
        sp_serial_printf("OK PING\n");
    } else if (strcasecmp(cmd, "INFO") == 0) {
        cmd_info();
    } else if (strcasecmp(cmd, "STATS") == 0) {
        cmd_stats();
    } else if (strcasecmp(cmd, "SET_PHY") == 0) {
        cmd_set_phy(arg);
    } else if (strcasecmp(cmd, "SET_TX_POWER") == 0) {
        cmd_set_tx_power(arg);
    } else if (strcasecmp(cmd, "START_SCAN") == 0) {
        if (sp_ble_start_scan() == 0) sp_serial_printf("OK START_SCAN\n");
        else sp_serial_emit_err("START_SCAN failed");
    } else if (strcasecmp(cmd, "STOP_SCAN") == 0) {
        if (sp_ble_stop_scan() == 0) sp_serial_printf("OK STOP_SCAN\n");
        else sp_serial_emit_err("STOP_SCAN failed");
    } else if (strcasecmp(cmd, "SEND") == 0) {
        cmd_send(arg);
    } else if (strcasecmp(cmd, "RELAY_ON") == 0) {
        s_relay_enabled = true;
        sp_serial_printf("OK RELAY_ON\n");
    } else if (strcasecmp(cmd, "RELAY_OFF") == 0) {
        s_relay_enabled = false;
        sp_serial_printf("OK RELAY_OFF\n");
    } else {
        sp_serial_emit_err("unknown command");
    }
}

// reader_task accumulates bytes into lines and dispatches each. CR and LF both
// terminate a line; overlong lines are truncated with an ERR.
static void reader_task(void *arg) {
    char line[SP_SERIAL_LINE_MAX];
    size_t len = 0;
    uint8_t buf[128];
    for (;;) {
        int n = usb_serial_jtag_read_bytes(buf, sizeof(buf), pdMS_TO_TICKS(100));
        for (int i = 0; i < n; i++) {
            char c = (char)buf[i];
            if (c == '\r' || c == '\n') {
                if (len == 0) continue;
                line[len] = '\0';
                dispatch(line);
                len = 0;
            } else if (len < sizeof(line) - 1) {
                line[len++] = c;
            } else {
                len = 0; // overflow: drop the line
                sp_serial_emit_err("line too long");
            }
        }
    }
}

void sp_serial_init(sp_relay_t *relay) {
    s_relay = relay;
    s_write_mu = xSemaphoreCreateMutex();

    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size = USB_RX_BUF,
        .tx_buffer_size = USB_TX_BUF,
    };
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&cfg));
    // Route the ESP log away from the data plane is left to sdkconfig; we keep
    // logs on the same port but every protocol line is self-delimited.
    ESP_LOGI(TAG, "serial ready");

    xTaskCreate(reader_task, "sp_serial", 4096, NULL, 6, NULL);
}
