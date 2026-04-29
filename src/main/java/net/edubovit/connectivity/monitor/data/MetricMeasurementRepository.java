package net.edubovit.connectivity.monitor.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import net.edubovit.connectivity.monitor.service.MetricMeasurement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricMeasurementRepository {

    private final JdbcTemplate jdbcTemplate;

    public MetricMeasurementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String metricName, Instant measuredAt, MetricMeasurement measurement) {
        jdbcTemplate.update("""
                        INSERT INTO metric_measurements (
                            metric_name,
                            measured_at_epoch_ms,
                            metric_value,
                            successful,
                            duration_ms,
                            details,
                            error_message
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                metricName,
                measuredAt.toEpochMilli(),
                measurement.value(),
                measurement.successful(),
                measurement.duration().toMillis(),
                measurement.details(),
                measurement.errorMessage());
    }

    public List<PersistedMetricMeasurement> findBetween(Instant from, Instant to) {
        return jdbcTemplate.query("""
                        SELECT id,
                               metric_name,
                               measured_at_epoch_ms,
                               metric_value,
                               successful,
                               duration_ms,
                               details,
                               error_message
                        FROM metric_measurements
                        WHERE measured_at_epoch_ms >= ? AND measured_at_epoch_ms <= ?
                        ORDER BY measured_at_epoch_ms ASC, id ASC
                        """,
                this::mapMeasurement,
                from.toEpochMilli(),
                to.toEpochMilli());
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
