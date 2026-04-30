package net.edubovit.connectivity.monitor.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Metric;
import net.edubovit.connectivity.monitor.data.MetricMeasurementRepository;
import net.edubovit.connectivity.monitor.data.PersistedMetricMeasurement;
import org.springframework.stereotype.Service;

@Service
public class MetricHistoryService {

    private final ConnectivityMonitorProperties properties;

    private final MetricMeasurementRepository repository;

    public MetricHistoryService(ConnectivityMonitorProperties properties, MetricMeasurementRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public List<MetricSeriesView> metrics(Instant from, Instant to) {
        List<MetricView> configuredMetrics = configuredMetrics();
        if (configuredMetrics.isEmpty()) {
            return List.of();
        }

        List<PersistedMetricMeasurement> measurements = repository.findBetween(
                from,
                to,
                configuredMetrics.stream().map(MetricView::name).toList());
        Map<String, List<PersistedMetricMeasurement>> measurementsByMetric = measurements.stream()
                .collect(Collectors.groupingBy(PersistedMetricMeasurement::metricName, LinkedHashMap::new, Collectors.toList()));

        List<MetricSeriesView> views = new ArrayList<>();
        for (MetricView metric : configuredMetrics) {
            views.add(new MetricSeriesView(metric.name(), metric.unit(), measurementsByMetric.getOrDefault(metric.name(), List.of())));
        }
        return views;
    }

    private List<MetricView> configuredMetrics() {
        List<Metric> metrics = properties.getMetrics();
        if (metrics == null) {
            return List.of();
        }

        return metrics.stream()
                .filter(metric -> metric.getName() != null && !metric.getName().isBlank())
                .map(metric -> new MetricView(metric.getName(), metric.getUnit()))
                .collect(Collectors.toMap(MetricView::name, Function.identity(), (left, right) -> left, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    public record MetricSeriesView(String name, String unit, List<PersistedMetricMeasurement> samples) {
    }

    public record MetricView(String name, String unit) {
    }
}
