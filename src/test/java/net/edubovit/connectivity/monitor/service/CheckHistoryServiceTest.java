package net.edubovit.connectivity.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
        when(repository.findBetween(from, to)).thenReturn(List.of(
                result(1, "run-1", "homepage-get", true, "2026-04-28T10:01:00Z"),
                result(2, "run-1", "host-ping", false, "2026-04-28T10:01:01Z"),
                result(3, "run-2", "homepage-get", true, "2026-04-28T10:02:00Z"),
                result(4, "run-2", "host-ping", true, "2026-04-28T10:02:01Z")));

        CheckHistoryService service = new CheckHistoryService(propertiesWithTwoChecks(), repository);

        assertThat(service.availability(from, to))
                .singleElement()
                .satisfies(availability -> {
                    assertThat(availability.totalSamples()).isEqualTo(2);
                    assertThat(availability.onlineSamples()).isEqualTo(1);
                    assertThat(availability.availabilityPercent()).isEqualTo(50.0);
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
                });
    }

    @Test
    void failedCheckDurationCoversConsecutiveFailurePeriodForThatCheck() {
        Instant from = Instant.parse("2026-04-28T12:00:00Z");
        Instant to = Instant.parse("2026-04-28T12:10:00Z");
        CheckResultRepository repository = mock(CheckResultRepository.class);
        when(repository.findBetween(from, to)).thenReturn(List.of(
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
                .timeline().get(2)
                .failedChecks().getFirst();

        assertThat(failedCheck.checkName()).isEqualTo("host-ping");
        assertThat(failedCheck.failureStartedAt()).isEqualTo(Instant.parse("2026-04-28T12:00:00Z"));
        assertThat(failedCheck.failureEndedAt()).isEqualTo(Instant.parse("2026-04-28T12:04:00Z"));
        assertThat(failedCheck.failureDurationMs()).isEqualTo(Duration.ofMinutes(4).toMillis());
    }

    private PersistedCheckResult result(long id, String runId, String checkName, boolean successful, String checkedAt) {
        return new PersistedCheckResult(
                id,
                runId,
                "example-resource",
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
