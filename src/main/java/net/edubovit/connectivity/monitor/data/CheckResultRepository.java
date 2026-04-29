package net.edubovit.connectivity.monitor.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import net.edubovit.connectivity.monitor.service.CheckResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CheckResultRepository {

    private final JdbcTemplate jdbcTemplate;

    public CheckResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
}
