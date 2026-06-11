ADB ?= adb
APK := android/app/build/outputs/apk/debug/app-debug.apk

.PHONY: build install-all devices clean

build:
	cd android && ./gradlew assembleDebug

devices:
	$(ADB) devices -l

install-all: build
	@set -e; \
	devices="$$( $(ADB) devices | awk 'NR > 1 && $$2 == "device" { print $$1 }' )"; \
	if [ -z "$$devices" ]; then \
		echo "No authorized Android devices found."; \
		exit 1; \
	fi; \
	for device in $$devices; do \
		echo "Installing on $$device..."; \
		$(ADB) -s "$$device" install -r "$(APK)"; \
	done

clean:
	cd android && ./gradlew clean
