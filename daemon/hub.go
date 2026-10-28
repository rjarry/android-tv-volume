// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Robin Jarry

package main

import (
	"io"
	"log"
	"net"
	"sync"
	"time"
)

const writeTimeout = 200 * time.Millisecond

// hub broadcasts volume actions to every connected client. While no client is
// connected, volume keys are replayed to the system instead of being consumed.
type hub struct {
	mu    sync.Mutex
	conns map[net.Conn]struct{}
}

func newHub() *hub {
	return &hub{conns: make(map[net.Conn]struct{})}
}

func (h *hub) listen(addr string) error {
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				log.Printf("accept: %v", err)
				return
			}
			if tcp, ok := conn.(*net.TCPConn); ok {
				tcp.SetNoDelay(true)
			}
			h.add(conn)
		}
	}()
	return nil
}

func (h *hub) add(conn net.Conn) {
	h.mu.Lock()
	h.conns[conn] = struct{}{}
	count := len(h.conns)
	h.mu.Unlock()
	log.Printf("client connected: %s (%d total)", conn.RemoteAddr(), count)
	go func() {
		io.Copy(io.Discard, conn)
		h.remove(conn)
	}()
}

func (h *hub) remove(conn net.Conn) {
	h.mu.Lock()
	_, found := h.conns[conn]
	delete(h.conns, conn)
	count := len(h.conns)
	h.mu.Unlock()
	conn.Close()
	if found {
		log.Printf("client disconnected: %s (%d left)", conn.RemoteAddr(), count)
	}
}

func (h *hub) connected() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns) > 0
}

func (h *hub) send(action string) {
	h.mu.Lock()
	conns := make([]net.Conn, 0, len(h.conns))
	for conn := range h.conns {
		conns = append(conns, conn)
	}
	h.mu.Unlock()

	line := []byte(action + "\n")
	for _, conn := range conns {
		conn.SetWriteDeadline(time.Now().Add(writeTimeout))
		if _, err := conn.Write(line); err != nil {
			log.Printf("write %s: %v", conn.RemoteAddr(), err)
			h.remove(conn)
		}
	}
}
