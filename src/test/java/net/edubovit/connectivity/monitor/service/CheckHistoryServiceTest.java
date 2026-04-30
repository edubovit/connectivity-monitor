package net.edubovit.connectivity.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.edubovit.connectivity.monitor.config.CheckType;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Check;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Resource;
import net.edubovit.connectivity.monitor.data.CheckResultRepository;
import net.edubovit.connectivity.monitor.data.PersistedCheckResult;
import org.junit.jupiter.api.Test;

class CheckHistoryServiceTest {

    @Test
    void resourceIsOfflineWhenAnyCheckInRunFails() {
        Instant from = Instant.parse("2026-04-28T10:00:00Z");
        Instant to = Instant.parse("2026-04-28T10:10:00Z");
        CheckResultRepository repository = mock(CheckResultRepository.class);
        when(repository.findForAvailability(eq(from), eq(to), any())).thenReturn(List.of(
                result(1, "run-1", "homepage-get", true, "2026-04-28T10:01:00Z"),
                result(2, "run-1", "host-ping", false, "2026-04-28T10:01:01Z"),
                result(3, "run-2", "homepage-get", true, "2026-04-28T10:02:00Z"),
                result(4, "run-2", "host-ping", true, "2026-04-28T10:02:01Z")));

        CheckHistoryService service = new CheckHistoryService(propertiesWithTwoChecks(), repository);

        assertThat(service.availability(from, to))
                .singleElement()
                .satisfies(availability -> {
                    assertThat(availability.totalSamples()).isEqualTo(3);
                    assertThat(availability.onlineSamples()).isEqualTo(1);
                    assertThat(availability.availabilityPercent()).isEqualTo(100.0 / 3.0);
                    assertThat(availability.online()).isTrue();
                    assertThat(availability.timeline()).extracting(CheckHistoryService.ResourceStatusSample::online)
                            .containsExactly(false, true);
                    assertThat(availability.timeline().getFirst().failedChecks())
                            .singleElement()
                            .satisfies(failedCheck -> {
                                assertThat(failedCheck.checkName()).isEqualTo("host-ping");
                                assertThat(failedCheck.checkType()).isEqualTo("REACHABILITY");
                                assertThat(failedCheck.target()).isEqualTo("example.test");
                                assertThat(failedCheck.durationMs()).isEqualTo(100);
                                assertThat(failedCheck.details()).isEqualTo("details");
                            });
                    assertThat(availability.timeline().get(1).failedChecks()).isEmpty();
                    assertThat(availability.checks()).extracting(CheckHistoryService.CheckAvailabilityView::latestDurationMs)
                            .containsExactly(100L, 100L);
                });
    }

    @Test
    void failedCheckDurationCoversConsecutiveFailurePeriodForThatCheck() {
        Instant from = Instant.parse("2026-04-28T12:00:00Z");
        Instant to = Instant.parse("2026-04-28T12:10:00Z");
        CheckResultRepository repository = mock(CheckResultRepository.class);
        when(repository.findForAvailability(eq(from), eq(to), any())).thenReturn(List.of(
                result(1, "run-1", "homepage-get", true, "2026-04-28T12:00:00Z"),
                result(2, "run-1", "host-ping", false, "2026-04-28T12:00:00Z"),
                result(3, "run-2", "homepage-get", true, "2026-04-28T12:01:00Z"),
                result(4, "run-2", "host-ping", false, "2026-04-28T12:01:00Z"),
                result(5, "run-3", "homepage-get", true, "2026-04-28T12:02:00Z"),
                result(6, "run-3", "host-ping", false, "2026-04-28T12:02:00Z"),
                result(7, "run-4", "homepage-get", true, "2026-04-28T12:03:00Z"),
                result(8, "run-4", "host-ping", false, "2026-04-28T12:03:00Z"),
                result(9, "run-5", "homepage-get", true, "2026-04-28T12:04:00Z"),
                result(10, "run-5", "host-ping", false, "2026-04-28T12:04:00Z"),
                result(11, "run-6", "homepage-get", true, "2026-04-28T12:05:00Z"),
                result(12, "run-6", "host-ping", true, "2026-04-28T12:05:00Z")));

        CheckHistoryService service = new CheckHistoryService(propertiesWithTwoChecks(), repository);

        CheckHistoryService.FailedCheckView failedCheck = service.availability(from, to).getFirst()
                .timeline().getFirst()
                .failedChecks().getFirst();

        assertThat(failedCheck.checkName()).isEqualTo("host-ping");
        assertThat(failedCheck.failureStartedAt()).isEqualTo(Instant.parse("2026-04-28T12:00:00Z"));
        assertThat(failedCheck.failureEndedAt()).isEqualTo(Instant.parse("2026-04-28T12:04:00Z"));
        assertThat(failedCheck.failureDurationMs()).isEqualTo(Duration.ofMinutes(4).toMillis());
    }

    @Test
    void resourceStateUsesLatestKnownCheckResultsAtEachCheckTimestamp() {
        Instant from = Instant.parse("2026-04-28T13:00:00Z");
        Instant to = Instant.parse("2026-04-28T13:10:00Z");
        CheckResultRepository repository = mock(CheckResultRepository.class);
        when(repository.findForAvailability(eq(from), eq(to), any())).thenReturn(List.of(
                result(1, "run-1", "homepage-get", true, "2026-04-28T12:59:00Z"),
                result(2, "run-2", "host-ping", true, "2026-04-28T12:59:30Z"),
                result(3, "run-3", "homepage-get", false, "2026-04-28T13:01:00Z"),
                result(4, "run-4", "host-ping", true, "2026-04-28T13:02:00Z"),
                result(5, "run-5", "homepage-get", true, "2026-04-28T13:03:00Z")));

        CheckHistoryService service = new CheckHistoryService(propertiesWithTwoChecks(), repository);

        assertThat(service.availability(from, to))
                .singleElement()
                .satisfies(availability -> {
                    assertThat(availability.timeline()).extracting(CheckHistoryService.ResourceStatusSample::online)
                            .containsExactly(false, true);
                    assertThat(availability.checks()).extracting(CheckHistoryService.CheckAvailabilityView::online)
                            .containsExactly(true, true);
                });
    }

    @Test
    void staleResultsForUnconfiguredChecksAndResourcesAreHiddenAndIgnored() {
        Instant from = Instant.parse("2026-04-28T14:00:00Z");
        Instant to = Instant.parse("2026-04-28T14:10:00Z");
        CheckResultRepository repository = mock(CheckResultRepository.class);
        when(repository.findForAvailability(eq(from), eq(to), any())).thenReturn(List.of(
                result(1, "run-1", "homepage-get", true, "2026-04-28T14:01:00Z"),
                result(2, "run-2", "host-ping", true, "2026-04-28T14:01:10Z"),
                result(3, "run-stale", "removed-check", false, "2026-04-28T14:02:00Z"),
                result(4, "old-resource", "run-old", "legacy-check", false, "2026-04-28T14:03:00Z"),
                result(5, "run-3", "homepage-get", true, "2026-04-28T14:04:00Z")));

        CheckHistoryService service = new CheckHistoryService(propertiesWithTwoChecks(), repository);

        assertThat(service.availability(from, to))
                .singleElement()
                .satisfies(availability -> {
                    assertThat(availability.resourceName()).isEqualTo("example-resource");
                    assertThat(availability.totalSamples()).isEqualTo(2);
                    assertThat(availability.onlineSamples()).isEqualTo(2);
                    assertThat(availability.timeline()).extracting(CheckHistoryService.ResourceStatusSample::online)
                            .containsExactly(true);
                    assertThat(availability.checks()).extracting(CheckHistoryService.CheckAvailabilityView::checkName)
                            .containsExactly("homepage-get", "host-ping");
                    assertThat(availability.timeline()).allSatisfy(sample -> assertThat(sample.failedChecks()).isEmpty());
                });
    }

    @Test
    void baselineFailureKeepsFailureStartFromRepository() {
        Instant from = Instant.parse("2026-04-28T15:00:00Z");
        Instant to = Instant.parse("2026-04-28T15:10:00Z");
        PersistedCheckResult baselineFailure = result(1, "run-before", "host-ping", false, "2026-04-28T14:55:00Z");
        CheckResultRepository repository = mock(CheckResultRepository.class);
        when(repository.findForAvailability(eq(from), eq(to), any())).thenReturn(List.of(
                result(2, "run-before", "homepage-get", true, "2026-04-28T14:56:00Z"),
                baselineFailure,
                result(3, "run-range", "homepage-get", true, "2026-04-28T15:01:00Z")));
        when(repository.findFailureStartedAt(baselineFailure))
                .thenReturn(Optional.of(Instant.parse("2026-04-28T14:30:00Z")));

        CheckHistoryService service = new CheckHistoryService(propertiesWithTwoChecks(), repository);

        CheckHistoryService.FailedCheckView failedCheck = service.availability(from, to).getFirst()
                .timeline().getFirst()
                .failedChecks().getFirst();

        assertThat(failedCheck.failureStartedAt()).isEqualTo(Instant.parse("2026-04-28T14:30:00Z"));
        assertThat(failedCheck.failureDurationMs()).isEqualTo(Duration.ofMinutes(31).toMillis());
    }

    @Test
    void latencyReturnsConfiguredChecksWithBaselineAndRangeSamples() {
        Instant from = Instant.parse("2026-04-28T16:00:00Z");
        Instant to = Instant.parse("2026-04-28T16:10:00Z");
        CheckResultRepository repository = mock(CheckResultRepository.class);
        when(repository.findForLatency(eq(from), eq(to), any())).thenReturn(List.of(
                result(1, "run-before", "homepage-get", true, "2026-04-28T15:59:00Z"),
                result(2, "run-1", "homepage-get", true, "2026-04-28T16:01:00Z"),
                result(3, "run-2", "homepage-get", false, "2026-04-28T16:02:00Z"),
                result(4, "run-3", "host-ping", true, "2026-04-28T16:03:00Z"),
                result(5, "run-stale", "removed-check", true, "2026-04-28T16:04:00Z")));

        CheckHistoryService service = new CheckHistoryService(propertiesWithTwoChecks(), repository);

        assertThat(service.latency(from, to, "example-resource"))
                .singleElement()
                .satisfies(resource -> {
                    assertThat(resource.resourceName()).isEqualTo("example-resource");
                    assertThat(resource.checks()).extracting(CheckHistoryService.CheckLatencyView::checkName)
                            .containsExactly("homepage-get", "host-ping");
                    CheckHistoryService.CheckLatencyView homepage = resource.checks().getFirst();
                    assertThat(homepage.latestSuccessful()).isFalse();
                    assertThat(homepage.latestDurationMs()).isEqualTo(100L);
                    assertThat(homepage.samples()).extracting(CheckHistoryService.CheckLatencySample::successful)
                            .containsExactly(true, true, false);
                });
    }

    private PersistedCheckResult result(long id, String runId, String checkName, boolean successful, String checkedAt) {
        return result(id, "example-resource", runId, checkName, successful, checkedAt);
    }

    private PersistedCheckResult result(
            long id,
            String resourceName,
            String runId,
            String checkName,
            boolean successful,
            String checkedAt) {
        return new PersistedCheckResult(
                id,
                runId,
                resourceName,
                checkName,
                checkName.equals("homepage-get") ? "HTTP_GET" : "REACHABILITY",
                Instant.parse(checkedAt),
                successful,
                100,
                "details",
                null);
    }

    private ConnectivityMonitorProperties propertiesWithTwoChecks() {
        Check homepage = new Check();
        homepage.setName("homepage-get");
        homepage.setType(CheckType.HTTP_GET);

        Check ping = new Check();
        ping.setName("host-ping");
        ping.setType(CheckType.REACHABILITY);
        ping.setHost("example.test");

        Resource resource = new Resource();
        resource.setName("example-resource");
        resource.setChecks(new ArrayList<>(List.of(homepage, ping)));

        ConnectivityMonitorProperties properties = new ConnectivityMonitorProperties();
        properties.setResources(new ArrayList<>(List.of(resource)));
        return properties;
    }
}
