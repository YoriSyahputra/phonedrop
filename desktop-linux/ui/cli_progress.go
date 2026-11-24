// Package ui implements PhoneDrop v2's native GTK3 + Ayatana AppIndicator
// desktop interface and ANSI terminal progress renderer.
package ui

import (
	"fmt"
	"io"
	"math"
	"strings"
	"sync"

	"phonedrop/v2/desktop/core/transfer"
)

// CLIProgressPrinter renders throttled single-line ANSI transfer progress bars.
type CLIProgressPrinter struct {
	mu  sync.Mutex
	out io.Writer
}

// NewCLIProgressPrinter constructs a terminal progress bar writer.
func NewCLIProgressPrinter(out io.Writer) *CLIProgressPrinter {
	return &CLIProgressPrinter{out: out}
}

// Print renders rep cleanly on one terminal line (terminating with newline on completion/error).
func (p *CLIProgressPrinter) Print(rep transfer.ProgressReport) {
	if p == nil || p.out == nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()

	const barWidth = 24
	filled := int(math.Round((rep.Percent / 100.0) * barWidth))
	if filled < 0 {
		filled = 0
	}
	if filled > barWidth {
		filled = barWidth
	}
	bar := strings.Repeat("█", filled) + strings.Repeat("░", barWidth-filled)

	dir := "RX"
	if rep.Direction == transfer.DirectionSend {
		dir = "TX"
	}

	if rep.Err != nil {
		fmt.Fprintf(p.out, "\r[%s] %s FAILED: %v\n", dir, rep.FileName, rep.Err)
		return
	}
	if rep.Verified {
		shortSHA := rep.SHA256Hex
		if len(shortSHA) > 16 {
			shortSHA = shortSHA[:16]
		}
		fmt.Fprintf(p.out, "\r[%s] %s [%s] 100.0%% | %.2f MB/s | SHA-256: %s ✓\n",
			dir, rep.FileName, bar, rep.SpeedMBps, shortSHA)
		return
	}
	fmt.Fprintf(p.out, "\r[%s] %s [%s] %5.1f%% | %5.2f MB/s (%d/%d chunks)",
		dir, rep.FileName, bar, rep.Percent, rep.SpeedMBps, rep.ChunkIndex, rep.TotalChunks)
}
