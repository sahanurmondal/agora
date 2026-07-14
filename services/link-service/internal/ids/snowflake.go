package ids

import (
	"fmt"
	"sync"
	"time"
)

// Snowflake layout: 41 bits ms-timestamp (custom epoch) | 10 bits machine | 12 bits sequence.
// K-sortable: IDs generated later always compare greater, which keeps the
// links PK index append-friendly.
const (
	epochMs      = int64(1767225600000) // 2026-01-01T00:00:00Z
	machineBits  = 10
	seqBits      = 12
	maxMachineID = (1 << machineBits) - 1
	maxSeq       = (1 << seqBits) - 1
)

type Snowflake struct {
	mu        sync.Mutex
	machineID int64
	lastMs    int64
	seq       int64
}

func NewSnowflake(machineID int64) (*Snowflake, error) {
	if machineID < 0 || machineID > maxMachineID {
		return nil, fmt.Errorf("machine id %d out of range [0,%d]", machineID, maxMachineID)
	}
	return &Snowflake{machineID: machineID}, nil
}

func (s *Snowflake) Next() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UnixMilli()
	// Clock moved backwards (NTP step): refuse to go back in time, spin until
	// the wall clock catches up with the last issued timestamp.
	for now < s.lastMs {
		time.Sleep(time.Duration(s.lastMs-now) * time.Millisecond)
		now = time.Now().UnixMilli()
	}
	if now == s.lastMs {
		s.seq = (s.seq + 1) & maxSeq
		if s.seq == 0 { // sequence exhausted within this ms: wait for next tick
			for now <= s.lastMs {
				now = time.Now().UnixMilli()
			}
		}
	} else {
		s.seq = 0
	}
	s.lastMs = now
	return (now-epochMs)<<(machineBits+seqBits) | s.machineID<<seqBits | s.seq
}

const base62Alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

// Base62 encodes a positive int64 into the shortest base62 string.
func Base62(n int64) string {
	if n == 0 {
		return "0"
	}
	buf := make([]byte, 0, 11)
	for n > 0 {
		buf = append(buf, base62Alphabet[n%62])
		n /= 62
	}
	for i, j := 0, len(buf)-1; i < j; i, j = i+1, j-1 {
		buf[i], buf[j] = buf[j], buf[i]
	}
	return string(buf)
}
