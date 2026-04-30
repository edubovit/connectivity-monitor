package net.edubovit.connectivity.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Metric;
import net.edubovit.connectivity.monitor.data.MetricMeasurementRepository;
import net.edubovit.connectivity.monitor.data.PersistedMetricMeasurement;
import org.junit.jupiter.api.Test;

class MetricHistoryServiceTest {

    @Test
    void returnsOnlyConfiguredMetricsInConfiguredOrder() {
        Instant from = Instant.parse("2026-04-30T10:00:00Z");
        Instant to = Instant.parse("2026-04-30T11:00:00Z");
        MetricMeasurementRepository repository = mock(MetricMeasurementRepository.class);
        when(repository.findBetween(eq(from), eq(to), any())).thenReturn(List.of(
                measurement(1, "alpha", 10.0),
                measurement(2, "stale", 99.0)));
        MetricHistoryService service = new MetricHistoryService(properties(metric("alpha", "Connections"), metric("beta", "ms")), repository);

        assertThat(service.metrics(from, to))
                .satisfiesExactly(
                        series -> {
                            assertThat(series.name()).isEqualTo("alpha");
                            assertThat(series.unit()).isEqualTo("Connections");
                            assertThat(series.samples()).extracting(PersistedMetricMeasurement::metricName)
                                    .containsExactly("alpha");
                        },
                        series -> {
                            assertThat(series.name()).isEqualTo("beta");
                            assertThat(series.unit()).isEqualTo("ms");
                            assertThat(series.samples()).isEmpty();
                        });
        verify(repository).findBetween(from, to, List.of("alpha", "beta"));
    }

    @Test
    void returnsNoMetricsWhenNoneAreConfigured() {
        ConnectivityMonitorProperties properties = new ConnectivityMonitorProperties();
        properties.setMetrics(List.of());
        MetricMeasurementRepository repository = mock(MetricMeasurementRepository.class);

        assertThat(new MetricHistoryService(properties, repository).metrics(Instant.EPOCH, Instant.EPOCH.plusSeconds(60)))
                .isEmpty();
        verifyNoInteractions(repository);
    }

    private ConnectivityMonitorProperties properties(Metric... metrics) {
        ConnectivityMonitorProperties properties = new ConnectivityMonitorProperties();
        properties.setMetrics(List.of(metrics));
        return properties;
    }

    private Metric metric(String name, String unit) {
        Metric metric = new Metric();
        metric.setName(name);
        metric.setUnit(unit);
        return metric;
    }

    private PersistedMetricMeasurement measurement(long id, String metricName, double value) {
        return new PersistedMetricMeasurement(
                id,
                metricName,
                Instant.parse("2026-04-30T10:30:00Z"),
                value,
                true,
                10,
                "details",
                null);
    }
}
