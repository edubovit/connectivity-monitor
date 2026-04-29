package net.edubovit.connectivity.monitor.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Metric;
import net.edubovit.connectivity.monitor.data.MetricMeasurementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class MetricMeasurementService {

    private static final Logger log = LoggerFactory.getLogger(MetricMeasurementService.class);

    private static final Pattern NUMERIC_VALUE = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private final ConnectivityMonitorProperties properties;

    private final MetricMeasurementRepository repository;

    private final ScheduledOperationExecutor operationExecutor;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final AtomicBoolean scheduling = new AtomicBoolean(false);

    public MetricMeasurementService(
            ConnectivityMonitorProperties properties,
            MetricMeasurementRepository repository,
            ScheduledOperationExecutor operationExecutor) {
        this.properties = properties;
        this.repository = repository;
        this.operationExecutor = operationExecutor;
    }

    @PostConstruct
    public void startScheduledMeasurements() {
        List<Metric> metrics = metrics(true);
        if (metrics.isEmpty()) {
            log.info("No numeric metrics configured");
            return;
        }

        scheduling.set(true);
        Duration initialDelay = nonNegative(properties.getInitialDelay(), Duration.ZERO, "connectivity initial-delay");
        metrics.forEach(metric -> scheduleMetric(metric, initialDelay));
        log.info("Scheduled numeric metric measurements: metrics={} initialDelay={}", metrics.size(), initialDelay);
    }

    @PreDestroy
    public void stopScheduledMeasurements() {
        scheduling.set(false);
        scheduler.shutdownNow();
    }

    public void measureAll() {
        List<Metric> metrics = metrics(false);
        if (metrics.isEmpty()) {
            log.info("No numeric metrics configured");
            return;
        }

        List<Future<?>> futures = new ArrayList<>();
        for (Metric metric : metrics) {
            futures.add(operationExecutor.submit(operationDescription(metric), () -> measure(metric)));
        }
        awaitMeasurements(futures);
    }

    private List<Metric> metrics(boolean requireIntervals) {
        List<Metric> metrics = properties.getMetrics();
        if (metrics == null) {
            return List.of();
        }
        if (requireIntervals) {
            metrics.forEach(this::validateScheduledMetric);
        }
        return metrics;
    }

    private void scheduleMetric(Metric metric, Duration delay) {
        if (!scheduling.get()) {
            return;
        }
        try {
            scheduler.schedule(() -> submitScheduledMeasurement(metric), Math.max(delay.toMillis(), 0L), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            if (scheduling.get()) {
                log.warn("Unable to schedule numeric metric: metric={}", metric.getName(), ex);
            }
        }
    }

    private void submitScheduledMeasurement(Metric metric) {
        if (!scheduling.get()) {
            return;
        }
        try {
            operationExecutor.submit(operationDescription(metric), () -> {
                try {
                    measure(metric);
                } finally {
                    scheduleMetric(metric, interval(metric));
                }
            });
        } catch (RejectedExecutionException ex) {
            if (scheduling.get()) {
                log.warn("Unable to submit numeric metric: metric={}", metric.getName(), ex);
                scheduleMetric(metric, interval(metric));
            }
        }
    }

    private void awaitMeasurements(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                futures.forEach(waitingFuture -> waitingFuture.cancel(true));
                return;
            } catch (ExecutionException ex) {
                log.warn("Numeric metric task failed unexpectedly", ex.getCause());
            }
        }
    }

    private void measure(Metric metric) {
        String metricName = metric.getName();
        Instant measuredAt = Instant.now();
        try {
            MetricMeasurement measurement = executeMetric(metric);
            repository.save(metricName, measuredAt, measurement);
            if (measurement.successful()) {
                log.info("Numeric metric measured: metric={} value={} durationMs={} details={}",
                        metricName, measurement.value(), measurement.duration().toMillis(), measurement.details());
            } else {
                log.warn("Numeric metric failed: metric={} durationMs={} error={} details={}",
                        metricName, measurement.duration().toMillis(), measurement.errorMessage(), measurement.details());
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            MetricMeasurement measurement = new MetricMeasurement(
                    null,
                    false,
                    Duration.between(measuredAt, Instant.now()),
                    "metric failed before a result could be produced",
                    ex.getMessage());
            repository.save(metricName, measuredAt, measurement);
            log.warn("Numeric metric errored: metric={} details={}", metricName, ex.getMessage(), ex);
        }
    }

    MetricMeasurement executeMetric(Metric metric) throws IOException, InterruptedException {
        require(StringUtils.hasText(metric.getCommand()), "metric command is required");

        Instant startedAt = Instant.now();
        Process process = new ProcessBuilder(shellCommand(metric.getCommand())).start();
        CapturedOutput output = captureOutput(process, timeout(metric));
        Duration duration = Duration.between(startedAt, Instant.now());

        String details = "command=%s exitCode=%d stdout=%s stderr=%s".formatted(
                metric.getCommand(), output.exitCode(), compact(output.stdout()), compact(output.stderr()));

        if (!output.finished()) {
            return new MetricMeasurement(null, false, duration, details, "metric command timed out after " + timeout(metric));
        }
        if (output.exitCode() != 0) {
            return new MetricMeasurement(null, false, duration, details, "metric command exited with code " + output.exitCode());
        }

        String trimmedOutput = output.stdout().strip();
        if (!NUMERIC_VALUE.matcher(trimmedOutput).matches()) {
            return new MetricMeasurement(null, false, duration, details, "metric stdout is not a single numeric value");
        }

        double value = Double.parseDouble(trimmedOutput);
        if (!Double.isFinite(value)) {
            return new MetricMeasurement(null, false, duration, details, "metric stdout is not a finite numeric value");
        }
        return new MetricMeasurement(value, true, duration, details, null);
    }

    private CapturedOutput captureOutput(Process process, Duration timeout) throws InterruptedException, IOException {
        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        AtomicReference<IOException> outputError = new AtomicReference<>();

        Thread stdoutReader = Thread.ofVirtual().start(() -> readOutput(process.getInputStream(), stdout, outputError));
        Thread stderrReader = Thread.ofVirtual().start(() -> readOutput(process.getErrorStream(), stderr, outputError));

        boolean finished = process.waitFor(Math.max(timeout.toMillis(), 1L), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }

        stdoutReader.join(1_000L);
        stderrReader.join(1_000L);

        if (finished && outputError.get() != null) {
            throw outputError.get();
        }

        return new CapturedOutput(
                finished,
                finished ? process.exitValue() : -1,
                stdout.get(),
                stderr.get());
    }

    private void readOutput(java.io.InputStream inputStream, AtomicReference<String> output, AtomicReference<IOException> outputError) {
        try (inputStream) {
            output.set(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            outputError.compareAndSet(null, ex);
        }
    }

    private List<String> shellCommand(String command) {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("sh", "-c", command);
    }

    private String compact(String output) {
        if (!StringUtils.hasText(output)) {
            return "";
        }
        return output.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .limit(6)
                .collect(Collectors.joining(" | "));
    }

    private Duration timeout(Metric metric) {
        return metric.getTimeout() == null ? Duration.ofSeconds(10) : metric.getTimeout();
    }

    private Duration interval(Metric metric) {
        return positive(metric.getInterval(), "metric interval");
    }

    private void validateScheduledMetric(Metric metric) {
        require(StringUtils.hasText(metric.getName()), "metric name is required");
        require(StringUtils.hasText(metric.getCommand()), "metric command is required");
        positive(metric.getInterval(), "metric interval");
    }

    private Duration positive(Duration duration, String name) {
        require(duration != null && !duration.isZero() && !duration.isNegative(), name + " must be greater than zero");
        return duration;
    }

    private Duration nonNegative(Duration duration, Duration fallback, String name) {
        Duration resolved = duration == null ? fallback : duration;
        require(!resolved.isNegative(), name + " must not be negative");
        return resolved;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private String operationDescription(Metric metric) {
        return "numeric metric metric=%s".formatted(metric.getName());
    }

    private record CapturedOutput(boolean finished, int exitCode, String stdout, String stderr) {
    }
}
