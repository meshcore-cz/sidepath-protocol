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

ESP32_ADMIN_PUBKEY ?= 6453f60892fe16b9a9de110bd021085ef7dc3d9eabc343db9edc1a9446885127
ESP32_BUILD_PROPERTIES := --build-property 'compiler.cpp.extra_flags=-DBLEEDGE_ADMIN_PUBKEY="$(ESP32_ADMIN_PUBKEY)"'

# Run the macOS node as a bot driven by a Bun script, e.g.:
#   make bot SCRIPT=bots/time-bot.ts
SCRIPT ?= bots/echo-bot.ts
bot: build-macos
	./bin/bleedge-macos --bot $(SCRIPT) --verbose --channels "Public,test,dev"

build-esp32:
	arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 $(ESP32_BUILD_PROPERTIES) firmware/xiao_esp32c6

install-esp32: build-esp32
	arduino-cli upload  --fqbn esp32:esp32:XIAO_ESP32C6 -p /dev/tty.usbmodem21101 firmware/xiao_esp32c6
