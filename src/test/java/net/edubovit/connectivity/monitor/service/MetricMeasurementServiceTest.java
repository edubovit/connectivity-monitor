package net.edubovit.connectivity.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Metric;
import net.edubovit.connectivity.monitor.data.MetricMeasurementRepository;
import org.junit.jupiter.api.Test;

class MetricMeasurementServiceTest {

    @Test
    void metricCommandParsesTrimmedStdoutWhenItIsExactlyNumeric() throws Exception {
        Metric metric = metric("echo 42");

        MetricMeasurement measurement = service().executeMetric(metric);

        assertThat(measurement.successful()).isTrue();
        assertThat(measurement.value()).isEqualTo(42.0);
        assertThat(measurement.errorMessage()).isNull();
    }

    @Test
    void metricCommandStoresErrorWhenStdoutIsNotExactlyOneNumber() throws Exception {
        Metric metric = metric("echo 42 extra");

        MetricMeasurement measurement = service().executeMetric(metric);

        assertThat(measurement.successful()).isFalse();
        assertThat(measurement.value()).isNull();
        assertThat(measurement.errorMessage()).isEqualTo("metric stdout is not a single numeric value");
    }

    private MetricMeasurementService service() {
        ConnectivityMonitorProperties properties = new ConnectivityMonitorProperties();
        return new MetricMeasurementService(
                properties,
                mock(MetricMeasurementRepository.class),
                new ScheduledOperationExecutor(properties));
    }

    private Metric metric(String command) {
        Metric metric = new Metric();
        metric.setName("test-metric");
        metric.setCommand(command);
        metric.setInterval(Duration.ofSeconds(60));
        metric.setTimeout(Duration.ofSeconds(5));
        return metric;
    }
}
