//go:build !darwin && !linux

package modem

import (
	"fmt"
	"os"
	"runtime"
)

// openSerial is unsupported off darwin/linux; the modem CLI reports this
// cleanly rather than failing to build.
func openSerial(path string, baud int) (*os.File, error) {
	return nil, fmt.Errorf("serial modem not supported on %s", runtime.GOOS)
}
