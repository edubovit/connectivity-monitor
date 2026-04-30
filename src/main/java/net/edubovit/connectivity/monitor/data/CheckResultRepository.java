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
        long checkId = resolveCheckId(resourceName, checkName, checkType);
        jdbcTemplate.update("""
                        INSERT INTO check_results (
                            check_id,
                            checked_at_epoch_ms,
                            successful,
                            duration_ms,
                            details,
                            error_message
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                checkId,
                checkedAt.toEpochMilli(),
                result.successful(),
                result.duration().toMillis(),
                result.details(),
                result.errorMessage());
    }

    private synchronized long resolveCheckId(String resourceName, String checkName, String checkType) {
        Long existingId = jdbcTemplate.query("""
                        SELECT id
                        FROM check_definitions
                        WHERE resource_name = ? AND check_name = ?
                        """,
                rs -> rs.next() ? rs.getLong("id") : null,
                resourceName,
                checkName);
        if (existingId != null) {
            jdbcTemplate.update("""
                            UPDATE check_definitions
                            SET check_type = ?
                            WHERE id = ? AND check_type <> ?
                            """,
                    checkType,
                    existingId,
                    checkType);
            return existingId;
        }

        jdbcTemplate.update("""
                        INSERT INTO check_definitions (resource_name, check_name, check_type)
                        VALUES (?, ?, ?)
                        """,
                resourceName,
                checkName,
                checkType);
        return jdbcTemplate.queryForObject("""
                        SELECT id
                        FROM check_definitions
                        WHERE resource_name = ? AND check_name = ?
                        """,
                Long.class,
                resourceName,
                checkName);
    }

    public List<PersistedCheckResult> findBetween(Instant from, Instant to) {
        return jdbcTemplate.query("""
                        SELECT r.id,
                               NULL AS run_id,
                               d.resource_name,
                               d.check_name,
                               d.check_type,
                               r.checked_at_epoch_ms,
                               r.successful,
                               r.duration_ms,
                               r.details,
                               r.error_message
                        FROM check_results r
                        JOIN check_definitions d ON d.id = r.check_id
                        WHERE r.checked_at_epoch_ms >= ? AND r.checked_at_epoch_ms <= ?
                        ORDER BY r.checked_at_epoch_ms ASC, r.id ASC
                        """,
                this::mapResult,
                from.toEpochMilli(),
                to.toEpochMilli());
    }

    public List<PersistedCheckResult> findUntil(Instant to) {
        return jdbcTemplate.query("""
                        SELECT r.id,
                               NULL AS run_id,
                               d.resource_name,
                               d.check_name,
                               d.check_type,
                               r.checked_at_epoch_ms,
                               r.successful,
                               r.duration_ms,
                               r.details,
                               r.error_message
                        FROM check_results r
                        JOIN check_definitions d ON d.id = r.check_id
                        WHERE r.checked_at_epoch_ms <= ?
                        ORDER BY r.checked_at_epoch_ms ASC, r.id ASC
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
                        SELECT r.id,
                               NULL AS run_id,
                               d.resource_name,
                               d.check_name,
                               d.check_type,
                               r.checked_at_epoch_ms,
                               r.successful,
                               r.duration_ms,
                               CASE WHEN r.successful THEN NULL ELSE r.details END AS details,
                               CASE WHEN r.successful THEN NULL ELSE r.error_message END AS error_message
                        FROM check_results r
                        JOIN check_definitions d ON d.id = r.check_id
                        WHERE r.checked_at_epoch_ms >= :from
                          AND r.checked_at_epoch_ms <= :to
                          AND %1$s
                        ORDER BY r.checked_at_epoch_ms ASC, r.id ASC
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
                        SELECT r.id,
                               NULL AS run_id,
                               d.resource_name,
                               d.check_name,
                               d.check_type,
                               r.checked_at_epoch_ms,
                               r.successful,
                               r.duration_ms,
                               NULL AS details,
                               NULL AS error_message
                        FROM check_results r
                        JOIN check_definitions d ON d.id = r.check_id
                        WHERE r.checked_at_epoch_ms >= :from
                          AND r.checked_at_epoch_ms <= :to
                          AND %1$s
                        ORDER BY r.checked_at_epoch_ms ASC, r.id ASC
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
        String detailsColumn = includeFailureDetails ? "CASE WHEN r.successful THEN NULL ELSE r.details END" : "NULL";
        String errorMessageColumn = includeFailureDetails ? "CASE WHEN r.successful THEN NULL ELSE r.error_message END" : "NULL";
        return jdbcTemplate.query("""
                        SELECT r.id,
                               NULL AS run_id,
                               d.resource_name,
                               d.check_name,
                               d.check_type,
                               r.checked_at_epoch_ms,
                               r.successful,
                               r.duration_ms,
                               %s AS details,
                               %s AS error_message
                        FROM check_results r
                        JOIN check_definitions d ON d.id = r.check_id
                        WHERE d.resource_name = ?
                          AND d.check_name = ?
                          AND r.checked_at_epoch_ms < ?
                        ORDER BY r.checked_at_epoch_ms DESC, r.id DESC
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
                        SELECT MIN(r.checked_at_epoch_ms)
                        FROM check_results r
                        JOIN check_definitions d ON d.id = r.check_id
                        WHERE d.resource_name = ?
                          AND d.check_name = ?
                          AND r.successful = FALSE
                          AND r.checked_at_epoch_ms > COALESCE((
                              SELECT MAX(previous.checked_at_epoch_ms)
                              FROM check_results previous
                              JOIN check_definitions previous_definition ON previous_definition.id = previous.check_id
                              WHERE previous_definition.resource_name = ?
                                AND previous_definition.check_name = ?
                                AND previous.successful = TRUE
                                AND previous.checked_at_epoch_ms < ?
                          ), ?)
                          AND r.checked_at_epoch_ms <= ?
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
            predicates.add("(d.resource_name = :" + resourceParameter + " AND d.check_name IN (:" + checksParameter + "))");
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
