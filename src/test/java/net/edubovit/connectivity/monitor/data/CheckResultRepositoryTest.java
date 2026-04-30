package net.edubovit.connectivity.monitor.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.edubovit.connectivity.monitor.config.CheckType;
import net.edubovit.connectivity.monitor.service.CheckResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CheckResultRepositoryTest {

    @Test
    void savesAndFindsResultsByTimeRange() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema(jdbcTemplate);
        CheckResultRepository repository = new CheckResultRepository(jdbcTemplate);
        Instant checkedAt = Instant.parse("2026-04-28T10:00:00Z");

        repository.save("run-1", "example-resource", "homepage-get", CheckType.HTTP_GET.name(), checkedAt,
                new CheckResult(true, Duration.ofMillis(123), "status=200"));

        assertThat(repository.findBetween(
                Instant.parse("2026-04-28T09:59:00Z"),
                Instant.parse("2026-04-28T10:01:00Z")))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.runId()).isEqualTo("run-1");
                    assertThat(result.resourceName()).isEqualTo("example-resource");
                    assertThat(result.checkName()).isEqualTo("homepage-get");
                    assertThat(result.checkType()).isEqualTo("HTTP_GET");
                    assertThat(result.checkedAt()).isEqualTo(checkedAt);
                    assertThat(result.successful()).isTrue();
                    assertThat(result.durationMs()).isEqualTo(123);
                    assertThat(result.details()).isEqualTo("status=200");
                });
    }

    @Test
    void findsAvailabilityRowsForConfiguredChecksUsingRangePlusLatestBaseline() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema(jdbcTemplate);
        CheckResultRepository repository = new CheckResultRepository(jdbcTemplate);
        Instant from = Instant.parse("2026-04-28T10:00:00Z");
        Instant to = Instant.parse("2026-04-28T10:10:00Z");

        repository.save("run-old-1", "example-resource", "homepage-get", CheckType.HTTP_GET.name(),
                Instant.parse("2026-04-28T09:50:00Z"), new CheckResult(true, Duration.ofMillis(10), "old success details"));
        repository.save("run-old-2", "example-resource", "host-ping", CheckType.REACHABILITY.name(),
                Instant.parse("2026-04-28T09:55:00Z"), new CheckResult(false, Duration.ofMillis(20), "old failure details", "old failure"));
        repository.save("run-1", "example-resource", "homepage-get", CheckType.HTTP_GET.name(),
                Instant.parse("2026-04-28T10:01:00Z"), new CheckResult(true, Duration.ofMillis(30), "success details"));
        repository.save("run-2", "example-resource", "host-ping", CheckType.REACHABILITY.name(),
                Instant.parse("2026-04-28T10:02:00Z"), new CheckResult(false, Duration.ofMillis(40), "failure details", "failure"));
        repository.save("run-stale", "example-resource", "removed-check", CheckType.REACHABILITY.name(),
                Instant.parse("2026-04-28T10:03:00Z"), new CheckResult(false, Duration.ofMillis(50), "stale", "stale"));
        repository.save("run-other", "old-resource", "homepage-get", CheckType.HTTP_GET.name(),
                Instant.parse("2026-04-28T10:04:00Z"), new CheckResult(false, Duration.ofMillis(60), "other", "other"));

        assertThat(repository.findForAvailability(from, to,
                Map.of("example-resource", List.of("homepage-get", "host-ping"))))
                .extracting(PersistedCheckResult::runId)
                .containsExactly("run-old-1", "run-old-2", "run-1", "run-2");
        assertThat(repository.findForAvailability(from, to,
                Map.of("example-resource", List.of("homepage-get", "host-ping"))))
                .filteredOn(PersistedCheckResult::successful)
                .extracting(PersistedCheckResult::details)
                .containsOnlyNulls();
        assertThat(repository.findForAvailability(from, to,
                Map.of("example-resource", List.of("homepage-get", "host-ping"))))
                .filteredOn(result -> !result.successful())
                .extracting(PersistedCheckResult::details)
                .containsExactly("old failure details", "failure details");
    }

    @Test
    void findsFailureStartAfterPreviousSuccessfulCheck() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema(jdbcTemplate);
        CheckResultRepository repository = new CheckResultRepository(jdbcTemplate);

        repository.save("run-1", "example-resource", "host-ping", CheckType.REACHABILITY.name(),
                Instant.parse("2026-04-28T09:00:00Z"), new CheckResult(true, Duration.ofMillis(10), "success"));
        repository.save("run-2", "example-resource", "host-ping", CheckType.REACHABILITY.name(),
                Instant.parse("2026-04-28T09:10:00Z"), new CheckResult(false, Duration.ofMillis(20), "failure 1", "failed"));
        repository.save("run-3", "example-resource", "host-ping", CheckType.REACHABILITY.name(),
                Instant.parse("2026-04-28T09:20:00Z"), new CheckResult(false, Duration.ofMillis(30), "failure 2", "failed"));

        PersistedCheckResult latestFailure = repository.findBetween(
                        Instant.parse("2026-04-28T09:20:00Z"),
                        Instant.parse("2026-04-28T09:20:00Z"))
                .getFirst();

        assertThat(repository.findFailureStartedAt(latestFailure))
                .contains(Instant.parse("2026-04-28T09:10:00Z"));
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE check_results (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    run_id VARCHAR(64) NOT NULL,
                    resource_name VARCHAR(255) NOT NULL,
                    check_name VARCHAR(255) NOT NULL,
                    check_type VARCHAR(64) NOT NULL,
                    checked_at_epoch_ms BIGINT NOT NULL,
                    successful BOOLEAN NOT NULL,
                    duration_ms BIGINT NOT NULL,
                    details CLOB,
                    error_message CLOB
                )
                """);
    }
}
