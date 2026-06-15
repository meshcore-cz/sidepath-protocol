idf_component_register(
    SRCS
        "main.c"
        "ble.c"
        "serial.c"
        "relay.c"
    INCLUDE_DIRS "."
    REQUIRES
        bt
        nvs_flash
        esp_timer
        driver
        esp_driver_usb_serial_jtag
        app_update
)
