package libcore

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

// Windows host ResetNetwork is not an Android Wi-Fi/mobile handover test.
func TestProfileTrafficNetworkResetKeepsCounterEpoch(t *testing.T) {
	b, stats := profileTestBox(t)
	out, _ := b.Outbound().Outbound("a")
	flow := stats.RoutedFlow(context.Background(), adapter.InboundContext{}, nil, out)
	flow.CountReverse(1024)
	b.resetNetwork()
	if got := profileValues(t, stats)["a"].Rx; got != 1024 {
		t.Fatalf("network reset discarded history: %d", got)
	}
	flow.CountReverse(2048)
	if got := profileValues(t, stats)["a"].Rx; got != 3072 {
		t.Fatalf("network reset changed counter epoch: %d", got)
	}
}
