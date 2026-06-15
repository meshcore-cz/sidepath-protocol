//go:build darwin

package modem

import "golang.org/x/sys/unix"

// configureTermios applies raw 8N1 at the given baud on macOS. BSD termios
// encodes the speed directly in Ispeed/Ospeed (arbitrary integers allowed).
func configureTermios(fd, baud int) error {
	t, err := unix.IoctlGetTermios(fd, unix.TIOCGETA)
	if err != nil {
		return err
	}
	applyRaw(t)
	t.Ispeed = uint64(baud)
	t.Ospeed = uint64(baud)
	return unix.IoctlSetTermios(fd, unix.TIOCSETA, t)
}
