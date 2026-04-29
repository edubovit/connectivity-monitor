package net.edubovit.connectivity.monitor.service;

import java.time.Duration;

public record MetricMeasurement(
        Double value,
        boolean successful,
        Duration duration,
        String details,
        String errorMessage
) {
}
