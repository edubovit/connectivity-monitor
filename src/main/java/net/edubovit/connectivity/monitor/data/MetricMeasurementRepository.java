package net.edubovit.connectivity.monitor.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import net.edubovit.connectivity.monitor.service.MetricMeasurement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricMeasurementRepository {

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public MetricMeasurementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public void save(String metricName, Instant measuredAt, MetricMeasurement measurement) {
        long metricId = resolveMetricId(metricName);
        jdbcTemplate.update("""
                        INSERT INTO metric_measurements (
                            metric_id,
                            measured_at_epoch_ms,
                            metric_value,
                            successful,
                            duration_ms,
                            details,
                            error_message
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                metricId,
                measuredAt.toEpochMilli(),
                measurement.value(),
                measurement.successful(),
                measurement.duration().toMillis(),
                measurement.details(),
                measurement.errorMessage());
    }

    private synchronized long resolveMetricId(String metricName) {
        Long existingId = jdbcTemplate.query("""
                        SELECT id
                        FROM metric_definitions
                        WHERE metric_name = ?
                        """,
                rs -> rs.next() ? rs.getLong("id") : null,
                metricName);
        if (existingId != null) {
            return existingId;
        }

        jdbcTemplate.update("""
                        INSERT INTO metric_definitions (metric_name)
                        VALUES (?)
                        """,
                metricName);
        return jdbcTemplate.queryForObject("""
                        SELECT id
                        FROM metric_definitions
                        WHERE metric_name = ?
                        """,
                Long.class,
                metricName);
    }

    public List<PersistedMetricMeasurement> findBetween(Instant from, Instant to) {
        return jdbcTemplate.query("""
                        SELECT m.id,
                               d.metric_name,
                               m.measured_at_epoch_ms,
                               m.metric_value,
                               m.successful,
                               m.duration_ms,
                               m.details,
                               m.error_message
                        FROM metric_measurements m
                        JOIN metric_definitions d ON d.id = m.metric_id
                        WHERE m.measured_at_epoch_ms >= ? AND m.measured_at_epoch_ms <= ?
                        ORDER BY m.measured_at_epoch_ms ASC, m.id ASC
                        """,
                this::mapMeasurement,
                from.toEpochMilli(),
                to.toEpochMilli());
    }

    public List<PersistedMetricMeasurement> findBetween(Instant from, Instant to, List<String> metricNames) {
        List<String> filteredMetricNames = metricNames == null
                ? List.of()
                : metricNames.stream()
                        .filter(metricName -> metricName != null && !metricName.isBlank())
                        .distinct()
                        .toList();
        if (filteredMetricNames.isEmpty()) {
            return List.of();
        }

        return namedParameterJdbcTemplate.query("""
                        SELECT m.id,
                               d.metric_name,
                               m.measured_at_epoch_ms,
                               m.metric_value,
                               m.successful,
                               m.duration_ms,
                               m.details,
                               m.error_message
                        FROM metric_measurements m
                        JOIN metric_definitions d ON d.id = m.metric_id
                        WHERE m.measured_at_epoch_ms >= :from
                          AND m.measured_at_epoch_ms <= :to
                          AND d.metric_name IN (:metricNames)
                        ORDER BY m.measured_at_epoch_ms ASC, m.id ASC
                        """,
                new MapSqlParameterSource()
                        .addValue("from", from.toEpochMilli())
                        .addValue("to", to.toEpochMilli())
                        .addValue("metricNames", filteredMetricNames),
                this::mapMeasurement);
    }

    private PersistedMetricMeasurement mapMeasurement(ResultSet rs, int rowNum) throws SQLException {
        return new PersistedMetricMeasurement(
                rs.getLong("id"),
                rs.getString("metric_name"),
                Instant.ofEpochMilli(rs.getLong("measured_at_epoch_ms")),
                rs.getObject("metric_value", Double.class),
                rs.getBoolean("successful"),
                rs.getLong("duration_ms"),
                rs.getString("details"),
                rs.getString("error_message"));
    }
}
