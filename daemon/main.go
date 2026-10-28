// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Robin Jarry

package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

var (
	addr = flag.String("addr", "127.0.0.1:38501",
		"address the volume action stream listens on")
	repeatDelay = flag.Duration("repeat-delay", 350*time.Millisecond,
		"delay before a held volume key starts repeating")
	repeatInterval = flag.Duration("repeat-interval", 110*time.Millisecond,
		"interval between repeats of a held volume key")
	scanInterval = flag.Duration("scan-interval", time.Second,
		"interval between scans for new input devices")
	only = flag.String("only", "",
		"comma separated input nodes to grab instead of autodetecting them")
)

// maxDevices caps how many devices can be grabbed at once, as a backstop
// against runaway mirroring.
const maxDevices = 16

type device struct {
	path string
	node string
	name string
	src  *os.File
	ui   *os.File
	hub  *hub

	closed       sync.Once
	mu           sync.Mutex
	repeatStop   chan struct{}
	kernelRepeat bool
}

func action(code uint16) string {
	switch code {
	case keyVolumeUp:
		return "up"
	case keyVolumeDown:
		return "down"
	case keyMute:
		return "mute"
	}
	return ""
}

func openDevice(path string, h *hub) (*device, error) {
	src, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	if *only == "" && !hasVolumeKeys(src) {
		src.Close()
		return nil, nil
	}
	name, err := deviceName(src)
	if err != nil {
		src.Close()
		return nil, err
	}
	ui, node, err := mirror(src)
	if err != nil {
		src.Close()
		return nil, err
	}
	if err := grab(src); err != nil {
		destroyMirror(ui)
		src.Close()
		return nil, err
	}
	log.Printf("grabbed %s (%s), mirrored to %s", path, name, node)
	return &device{path: path, node: node, name: name, src: src, ui: ui, hub: h}, nil
}

func (d *device) close() {
	d.closed.Do(func() {
		d.stopRepeat()
		ungrab(d.src)
		d.src.Close()
		destroyMirror(d.ui)
		log.Printf("released %s (%s)", d.path, d.name)
	})
}

func (d *device) replay(events []event) {
	if len(events) == 0 {
		return
	}
	buf := make([]byte, eventSize*len(events))
	for i, ev := range events {
		encodeEvent(buf[i*eventSize:], ev)
	}
	if _, err := d.ui.Write(buf); err != nil {
		log.Printf("replay to mirror of %s: %v", d.path, err)
	}
}

func (d *device) stopRepeat() {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.repeatStop != nil {
		close(d.repeatStop)
		d.repeatStop = nil
	}
}

// startRepeat synthesizes key repeats for a held volume key. Android disables
// kernel autorepeat on the devices it opens, so the grabbed device usually
// reports a single press until the key is released.
func (d *device) startRepeat(code uint16) {
	d.stopRepeat()
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.kernelRepeat {
		return
	}
	stop := make(chan struct{})
	d.repeatStop = stop
	go func() {
		timer := time.NewTimer(*repeatDelay)
		defer timer.Stop()
		select {
		case <-stop:
			return
		case <-timer.C:
		}
		ticker := time.NewTicker(*repeatInterval)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				d.hub.send(action(code))
			}
		}
	}()
}

func (d *device) handleVolume(ev event) {
	switch ev.Value {
	case 0:
		d.stopRepeat()
	case 1:
		d.hub.send(action(ev.Code))
		if ev.Code != keyMute {
			d.startRepeat(ev.Code)
		}
	case 2:
		d.mu.Lock()
		d.kernelRepeat = true
		d.mu.Unlock()
		d.stopRepeat()
		d.hub.send(action(ev.Code))
	}
}

func (d *device) run(done chan<- string) {
	defer func() {
		d.close()
		done <- d.path
	}()

	buf := make([]byte, eventSize*64)
	packet := make([]event, 0, 16)
	keys, volumeKeys := 0, 0
	for {
		n, err := d.src.Read(buf)
		if err != nil {
			log.Printf("read %s: %v", d.path, err)
			return
		}
		for i := 0; i+eventSize <= n; i += eventSize {
			ev := decodeEvent(buf[i:])
			consume := ev.Type == evKey && action(ev.Code) != "" &&
				d.hub.connected()
			if consume {
				d.handleVolume(ev)
			}
			if ev.Type == evKey {
				keys++
				if consume {
					volumeKeys++
					continue
				}
			}
			packet = append(packet, ev)
			if ev.Type != evSyn || ev.Code != synReport {
				continue
			}
			// Drop the whole report when every key it carried was
			// consumed, so that no orphan scan code reaches Android.
			if keys == 0 || keys != volumeKeys {
				d.replay(packet)
			}
			packet = packet[:0]
			keys, volumeKeys = 0, 0
		}
	}
}

func scan() []string {
	if *only != "" {
		return strings.Split(*only, ",")
	}
	paths, err := filepath.Glob("/dev/input/event*")
	if err != nil {
		log.Printf("scan: %v", err)
		return nil
	}
	return paths
}

func main() {
	flag.Parse()
	log.SetFlags(log.Ltime | log.Lmicroseconds)

	h := newHub()
	if err := h.listen(*addr); err != nil {
		log.Fatalf("listen: %v", err)
	}
	log.Printf("listening on %s", *addr)

	devices := make(map[string]*device)
	mirrors := make(map[string]bool)
	done := make(chan string)
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	ticker := time.NewTicker(*scanInterval)
	defer ticker.Stop()

	rescan := func() {
		for _, path := range scan() {
			// Mirrors expose the same keys as the devices they
			// stand in for and would otherwise be grabbed and
			// mirrored again, endlessly.
			if devices[path] != nil || mirrors[path] {
				continue
			}
			if len(devices) >= maxDevices {
				log.Printf("refusing to grab %s: %d devices already grabbed",
					path, len(devices))
				return
			}
			dev, err := openDevice(path, h)
			if err != nil {
				log.Printf("open %s: %v", path, err)
				continue
			}
			if dev == nil {
				continue
			}
			devices[path] = dev
			mirrors[dev.node] = true
			go dev.run(done)
		}
	}

	rescan()
	for {
		select {
		case <-ticker.C:
			rescan()
		case path := <-done:
			if dev := devices[path]; dev != nil {
				delete(mirrors, dev.node)
			}
			delete(devices, path)
		case sig := <-signals:
			log.Printf("caught %s, releasing devices", sig)
			for _, dev := range devices {
				dev.close()
			}
			return
		}
	}
}
