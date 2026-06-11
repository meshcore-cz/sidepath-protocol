// Minimal Arduino.h stub so mesh.cpp/mesh.h can be compiled and unit-tested on
// the host (the firmware logic only needs integer types and millis()).
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

static inline uint32_t millis() { return 0; }
