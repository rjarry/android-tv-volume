// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Robin Jarry

package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

const (
	evSyn = 0x00
	evKey = 0x01
	evRel = 0x02
	evAbs = 0x03
	evMsc = 0x04
	evSw  = 0x05

	synReport = 0x00

	keyMute       = 113
	keyVolumeDown = 114
	keyVolumeUp   = 115

	evMax  = 0x1f
	keyMax = 0x2ff
	relMax = 0x0f
	absMax = 0x3f
	mscMax = 0x07
	swMax  = 0x10

	eviocgrab = 0x40044590

	uiSetEvBit   = 0x40045564
	uiSetKeyBit  = 0x40045565
	uiSetRelBit  = 0x40045566
	uiSetAbsBit  = 0x40045567
	uiSetMscBit  = 0x40045568
	uiSetSwBit   = 0x4004556d
	uiDevCreate  = 0x5501
	uiDevDestroy = 0x5502

	eventSize = 16
)

type inputID struct {
	Bustype uint16
	Vendor  uint16
	Product uint16
	Version uint16
}

type absInfo struct {
	Value      int32
	Minimum    int32
	Maximum    int32
	Fuzz       int32
	Flat       int32
	Resolution int32
}

type uinputUserDev struct {
	Name         [80]byte
	ID           inputID
	FFEffectsMax uint32
	Absmax       [absMax + 1]int32
	Absmin       [absMax + 1]int32
	Absfuzz      [absMax + 1]int32
	Absflat      [absMax + 1]int32
}

type event struct {
	Sec   int32
	Usec  int32
	Type  uint16
	Code  uint16
	Value int32
}

func decodeEvent(b []byte) event {
	return event{
		Sec:   int32(binary.LittleEndian.Uint32(b[0:])),
		Usec:  int32(binary.LittleEndian.Uint32(b[4:])),
		Type:  binary.LittleEndian.Uint16(b[8:]),
		Code:  binary.LittleEndian.Uint16(b[10:]),
		Value: int32(binary.LittleEndian.Uint32(b[12:])),
	}
}

func encodeEvent(b []byte, ev event) {
	binary.LittleEndian.PutUint32(b[0:], uint32(ev.Sec))
	binary.LittleEndian.PutUint32(b[4:], uint32(ev.Usec))
	binary.LittleEndian.PutUint16(b[8:], ev.Type)
	binary.LittleEndian.PutUint16(b[10:], ev.Code)
	binary.LittleEndian.PutUint32(b[12:], uint32(ev.Value))
}

func ioctl(fd uintptr, req uintptr, arg uintptr) error {
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); errno != 0 {
		return errno
	}
	return nil
}

func iocRead(typ, nr, size uintptr) uintptr {
	return 2<<30 | size<<16 | typ<<8 | nr
}

func deviceName(f *os.File) (string, error) {
	buf := make([]byte, 80)
	err := ioctl(f.Fd(), iocRead('E', 0x06, uintptr(len(buf))),
		uintptr(unsafe.Pointer(&buf[0])))
	if err != nil {
		return "", err
	}
	for i, c := range buf {
		if c == 0 {
			return string(buf[:i]), nil
		}
	}
	return string(buf), nil
}

func deviceID(f *os.File) (inputID, error) {
	var id inputID
	err := ioctl(f.Fd(), iocRead('E', 0x02, unsafe.Sizeof(id)),
		uintptr(unsafe.Pointer(&id)))
	return id, err
}

func deviceBits(f *os.File, ev uintptr, max int) ([]byte, error) {
	buf := make([]byte, max/8+1)
	err := ioctl(f.Fd(), iocRead('E', 0x20+ev, uintptr(len(buf))),
		uintptr(unsafe.Pointer(&buf[0])))
	if err != nil {
		return nil, err
	}
	return buf, nil
}

func deviceAbsInfo(f *os.File, axis uintptr) (absInfo, error) {
	var info absInfo
	err := ioctl(f.Fd(), iocRead('E', 0x40+axis, unsafe.Sizeof(info)),
		uintptr(unsafe.Pointer(&info)))
	return info, err
}

func bitSet(bits []byte, i int) bool {
	return i/8 < len(bits) && bits[i/8]&(1<<(uint(i)%8)) != 0
}

func grab(f *os.File) error {
	return ioctl(f.Fd(), eviocgrab, 1)
}

func ungrab(f *os.File) error {
	return ioctl(f.Fd(), eviocgrab, 0)
}

// mirror creates a uinput device advertising the same identity and capabilities
// as src, so that Android resolves the same key layout and configuration files
// for the events replayed through it. It returns the mirror and the input node
// it was given, which must be left alone by the device scan.
func mirror(src *os.File) (*os.File, string, error) {
	id, err := deviceID(src)
	if err != nil {
		return nil, "", fmt.Errorf("EVIOCGID: %w", err)
	}
	name, err := deviceName(src)
	if err != nil {
		return nil, "", fmt.Errorf("EVIOCGNAME: %w", err)
	}
	types, err := deviceBits(src, 0, evMax)
	if err != nil {
		return nil, "", fmt.Errorf("EVIOCGBIT: %w", err)
	}

	ui, err := os.OpenFile("/dev/uinput", os.O_WRONLY, 0)
	if err != nil {
		return nil, "", err
	}

	setup := uinputUserDev{ID: id}
	copy(setup.Name[:len(setup.Name)-1], name)

	codes := []struct {
		ev   uintptr
		max  int
		bit  uintptr
		axes bool
	}{
		{evKey, keyMax, uiSetKeyBit, false},
		{evRel, relMax, uiSetRelBit, false},
		{evAbs, absMax, uiSetAbsBit, true},
		{evMsc, mscMax, uiSetMscBit, false},
		{evSw, swMax, uiSetSwBit, false},
	}
	for _, c := range codes {
		if !bitSet(types, int(c.ev)) {
			continue
		}
		bits, err := deviceBits(src, c.ev, c.max)
		if err != nil {
			ui.Close()
			return nil, "", fmt.Errorf("EVIOCGBIT(%d): %w", c.ev, err)
		}
		if err := ioctl(ui.Fd(), uiSetEvBit, c.ev); err != nil {
			ui.Close()
			return nil, "", fmt.Errorf("UI_SET_EVBIT(%d): %w", c.ev, err)
		}
		for code := 0; code <= c.max; code++ {
			if !bitSet(bits, code) {
				continue
			}
			if err := ioctl(ui.Fd(), c.bit, uintptr(code)); err != nil {
				ui.Close()
				return nil, "", fmt.Errorf("UI_SET_*BIT(%d, %d): %w", c.ev, code, err)
			}
			if !c.axes {
				continue
			}
			info, err := deviceAbsInfo(src, uintptr(code))
			if err != nil {
				ui.Close()
				return nil, "", fmt.Errorf("EVIOCGABS(%d): %w", code, err)
			}
			setup.Absmin[code] = info.Minimum
			setup.Absmax[code] = info.Maximum
			setup.Absfuzz[code] = info.Fuzz
			setup.Absflat[code] = info.Flat
		}
	}

	buf := (*[unsafe.Sizeof(setup)]byte)(unsafe.Pointer(&setup))[:]
	if _, err := ui.Write(buf); err != nil {
		ui.Close()
		return nil, "", fmt.Errorf("uinput setup: %w", err)
	}
	if err := ioctl(ui.Fd(), uiDevCreate, 0); err != nil {
		ui.Close()
		return nil, "", fmt.Errorf("UI_DEV_CREATE: %w", err)
	}
	node, err := mirrorNode(ui)
	if err != nil {
		destroyMirror(ui)
		return nil, "", err
	}
	return ui, node, nil
}

// mirrorNode resolves the /dev/input node of a freshly created uinput device.
func mirrorNode(ui *os.File) (string, error) {
	buf := make([]byte, 64)
	err := ioctl(ui.Fd(), iocRead('U', 44, uintptr(len(buf))),
		uintptr(unsafe.Pointer(&buf[0])))
	if err != nil {
		return "", fmt.Errorf("UI_GET_SYSNAME: %w", err)
	}
	sysname := string(buf)
	if i := strings.IndexByte(sysname, 0); i >= 0 {
		sysname = sysname[:i]
	}
	matches, err := filepath.Glob("/sys/devices/virtual/input/" + sysname + "/event*")
	if err != nil || len(matches) == 0 {
		return "", fmt.Errorf("no event node for %s", sysname)
	}
	return "/dev/input/" + filepath.Base(matches[0]), nil
}

func destroyMirror(ui *os.File) {
	ioctl(ui.Fd(), uiDevDestroy, 0)
	ui.Close()
}

func hasVolumeKeys(f *os.File) bool {
	types, err := deviceBits(f, 0, evMax)
	if err != nil || !bitSet(types, evKey) {
		return false
	}
	keys, err := deviceBits(f, evKey, keyMax)
	if err != nil {
		return false
	}
	return bitSet(keys, keyVolumeUp) || bitSet(keys, keyVolumeDown)
}
