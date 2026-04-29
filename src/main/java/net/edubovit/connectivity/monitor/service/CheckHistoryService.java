package net.edubovit.connectivity.monitor.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Check;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Resource;
import net.edubovit.connectivity.monitor.data.CheckResultRepository;
import net.edubovit.connectivity.monitor.data.PersistedCheckResult;
import org.springframework.stereotype.Service;

@Service
public class CheckHistoryService {

    private final ConnectivityMonitorProperties properties;

    private final CheckResultRepository resultRepository;

    public CheckHistoryService(ConnectivityMonitorProperties properties, CheckResultRepository resultRepository) {
        this.properties = properties;
        this.resultRepository = resultRepository;
    }

    public List<ResourceView> resources() {
        List<Resource> resources = properties.getResources();
        if (resources == null) {
            return List.of();
        }

        return resources.stream()
                .map(resource -> new ResourceView(
                        resource.getName(),
                        checks(resource)))
                .toList();
    }

    public List<PersistedCheckResult> results(Instant from, Instant to) {
        return resultRepository.findBetween(from, to);
    }

    public List<AvailabilityView> availability(Instant from, Instant to) {
        List<PersistedCheckResult> results = resultRepository.findUntil(to);
        Map<String, List<PersistedCheckResult>> resultsByResource = results.stream()
                .collect(Collectors.groupingBy(PersistedCheckResult::resourceName, LinkedHashMap::new, Collectors.toList()));

        Map<String, ResourceView> configuredResources = resources().stream()
                .collect(Collectors.toMap(ResourceView::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<AvailabilityView> availability = new ArrayList<>();
        for (ResourceView resource : configuredResources.values()) {
            availability.add(toAvailability(resource, resultsByResource.getOrDefault(resource.name(), List.of()), from));
        }

        return availability;
    }

    private AvailabilityView toAvailability(ResourceView resource, List<PersistedCheckResult> results, Instant from) {
        List<PersistedCheckResult> rangeResults = results.stream()
                .filter(result -> !result.checkedAt().isBefore(from))
                .toList();
        List<ResourceStatusSample> timeline = resourceTimeline(resource, results, from);
        long totalSamples = timeline.size();
        long onlineSamples = timeline.stream().filter(ResourceStatusSample::online).count();
        Double percentage = totalSamples == 0 ? null : onlineSamples * 100.0 / totalSamples;
        Boolean online = timeline.isEmpty() ? null : timeline.getLast().online();

        Map<String, List<PersistedCheckResult>> resultsByCheck = rangeResults.stream()
                .collect(Collectors.groupingBy(PersistedCheckResult::checkName, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<PersistedCheckResult>> allResultsByCheck = results.stream()
                .collect(Collectors.groupingBy(PersistedCheckResult::checkName, LinkedHashMap::new, Collectors.toList()));
        Map<String, CheckView> configuredChecks = resource.checks().stream()
                .collect(Collectors.toMap(CheckView::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<CheckAvailabilityView> checks = new ArrayList<>();
        for (CheckView check : configuredChecks.values()) {
            checks.add(toCheckAvailability(
                    check,
                    resultsByCheck.getOrDefault(check.name(), List.of()),
                    allResultsByCheck.getOrDefault(check.name(), List.of())));
        }

        return new AvailabilityView(resource.name(), totalSamples, onlineSamples, percentage, online, timeline, checks);
    }

    private List<ResourceStatusSample> resourceTimeline(ResourceView resource, List<PersistedCheckResult> results, Instant from) {
        if (results.isEmpty()) {
            return List.of();
        }

        Map<Long, FailurePeriod> failurePeriods = failurePeriodsByResult(results);
        Set<String> requiredCheckNames = resource.checks().stream()
                .map(CheckView::name)
                .collect(Collectors.toSet());
        if (requiredCheckNames.isEmpty()) {
            return List.of();
        }

        Map<String, PersistedCheckResult> latestResultsByCheck = new LinkedHashMap<>();
        List<ResourceStatusSample> timeline = new ArrayList<>();
        List<PersistedCheckResult> sortedResults = results.stream()
                .sorted(Comparator.comparing(PersistedCheckResult::checkedAt).thenComparing(PersistedCheckResult::id))
                .toList();
        for (PersistedCheckResult result : sortedResults) {
            if (!requiredCheckNames.contains(result.checkName())) {
                continue;
            }

            latestResultsByCheck.put(result.checkName(), result);
            if (!latestResultsByCheck.keySet().containsAll(requiredCheckNames) || result.checkedAt().isBefore(from)) {
                continue;
            }

            boolean online = requiredCheckNames.stream()
                    .allMatch(checkName -> latestResultsByCheck.get(checkName).successful());
            List<FailedCheckView> failedChecks = online
                    ? List.of()
                    : failedChecks(resource, failurePeriods, latestResultsByCheck, requiredCheckNames, result.checkedAt());
            timeline.add(new ResourceStatusSample(runKey(result), result.checkedAt(), online, failedChecks));
        }

        return timeline;
    }

    private Map<Long, FailurePeriod> failurePeriodsByResult(List<PersistedCheckResult> results) {
        Map<Long, FailurePeriod> periods = new LinkedHashMap<>();
        Map<String, List<PersistedCheckResult>> resultsByCheck = results.stream()
                .collect(Collectors.groupingBy(this::checkKey, LinkedHashMap::new, Collectors.toList()));

        for (List<PersistedCheckResult> checkResults : resultsByCheck.values()) {
            List<PersistedCheckResult> sortedResults = checkResults.stream()
                    .sorted(Comparator.comparing(PersistedCheckResult::checkedAt).thenComparing(PersistedCheckResult::id))
                    .toList();
            List<PersistedCheckResult> failedStreak = new ArrayList<>();
            for (PersistedCheckResult result : sortedResults) {
                if (result.successful()) {
                    addFailurePeriod(periods, failedStreak);
                    failedStreak.clear();
                } else {
                    failedStreak.add(result);
                }
            }
            addFailurePeriod(periods, failedStreak);
        }

        return periods;
    }

    private void addFailurePeriod(Map<Long, FailurePeriod> periods, List<PersistedCheckResult> failedStreak) {
        if (failedStreak.isEmpty()) {
            return;
        }

        Instant startedAt = failedStreak.getFirst().checkedAt();
        Instant endedAt = failedStreak.getLast().checkedAt();
        long durationMs = Math.max(endedAt.toEpochMilli() - startedAt.toEpochMilli(), 0L);
        FailurePeriod period = new FailurePeriod(startedAt, endedAt, durationMs);
        for (PersistedCheckResult result : failedStreak) {
            periods.put(result.id(), period);
        }
    }

    private List<FailedCheckView> failedChecks(
            ResourceView resource,
            Map<Long, FailurePeriod> failurePeriods,
            Map<String, PersistedCheckResult> latestResultsByCheck,
            Set<String> requiredCheckNames,
            Instant sampleAt) {
        Map<String, CheckView> checksByName = resource.checks().stream()
                .collect(Collectors.toMap(CheckView::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        return requiredCheckNames.stream()
                .map(latestResultsByCheck::get)
                .filter(result -> result != null && !result.successful())
                .map(result -> {
                    CheckView check = checksByName.get(result.checkName());
                    String target = check == null ? result.checkName() : check.target();
                    FailurePeriod period = failurePeriods.getOrDefault(result.id(),
                            new FailurePeriod(result.checkedAt(), result.checkedAt(), 0L));
                    Instant failureEndedAt = period.endedAt().isBefore(sampleAt) ? sampleAt : period.endedAt();
                    long failureDurationMs = Math.max(failureEndedAt.toEpochMilli() - period.startedAt().toEpochMilli(), 0L);
                    return new FailedCheckView(
                            result.checkName(),
                            result.checkType(),
                            target,
                            result.checkedAt(),
                            result.durationMs(),
                            period.startedAt(),
                            failureEndedAt,
                            failureDurationMs,
                            result.details(),
                            result.errorMessage());
                })
                .toList();
    }

    private String runKey(PersistedCheckResult result) {
        return result.runId() == null || result.runId().isBlank()
                ? "legacy-" + result.id()
                : result.runId();
    }

    private String checkKey(PersistedCheckResult result) {
        return result.resourceName() + "\0" + result.checkName();
    }

    private List<CheckView> checks(Resource resource) {
        List<Check> checks = resource.getChecks();
        if (checks == null) {
            return List.of();
        }

        return checks.stream()
                .map(check -> new CheckView(
                        check.getName(),
                        check.getType() == null ? "UNKNOWN" : check.getType().name(),
                        checkTarget(check)))
                .toList();
    }

    private String checkTarget(Check check) {
        if (check.getUrl() != null) {
            return check.getUrl().toString();
        }
        if (check.getPort() != null && check.getHost() != null) {
            return check.getHost() + ":" + check.getPort();
        }
        return check.getHost();
    }

    private CheckAvailabilityView toCheckAvailability(
            CheckView check,
            List<PersistedCheckResult> rangeResults,
            List<PersistedCheckResult> allResults) {
        long total = rangeResults.size();
        long successful = rangeResults.stream().filter(PersistedCheckResult::successful).count();
        Double percentage = total == 0 ? null : successful * 100.0 / total;
        Boolean online = allResults.isEmpty() ? null : allResults.getLast().successful();
        return new CheckAvailabilityView(check.name(), check.type(), check.target(), total, successful, percentage, online);
    }

    public record ResourceView(String name, List<CheckView> checks) {
    }

    public record CheckView(String name, String type, String target) {
    }

    public record AvailabilityView(
            String resourceName,
            long totalSamples,
            long onlineSamples,
            Double availabilityPercent,
            Boolean online,
            List<ResourceStatusSample> timeline,
            List<CheckAvailabilityView> checks
    ) {
    }

    public record ResourceStatusSample(
            String runId,
            Instant checkedAt,
            boolean online,
            List<FailedCheckView> failedChecks
    ) {
    }

    public record FailedCheckView(
            String checkName,
            String checkType,
            String target,
            Instant checkedAt,
            long durationMs,
            Instant failureStartedAt,
            Instant failureEndedAt,
            long failureDurationMs,
            String details,
            String errorMessage
    ) {
    }

    private record FailurePeriod(Instant startedAt, Instant endedAt, long durationMs) {
    }

    public record CheckAvailabilityView(
            String checkName,
            String checkType,
            String target,
            long totalChecks,
            long successfulChecks,
            Double availabilityPercent,
            Boolean online
    ) {
    }
}
