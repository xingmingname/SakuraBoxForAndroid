package libcore

import (
	"fmt"
	"io"
)

func readProtectAck(reader io.Reader) error {
	var ack [1]byte
	if _, err := io.ReadFull(reader, ack[:]); err != nil {
		return fmt.Errorf("failed to receive protect acknowledgement: %w", err)
	}
	if ack[0] != 1 {
		return fmt.Errorf("socket protection rejected (ack=%d)", ack[0])
	}
	return nil
}
