ADB ?= adb
CHAT_APK  := android/chat-app/build/outputs/apk/debug/chat-app-debug.apk
DEBUG_APK := android/debug-app/build/outputs/apk/debug/debug-app-debug.apk
# Which app `install-all` deploys: chat (default) or debug.
APK ?= $(CHAT_APK)

.PHONY: build test test-go build-macos bot install-all install-debug devices clean

build:
	cd android && ./gradlew assembleDebug

test:
	cd android && ./gradlew :common:testDebugUnitTest

devices:
	$(ADB) devices -l

install-all: build
	@$(MAKE) APK="$(APK)" _install

install-debug: build
	@$(MAKE) APK="$(DEBUG_APK)" _install

_install:
	@set -e; \
	devices="$$( $(ADB) devices | awk 'NR > 1 && $$2 == "device" { print $$1 }' )"; \
	if [ -z "$$devices" ]; then \
		echo "No authorized Android devices found."; \
		exit 1; \
	fi; \
	for device in $$devices; do \
		echo "Installing $(APK) on $$device..."; \
		$(ADB) -s "$$device" install -r "$(APK)"; \
	done

clean:
	cd android && ./gradlew clean

test-go:
	go test ./core/

build-macos:
	go build -o bin/bleedge-macos ./cmd/bleedge-macos

ESP32_ADMIN_PUBKEY ?=
# Optional fixed identity for a built ESP32 node: a 64-hex-char (32-byte) Ed25519 seed.
# Empty (default) = the node generates and persists its own seed in NVS on first boot.
# Override to pin an identity, e.g.:  make build-esp32 ESP32_NODE_SEED=000102...1f
# (dummy placeholder for now — supply a real 64-hex seed when you need a fixed node id.)
ESP32_NODE_SEED ?=
ESP32_EXTRA_FLAGS := -DBLEEDGE_ADMIN_PUBKEY="$(ESP32_ADMIN_PUBKEY)"
ifneq ($(strip $(ESP32_NODE_SEED)),)
ESP32_EXTRA_FLAGS += -DBLEEDGE_NODE_SEED_HEX="$(ESP32_NODE_SEED)"
endif
ESP32_BUILD_PROPERTIES := --build-property 'compiler.cpp.extra_flags=$(ESP32_EXTRA_FLAGS)'

# Run the macOS node as a bot driven by a Bun script, e.g.:
#   make bot SCRIPT=bots/time-bot.ts
SCRIPT ?= bots/echo-bot.ts
bot: build-macos
	./bin/bleedge-macos --bot $(SCRIPT) --verbose --channels "Public,test,dev"

build-esp32:
	arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 $(ESP32_BUILD_PROPERTIES) firmware/xiao_esp32c6

install-esp32: build-esp32
	arduino-cli upload  --fqbn esp32:esp32:XIAO_ESP32C6 -p /dev/tty.usbmodem21101 firmware/xiao_esp32c6
