//go:build linux

package modem

import (
	"fmt"

	"golang.org/x/sys/unix"
)

// baudConst maps an integer baud to the Linux termios speed constant. Linux
// encodes speed as a flag in c_cflag (plus the c_ispeed/c_ospeed mirror).
func baudConst(baud int) (uint32, error) {
	switch baud {
	case 9600:
		return unix.B9600, nil
	case 19200:
		return unix.B19200, nil
	case 38400:
		return unix.B38400, nil
	case 57600:
		return unix.B57600, nil
	case 115200:
		return unix.B115200, nil
	case 230400:
		return unix.B230400, nil
	case 460800:
		return unix.B460800, nil
	case 921600:
		return unix.B921600, nil
	default:
		return 0, fmt.Errorf("unsupported baud %d", baud)
	}
}

// configureTermios applies raw 8N1 at the given baud on Linux.
func configureTermios(fd, baud int) error {
	speed, err := baudConst(baud)
	if err != nil {
		return err
	}
	t, err := unix.IoctlGetTermios(fd, unix.TCGETS)
	if err != nil {
		return err
	}
	applyRaw(t)
	t.Cflag &^= unix.CBAUD
	t.Cflag |= speed
	t.Ispeed = speed
	t.Ospeed = speed
	return unix.IoctlSetTermios(fd, unix.TCSETS, t)
}
