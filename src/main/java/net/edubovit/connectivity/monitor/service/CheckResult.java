package net.edubovit.connectivity.monitor.service;

import java.time.Duration;

public record CheckResult(boolean successful, Duration duration, String details, String errorMessage) {

    public CheckResult(boolean successful, Duration duration, String details) {
        this(successful, duration, details, null);
    }
}
