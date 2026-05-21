package com.debugbundle.sdk;

final class ManualClock {
    private long nowMillis = 1_700_000_000_000L;

    long nowMillis() {
        return nowMillis;
    }

    void setNowMillis(long nowMillis) {
        this.nowMillis = nowMillis;
    }

    void advanceMillis(long millis) {
        nowMillis += millis;
    }
}
