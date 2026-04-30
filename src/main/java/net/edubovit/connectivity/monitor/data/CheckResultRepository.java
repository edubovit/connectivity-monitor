package net.edubovit.connectivity.monitor.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.edubovit.connectivity.monitor.service.CheckResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CheckResultRepository {

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public CheckResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public void save(String runId, String resourceName, String checkName, String checkType, Instant checkedAt, CheckResult result) {
        jdbcTemplate.update("""
                        INSERT INTO check_results (
                            run_id,
                            resource_name,
                            check_name,
                            check_type,
                            checked_at_epoch_ms,
                            successful,
                            duration_ms,
                            details,
                            error_message
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                runId,
                resourceName,
                checkName,
                checkType,
                checkedAt.toEpochMilli(),
                result.successful(),
                result.duration().toMillis(),
                result.details(),
                result.errorMessage());
    }

    public List<PersistedCheckResult> findBetween(Instant from, Instant to) {
        return jdbcTemplate.query("""
                        SELECT id,
                               run_id,
                               resource_name,
                               check_name,
                               check_type,
                               checked_at_epoch_ms,
                               successful,
                               duration_ms,
                               details,
                               error_message
                        FROM check_results
                        WHERE checked_at_epoch_ms >= ? AND checked_at_epoch_ms <= ?
                        ORDER BY checked_at_epoch_ms ASC, id ASC
                        """,
                this::mapResult,
                from.toEpochMilli(),
                to.toEpochMilli());
    }

    public List<PersistedCheckResult> findUntil(Instant to) {
        return jdbcTemplate.query("""
                        SELECT id,
                               run_id,
                               resource_name,
                               check_name,
                               check_type,
                               checked_at_epoch_ms,
                               successful,
                               duration_ms,
                               details,
                               error_message
                        FROM check_results
                        WHERE checked_at_epoch_ms <= ?
                        ORDER BY checked_at_epoch_ms ASC, id ASC
                        """,
                this::mapResult,
                to.toEpochMilli());
    }

    public List<PersistedCheckResult> findForAvailability(
            Instant from,
            Instant to,
            Map<String, ? extends Collection<String>> checksByResource) {
        FilterSql filterSql = filterSql(checksByResource);
        if (filterSql.empty()) {
            return List.of();
        }

        MapSqlParameterSource parameters = filterSql.parameters()
                .addValue("from", from.toEpochMilli())
                .addValue("to", to.toEpochMilli());
        String configuredChecksFilter = filterSql.sql();

        List<PersistedCheckResult> results = new ArrayList<>(namedParameterJdbcTemplate.query("""
                        SELECT id,
                               run_id,
                               resource_name,
                               check_name,
                               check_type,
                               checked_at_epoch_ms,
                               successful,
                               duration_ms,
                               CASE WHEN successful THEN NULL ELSE details END AS details,
                               CASE WHEN successful THEN NULL ELSE error_message END AS error_message
                        FROM check_results
                        WHERE checked_at_epoch_ms >= :from
                          AND checked_at_epoch_ms <= :to
                          AND %1$s
                        ORDER BY checked_at_epoch_ms ASC, id ASC
                        """.formatted(configuredChecksFilter),
                parameters,
                this::mapResult));

        results.addAll(findLatestBefore(from, checksByResource));
        results.sort(Comparator.comparing(PersistedCheckResult::checkedAt).thenComparing(PersistedCheckResult::id));
        return results;
    }

    public List<PersistedCheckResult> findForLatency(
            Instant from,
            Instant to,
            Map<String, ? extends Collection<String>> checksByResource) {
        FilterSql filterSql = filterSql(checksByResource);
        if (filterSql.empty()) {
            return List.of();
        }

        MapSqlParameterSource parameters = filterSql.parameters()
                .addValue("from", from.toEpochMilli())
                .addValue("to", to.toEpochMilli());
        String configuredChecksFilter = filterSql.sql();

        List<PersistedCheckResult> results = new ArrayList<>(namedParameterJdbcTemplate.query("""
                        SELECT id,
                               run_id,
                               resource_name,
                               check_name,
                               check_type,
                               checked_at_epoch_ms,
                               successful,
                               duration_ms,
                               NULL AS details,
                               NULL AS error_message
                        FROM check_results
                        WHERE checked_at_epoch_ms >= :from
                          AND checked_at_epoch_ms <= :to
                          AND %1$s
                        ORDER BY checked_at_epoch_ms ASC, id ASC
                        """.formatted(configuredChecksFilter),
                parameters,
                this::mapResult));

        results.addAll(findLatestBefore(from, checksByResource, false));
        results.sort(Comparator.comparing(PersistedCheckResult::checkedAt).thenComparing(PersistedCheckResult::id));
        return results;
    }

    private List<PersistedCheckResult> findLatestBefore(
            Instant from,
            Map<String, ? extends Collection<String>> checksByResource) {
        return findLatestBefore(from, checksByResource, true);
    }

    private List<PersistedCheckResult> findLatestBefore(
            Instant from,
            Map<String, ? extends Collection<String>> checksByResource,
            boolean includeFailureDetails) {
        List<PersistedCheckResult> baselineResults = new ArrayList<>();
        if (checksByResource == null || checksByResource.isEmpty()) {
            return baselineResults;
        }

        for (Map.Entry<String, ? extends Collection<String>> entry : checksByResource.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            entry.getValue().stream()
                    .filter(checkName -> checkName != null && !checkName.isBlank())
                    .distinct()
                    .forEach(checkName -> baselineResults.addAll(findLatestBefore(from, entry.getKey(), checkName, includeFailureDetails)));
        }
        return baselineResults;
    }

    private List<PersistedCheckResult> findLatestBefore(
            Instant from,
            String resourceName,
            String checkName,
            boolean includeFailureDetails) {
        String detailsColumn = includeFailureDetails ? "CASE WHEN successful THEN NULL ELSE details END" : "NULL";
        String errorMessageColumn = includeFailureDetails ? "CASE WHEN successful THEN NULL ELSE error_message END" : "NULL";
        return jdbcTemplate.query("""
                        SELECT id,
                               run_id,
                               resource_name,
                               check_name,
                               check_type,
                               checked_at_epoch_ms,
                               successful,
                               duration_ms,
                               %s AS details,
                               %s AS error_message
                        FROM check_results
                        WHERE resource_name = ?
                          AND check_name = ?
                          AND checked_at_epoch_ms < ?
                        ORDER BY checked_at_epoch_ms DESC, id DESC
                        LIMIT 1
                        """.formatted(detailsColumn, errorMessageColumn),
                this::mapResult,
                resourceName,
                checkName,
                from.toEpochMilli());
    }

    public Optional<Instant> findFailureStartedAt(PersistedCheckResult failedResult) {
        if (failedResult.successful()) {
            return Optional.empty();
        }

        Long startedAtEpochMs = jdbcTemplate.query("""
                        SELECT MIN(checked_at_epoch_ms)
                        FROM check_results
                        WHERE resource_name = ?
                          AND check_name = ?
                          AND successful = FALSE
                          AND checked_at_epoch_ms > COALESCE((
                              SELECT MAX(checked_at_epoch_ms)
                              FROM check_results
                              WHERE resource_name = ?
                                AND check_name = ?
                                AND successful = TRUE
                                AND checked_at_epoch_ms < ?
                          ), ?)
                          AND checked_at_epoch_ms <= ?
                        """,
                rs -> rs.next() ? rs.getObject(1, Long.class) : null,
                failedResult.resourceName(),
                failedResult.checkName(),
                failedResult.resourceName(),
                failedResult.checkName(),
                failedResult.checkedAt().toEpochMilli(),
                Long.MIN_VALUE,
                failedResult.checkedAt().toEpochMilli());

        return startedAtEpochMs == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(startedAtEpochMs));
    }

    private FilterSql filterSql(Map<String, ? extends Collection<String>> checksByResource) {
        Map<String, ? extends Collection<String>> nonEmptyFilters = checksByResource == null
                ? Map.of()
                : checksByResource;
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, ? extends Collection<String>> entry : nonEmptyFilters.entrySet()) {
            List<String> checkNames = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream().filter(checkName -> checkName != null && !checkName.isBlank()).toList();
            if (entry.getKey() == null || entry.getKey().isBlank() || checkNames.isEmpty()) {
                continue;
            }

            String resourceParameter = "resource" + index;
            String checksParameter = "checks" + index;
            predicates.add("(resource_name = :" + resourceParameter + " AND check_name IN (:" + checksParameter + "))");
            parameters.addValue(resourceParameter, entry.getKey());
            parameters.addValue(checksParameter, checkNames);
            index++;
        }

        if (predicates.isEmpty()) {
            return new FilterSql("", parameters, true);
        }
        return new FilterSql("(" + String.join(" OR ", predicates) + ")", parameters, false);
    }

    private PersistedCheckResult mapResult(ResultSet rs, int rowNum) throws SQLException {
        return new PersistedCheckResult(
                rs.getLong("id"),
                rs.getString("run_id"),
                rs.getString("resource_name"),
                rs.getString("check_name"),
                rs.getString("check_type"),
                Instant.ofEpochMilli(rs.getLong("checked_at_epoch_ms")),
                rs.getBoolean("successful"),
                rs.getLong("duration_ms"),
                rs.getString("details"),
                rs.getString("error_message"));
    }

    private record FilterSql(String sql, MapSqlParameterSource parameters, boolean empty) {
    }
}
