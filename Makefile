ADB ?= adb
CHAT_APK  := android/chat-app/build/outputs/apk/debug/chat-app-debug.apk
DEBUG_APK := android/debug-app/build/outputs/apk/debug/debug-app-debug.apk
# Which app `install-all` deploys: chat (default) or debug.
APK ?= $(CHAT_APK)

.PHONY: build test install-all install-debug devices clean

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

build-esp32:
	arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 firmware/xiao_esp32c6

install-esp32: build-esp32
	arduino-cli upload  --fqbn esp32:esp32:XIAO_ESP32C6 -p /dev/tty.usbmodem21101 firmware/xiao_esp32c6
