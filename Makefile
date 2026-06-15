.PHONY: help build test test-go build-macos build-macos-helper bot clean sp sp-install build-esp32 install-esp32

.DEFAULT_GOAL := help

help: ## List available targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

build: ## Build the Android modules (gradle)
	cd android && ./gradlew :sidepath-protocol:build :sidepath-chat:build :sidepath-meshcore:assembleDebug :sidepath-networking:assembleDebug

test: ## Run the Android unit tests (gradle)
	cd android && ./gradlew :sidepath-protocol:test :sidepath-chat:test :sidepath-meshcore:testDebugUnitTest :sidepath-networking:testDebugUnitTest

clean: ## Clean the Android build (gradle)
	cd android && ./gradlew clean

test-go: ## Run the Go core tests
	go test ./core/

# Universal sp CLI: one binary for foreground commands and the background daemon.
sp: ## Build the universal sp CLI -> ./sp
	go build -o sp ./cmd/sp

sp-install: ## Install the sp CLI via 'go install' (to $GOBIN / $GOPATH/bin)
	go install ./cmd/sp

# Native CoreBluetooth helper (Swift). The Go node spawns bin/sidepath-macos-ble-helper at runtime.
build-macos-helper: ## Build the native CoreBluetooth helper (Swift)
	swiftc -O macos-ble-helper/*.swift -o bin/sidepath-macos-ble-helper -framework CoreBluetooth -framework Foundation

build-macos: build-macos-helper ## Build the macOS Sidepath node
	go build -o bin/sidepath-macos ./cmd/sidepath-macos

ESP32_ADMIN_PUBKEY ?=
# Optional fixed identity for a built ESP32 node: a 64-hex-char (32-byte) Ed25519 seed.
# Empty (default) = the node generates and persists its own seed in NVS on first boot.
# Override to pin an identity, e.g.:  make build-esp32 ESP32_NODE_SEED=000102...1f
# (dummy placeholder for now — supply a real 64-hex seed when you need a fixed node id.)
ESP32_NODE_SEED ?=
ESP32_EXTRA_FLAGS := -DSIDEPATH_ADMIN_PUBKEY="$(ESP32_ADMIN_PUBKEY)"
ifneq ($(strip $(ESP32_NODE_SEED)),)
ESP32_EXTRA_FLAGS += -DSIDEPATH_NODE_SEED_HEX="$(ESP32_NODE_SEED)"
endif
ESP32_BUILD_PROPERTIES := --build-property 'compiler.cpp.extra_flags=$(ESP32_EXTRA_FLAGS)'

# Run the macOS node as a bot driven by a Bun script, e.g.:
#   make bot SCRIPT=bots/time-bot.ts
SCRIPT ?= bots/echo-bot.ts
bot: build-macos ## Run the macOS node as a Bun-driven bot (SCRIPT=...)
	./bin/sidepath-macos --bot $(SCRIPT) --verbose --channels "Public,test,dev"

build-esp32: ## Compile the XIAO ESP32-C6 firmware
	arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 $(ESP32_BUILD_PROPERTIES) firmware/xiao_esp32c6

install-esp32: build-esp32 ## Compile and flash the XIAO ESP32-C6 firmware
	arduino-cli upload  --fqbn esp32:esp32:XIAO_ESP32C6 -p /dev/tty.usbmodem21101 firmware/xiao_esp32c6
