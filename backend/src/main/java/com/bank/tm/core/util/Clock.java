package com.bank.tm.core.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Clock {
    private Clock() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
