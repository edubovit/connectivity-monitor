package net.edubovit.connectivity.monitor.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        List<PersistedCheckResult> results = results(from, to);
        Map<String, List<PersistedCheckResult>> resultsByResource = results.stream()
                .collect(Collectors.groupingBy(PersistedCheckResult::resourceName, LinkedHashMap::new, Collectors.toList()));

        Map<String, ResourceView> configuredResources = resources().stream()
                .collect(Collectors.toMap(ResourceView::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<AvailabilityView> availability = new ArrayList<>();
        for (ResourceView resource : configuredResources.values()) {
            availability.add(toAvailability(resource, resultsByResource.getOrDefault(resource.name(), List.of())));
        }

        for (Map.Entry<String, List<PersistedCheckResult>> entry : resultsByResource.entrySet()) {
            if (!configuredResources.containsKey(entry.getKey())) {
                PersistedCheckResult first = entry.getValue().getFirst();
                availability.add(toAvailability(new ResourceView(first.resourceName(), List.of()),
                        entry.getValue()));
            }
        }

        return availability;
    }

    private AvailabilityView toAvailability(ResourceView resource, List<PersistedCheckResult> results) {
        List<ResourceStatusSample> timeline = resourceTimeline(resource, results);
        long totalSamples = timeline.size();
        long onlineSamples = timeline.stream().filter(ResourceStatusSample::online).count();
        Double percentage = totalSamples == 0 ? null : onlineSamples * 100.0 / totalSamples;
        Boolean online = timeline.isEmpty() ? null : timeline.getLast().online();

        Map<String, List<PersistedCheckResult>> resultsByCheck = results.stream()
                .collect(Collectors.groupingBy(PersistedCheckResult::checkName, LinkedHashMap::new, Collectors.toList()));
        Map<String, CheckView> configuredChecks = resource.checks().stream()
                .collect(Collectors.toMap(CheckView::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<CheckAvailabilityView> checks = new ArrayList<>();
        for (CheckView check : configuredChecks.values()) {
            checks.add(toCheckAvailability(check, resultsByCheck.getOrDefault(check.name(), List.of())));
        }
        for (Map.Entry<String, List<PersistedCheckResult>> entry : resultsByCheck.entrySet()) {
            if (!configuredChecks.containsKey(entry.getKey())) {
                PersistedCheckResult first = entry.getValue().getFirst();
                checks.add(toCheckAvailability(new CheckView(first.checkName(), first.checkType(), first.checkName()), entry.getValue()));
            }
        }

        return new AvailabilityView(resource.name(), totalSamples, onlineSamples, percentage, online, timeline, checks);
    }

    private List<ResourceStatusSample> resourceTimeline(ResourceView resource, List<PersistedCheckResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }

        Map<Long, FailurePeriod> failurePeriods = failurePeriodsByResult(results);
        Set<String> configuredCheckNames = resource.checks().stream()
                .map(CheckView::name)
                .collect(Collectors.toSet());
        Map<String, List<PersistedCheckResult>> resultsByRun = results.stream()
                .collect(Collectors.groupingBy(this::runKey, LinkedHashMap::new, Collectors.toList()));

        return resultsByRun.values().stream()
                .map(runResults -> toResourceStatusSample(resource, configuredCheckNames, failurePeriods, runResults))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(ResourceStatusSample::checkedAt))
                .toList();
    }

    private Map<Long, FailurePeriod> failurePeriodsByResult(List<PersistedCheckResult> results) {
        Map<Long, FailurePeriod> periods = new LinkedHashMap<>();
        Map<String, List<PersistedCheckResult>> resultsByCheck = results.stream()
                .collect(Collectors.groupingBy(PersistedCheckResult::checkName, LinkedHashMap::new, Collectors.toList()));

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

    private Optional<ResourceStatusSample> toResourceStatusSample(
            ResourceView resource,
            Set<String> configuredCheckNames,
            Map<Long, FailurePeriod> failurePeriods,
            List<PersistedCheckResult> runResults) {
        Map<String, List<PersistedCheckResult>> resultsByCheck = runResults.stream()
                .collect(Collectors.groupingBy(PersistedCheckResult::checkName, LinkedHashMap::new, Collectors.toList()));

        if (!configuredCheckNames.isEmpty() && !resultsByCheck.keySet().containsAll(configuredCheckNames)) {
            return Optional.empty();
        }

        boolean everyConfiguredCheckPassed = configuredCheckNames.isEmpty()
                ? runResults.stream().allMatch(PersistedCheckResult::successful)
                : configuredCheckNames.stream()
                        .allMatch(checkName -> resultsByCheck.getOrDefault(checkName, List.of()).stream()
                                .allMatch(PersistedCheckResult::successful));
        boolean noCheckFailed = runResults.stream().allMatch(PersistedCheckResult::successful);
        boolean online = everyConfiguredCheckPassed && noCheckFailed;
        Instant checkedAt = runResults.stream()
                .map(PersistedCheckResult::checkedAt)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        String runId = runResults.stream()
                .map(PersistedCheckResult::runId)
                .filter(run -> run != null && !run.isBlank())
                .findFirst()
                .orElse("legacy-" + runResults.getFirst().id());

        List<FailedCheckView> failedChecks = online ? List.of() : failedChecks(resource, failurePeriods, runResults);
        return Optional.of(new ResourceStatusSample(runId, checkedAt, online, failedChecks));
    }

    private List<FailedCheckView> failedChecks(
            ResourceView resource,
            Map<Long, FailurePeriod> failurePeriods,
            List<PersistedCheckResult> runResults) {
        Map<String, CheckView> checksByName = resource.checks().stream()
                .collect(Collectors.toMap(CheckView::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        return runResults.stream()
                .filter(result -> !result.successful())
                .map(result -> {
                    CheckView check = checksByName.get(result.checkName());
                    String target = check == null ? result.checkName() : check.target();
                    FailurePeriod period = failurePeriods.getOrDefault(result.id(),
                            new FailurePeriod(result.checkedAt(), result.checkedAt(), 0L));
                    return new FailedCheckView(
                            result.checkName(),
                            result.checkType(),
                            target,
                            result.checkedAt(),
                            result.durationMs(),
                            period.startedAt(),
                            period.endedAt(),
                            period.durationMs(),
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

    private CheckAvailabilityView toCheckAvailability(CheckView check, List<PersistedCheckResult> results) {
        long total = results.size();
        long successful = results.stream().filter(PersistedCheckResult::successful).count();
        Double percentage = total == 0 ? null : successful * 100.0 / total;
        Boolean online = results.isEmpty() ? null : results.getLast().successful();
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
