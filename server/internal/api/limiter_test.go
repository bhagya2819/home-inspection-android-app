package api

import (
	"fmt"
	"testing"
	"time"
)

func TestLimiterPerIPAndPerAccount(t *testing.T) {
	l := newLoginLimiter()
	now := time.Unix(1_000_000, 0)
	for i := 0; i < perIPAllowance; i++ {
		l.fail("DEVA", "1.1.1.1", now)
	}
	if !l.blocked("DEVA", "1.1.1.1", false, now) {
		t.Fatal("same IP not throttled")
	}
	if l.blocked("DEVA", "2.2.2.2", false, now) {
		t.Fatal("other IP throttled too early")
	}
	// Rotating IPs: the account itself locks.
	for i := 0; i < perAccountAllowance; i++ {
		l.fail("DEVA", fmt.Sprintf("10.0.0.%d", i), now)
	}
	if !l.blocked("DEVA", "9.9.9.9", false, now) {
		t.Fatal("account not throttled across IPs")
	}
	if !l.blocked("DEVA", "9.9.9.9", false, now.Add(30*time.Second)) || l.blocked("DEVA", "9.9.9.9", false, now.Add(time.Hour)) {
		t.Fatal("lockout duration wrong")
	}
}

func TestLimiterFloodCannotEvictLockout(t *testing.T) {
	l := newLoginLimiter()
	now := time.Unix(1_000_000, 0)
	for i := 0; i < perIPAllowance; i++ {
		l.fail("DEVA", "1.1.1.1", now)
	}
	for i := 0; i < maxTrackedKeys; i++ { // attacker fills the table with junk codes
		l.fail(fmt.Sprintf("JUNK%d", i), "6.6.6.6", now)
	}
	if !l.blocked("DEVA", "1.1.1.1", false, now) {
		t.Fatal("flood cleared an active lockout")
	}
	if len(l.failures) > maxTrackedKeys {
		t.Fatalf("table grew past cap: %d", len(l.failures))
	}
}
