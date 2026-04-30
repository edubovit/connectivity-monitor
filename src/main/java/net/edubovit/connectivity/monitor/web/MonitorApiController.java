package net.edubovit.connectivity.monitor.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import net.edubovit.connectivity.monitor.data.PersistedCheckResult;
import net.edubovit.connectivity.monitor.service.CheckHistoryService;
import net.edubovit.connectivity.monitor.service.CheckHistoryService.AvailabilityView;
import net.edubovit.connectivity.monitor.service.CheckHistoryService.ResourceLatencyView;
import net.edubovit.connectivity.monitor.service.CheckHistoryService.ResourceView;
import net.edubovit.connectivity.monitor.service.MetricHistoryService;
import net.edubovit.connectivity.monitor.service.MetricHistoryService.MetricSeriesView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MonitorApiController {

    private final CheckHistoryService checkHistoryService;

    private final MetricHistoryService metricHistoryService;

    public MonitorApiController(CheckHistoryService checkHistoryService, MetricHistoryService metricHistoryService) {
        this.checkHistoryService = checkHistoryService;
        this.metricHistoryService = metricHistoryService;
    }

    @GetMapping("/resources")
    public List<ResourceView> resources() {
        return checkHistoryService.resources();
    }

    @GetMapping("/results")
    public List<PersistedCheckResult> results(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TimeRange timeRange = resolveTimeRange(from, to);
        return checkHistoryService.results(timeRange.from(), timeRange.to());
    }

    @GetMapping("/availability")
    public List<AvailabilityView> availability(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TimeRange timeRange = resolveTimeRange(from, to);
        return checkHistoryService.availability(timeRange.from(), timeRange.to());
    }

    @GetMapping("/latency")
    public List<ResourceLatencyView> latency(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String resource) {
        TimeRange timeRange = resolveTimeRange(from, to);
        return checkHistoryService.latency(timeRange.from(), timeRange.to(), resource);
    }

    @GetMapping("/metrics")
    public List<MetricSeriesView> metrics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TimeRange timeRange = resolveTimeRange(from, to);
        return metricHistoryService.metrics(timeRange.from(), timeRange.to());
    }

    private TimeRange resolveTimeRange(String from, String to) {
        Instant resolvedTo = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
        Instant resolvedFrom = from == null || from.isBlank() ? resolvedTo.minus(24, ChronoUnit.HOURS) : Instant.parse(from);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        return new TimeRange(resolvedFrom, resolvedTo);
    }

    private record TimeRange(Instant from, Instant to) {
    }
}
