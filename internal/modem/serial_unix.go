//go:build darwin || linux

package modem

import (
	"fmt"
	"os"
	"runtime"
	"strings"

	"golang.org/x/sys/unix"
)

// openSerial opens a serial/CDC device in raw 8N1 mode. USB CDC (the C6's USB
// Serial/JTAG) ignores the baud rate, but we still set a sane 115200 so the
// same code drives a real UART bridge. Reads return after a short timeout with
// zero bytes (VMIN=0/VTIME=1) so the reader loop can poll for shutdown.
func openSerial(path string, baud int) (*os.File, error) {
	path = normalizeSerialPath(path)
	fd, err := unix.Open(path, unix.O_RDWR|unix.O_NOCTTY|unix.O_NONBLOCK, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	// Back to blocking mode for the configured-timeout reads below.
	if err := unix.SetNonblock(fd, false); err != nil {
		unix.Close(fd)
		return nil, err
	}
	if err := configureTermios(fd, baud); err != nil {
		unix.Close(fd)
		return nil, err
	}
	return os.NewFile(uintptr(fd), path), nil
}

func normalizeSerialPath(path string) string {
	if runtime.GOOS == "darwin" && strings.HasPrefix(path, "/dev/tty.") {
		return "/dev/cu." + strings.TrimPrefix(path, "/dev/tty.")
	}
	return path
}

// applyRaw fills the common (BSD/Linux) raw-mode flags. Baud is set by the
// per-OS caller, which knows how this platform encodes speed.
func applyRaw(t *unix.Termios) {
	t.Iflag &^= unix.IGNBRK | unix.BRKINT | unix.PARMRK | unix.ISTRIP |
		unix.INLCR | unix.IGNCR | unix.ICRNL | unix.IXON
	t.Oflag &^= unix.OPOST
	t.Lflag &^= unix.ECHO | unix.ECHONL | unix.ICANON | unix.ISIG | unix.IEXTEN
	t.Cflag &^= unix.CSIZE | unix.PARENB
	t.Cflag |= unix.CS8 | unix.CREAD | unix.CLOCAL
	t.Cc[unix.VMIN] = 0
	t.Cc[unix.VTIME] = 1 // 0.1s read timeout
}
