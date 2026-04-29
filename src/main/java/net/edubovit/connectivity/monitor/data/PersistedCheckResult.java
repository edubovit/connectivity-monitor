package net.edubovit.connectivity.monitor.data;

import java.time.Instant;

public record PersistedCheckResult(
        long id,
        String runId,
        String resourceName,
        String checkName,
        String checkType,
        Instant checkedAt,
        boolean successful,
        long durationMs,
        String details,
        String errorMessage
) {
}
