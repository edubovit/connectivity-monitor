package net.edubovit.connectivity.monitor.data;

import java.time.Instant;

public record PersistedMetricMeasurement(
        long id,
        String metricName,
        Instant measuredAt,
        Double value,
        boolean successful,
        long durationMs,
        String details,
        String errorMessage
) {
}
