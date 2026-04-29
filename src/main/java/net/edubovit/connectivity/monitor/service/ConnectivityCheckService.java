package net.edubovit.connectivity.monitor.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import net.edubovit.connectivity.monitor.config.CheckType;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Check;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Resource;
import net.edubovit.connectivity.monitor.data.CheckResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class ConnectivityCheckService {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityCheckService.class);

    private final ConnectivityMonitorProperties properties;

    private final CheckResultRepository resultRepository;

    private final ScheduledOperationExecutor operationExecutor;

    private final HttpClient httpClient;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final AtomicBoolean scheduling = new AtomicBoolean(false);

    public ConnectivityCheckService(
            ConnectivityMonitorProperties properties,
            CheckResultRepository resultRepository,
            ScheduledOperationExecutor operationExecutor) {
        this.properties = properties;
        this.resultRepository = resultRepository;
        this.operationExecutor = operationExecutor;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    public void startScheduledChecks() {
        List<CheckTask> checkTasks = checkTasks(properties.getResources(), true);
        if (checkTasks.isEmpty()) {
            log.warn("No connectivity checks configured");
            return;
        }

        scheduling.set(true);
        Duration initialDelay = nonNegative(properties.getInitialDelay(), Duration.ZERO, "connectivity initial-delay");
        checkTasks.forEach(checkTask -> scheduleCheck(checkTask, initialDelay));
        log.info("Scheduled connectivity checks: checks={} initialDelay={}", checkTasks.size(), initialDelay);
    }

    @PreDestroy
    public void stopScheduledChecks() {
        scheduling.set(false);
        scheduler.shutdownNow();
    }

    public void checkAll() {
        List<Resource> resources = properties.getResources();
        if (resources == null || resources.isEmpty()) {
            log.warn("No connectivity resources configured");
            return;
        }

        List<CheckTask> checkTasks = checkTasks(resources, false);
        if (checkTasks.isEmpty()) {
            log.warn("No connectivity checks configured");
            return;
        }

        log.info("Starting connectivity checks: resources={} checks={}", resources.size(), checkTasks.size());
        runChecks(checkTasks);
        log.info("Finished connectivity checks: resources={} checks={}", resources.size(), checkTasks.size());
    }

    private List<CheckTask> checkTasks(List<Resource> resources, boolean requireIntervals) {
        List<CheckTask> checkTasks = new ArrayList<>();
        if (resources == null) {
            return checkTasks;
        }
        for (Resource resource : resources) {
            List<Check> checks = resource.getChecks();
            if (checks == null || checks.isEmpty()) {
                log.warn("No connectivity checks configured for resource: resource={}", resource.getName());
                continue;
            }

            for (Check check : checks) {
                if (requireIntervals) {
                    validateScheduledCheck(resource, check);
                }
                checkTasks.add(new CheckTask(resource, check));
            }
        }
        return checkTasks;
    }

    private void scheduleCheck(CheckTask checkTask, Duration delay) {
        if (!scheduling.get()) {
            return;
        }
        try {
            scheduler.schedule(() -> submitScheduledCheck(checkTask), Math.max(delay.toMillis(), 0L), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            if (scheduling.get()) {
                log.warn("Unable to schedule connectivity check: resource={} check={}",
                        checkTask.resource().getName(), checkTask.check().getName(), ex);
            }
        }
    }

    private void submitScheduledCheck(CheckTask checkTask) {
        if (!scheduling.get()) {
            return;
        }
        try {
            operationExecutor.submit(operationDescription(checkTask), () -> {
                try {
                    check(UUID.randomUUID().toString(), checkTask.resource(), checkTask.check());
                } finally {
                    scheduleCheck(checkTask, interval(checkTask.check()));
                }
            });
        } catch (RejectedExecutionException ex) {
            if (scheduling.get()) {
                log.warn("Unable to submit connectivity check: resource={} check={}",
                        checkTask.resource().getName(), checkTask.check().getName(), ex);
                scheduleCheck(checkTask, interval(checkTask.check()));
            }
        }
    }

    private void runChecks(List<CheckTask> checkTasks) {
        List<Future<?>> futures = new ArrayList<>();
        for (CheckTask checkTask : checkTasks) {
            futures.add(operationExecutor.submit(operationDescription(checkTask),
                    () -> check(UUID.randomUUID().toString(), checkTask.resource(), checkTask.check())));
        }

        awaitChecks(futures);
    }

    private void awaitChecks(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                futures.forEach(waitingFuture -> waitingFuture.cancel(true));
                return;
            } catch (ExecutionException ex) {
                log.warn("Connectivity check task failed unexpectedly", ex.getCause());
            }
        }
    }

    private void check(String runId, Resource resource, Check check) {
        String resourceName = resource.getName();
        String checkName = check.getName();
        CheckType type = check.getType();
        Instant checkedAt = Instant.now();

        try {
            require(type != null, "check type is required");
            CheckResult result = executeCheck(resource, check);

            resultRepository.save(runId, resourceName, checkName, checkTypeName(type), checkedAt, result);

            if (result.successful()) {
                log.info("Connectivity check succeeded: resource={} check={} type={} durationMs={} details={}",
                        resourceName, checkName, type, result.duration().toMillis(), result.details());
            } else {
                log.warn("Connectivity check failed: resource={} check={} type={} durationMs={} details={}",
                        resourceName, checkName, type, result.duration().toMillis(), result.details());
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            CheckResult result = new CheckResult(
                    false,
                    Duration.between(checkedAt, Instant.now()),
                    "check failed before a result could be produced",
                    ex.getMessage());
            resultRepository.save(runId, resourceName, checkName, checkTypeName(type), checkedAt, result);

            log.warn("Connectivity check errored: resource={} check={} type={} details={}",
                    resourceName, checkName, type, ex.getMessage(), ex);
        }
    }

    CheckResult executeCheck(Resource resource, Check check) throws IOException, InterruptedException, CertificateException {
        CheckType type = check.getType();
        require(type != null, "check type is required");
        return switch (type) {
            case HTTP_GET -> checkHttpGet(resource, check);
            case REACHABILITY -> checkReachability(resource, check);
            case PING -> checkPing(resource, check);
            case TCP_CONNECT -> checkTcpConnect(resource, check);
            case TLS_CERTIFICATE -> checkTlsCertificate(resource, check);
            case DNS_LOOKUP -> checkDnsLookup(resource, check);
        };
    }

    private CheckResult checkHttpGet(Resource resource, Check check) throws IOException, InterruptedException {
        require(check.getUrl() != null, "HTTP_GET check requires url");

        Instant startedAt = Instant.now();
        HttpRequest request = HttpRequest.newBuilder(check.getUrl())
                .GET()
                .timeout(timeout(check))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Duration duration = Duration.between(startedAt, Instant.now());

        int bodyLength = response.body() == null ? 0 : response.body().length();
        boolean statusOk = response.statusCode() == check.getExpectedStatus();
        boolean bodyOk = !check.isRequireNonEmptyBody() || StringUtils.hasText(response.body());
        boolean successful = statusOk && bodyOk;

        String details = "resource=%s url=%s status=%d expectedStatus=%d bodyLength=%d nonEmptyRequired=%s"
                .formatted(resource.getName(), check.getUrl(), response.statusCode(), check.getExpectedStatus(), bodyLength,
                        check.isRequireNonEmptyBody());
        return new CheckResult(successful, duration, details);
    }

    private CheckResult checkReachability(Resource resource, Check check) throws IOException {
        String host = check.getHost();
        require(StringUtils.hasText(host), "REACHABILITY check requires host");

        Instant startedAt = Instant.now();
        InetAddress address = InetAddress.getByName(host);
        boolean reachable = address.isReachable(timeoutMillis(check));
        Duration duration = Duration.between(startedAt, Instant.now());

        String details = "resource=%s host=%s address=%s reachable=%s".formatted(
                resource.getName(), host, address.getHostAddress(), reachable);
        return new CheckResult(reachable, duration, details);
    }

    CheckResult checkPing(Resource resource, Check check) throws IOException, InterruptedException {
        String host = check.getHost();
        require(StringUtils.hasText(host), "PING check requires host");

        Instant startedAt = Instant.now();
        Process process = new ProcessBuilder(systemPingCommand(host, timeout(check)))
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Math.max(timeout(check).toMillis() + 1_000L, 1_000L), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        Duration duration = Duration.between(startedAt, Instant.now());
        int exitCode = finished ? process.exitValue() : -1;
        boolean successful = finished && exitCode == 0;

        String details = "resource=%s host=%s exitCode=%d output=%s".formatted(
                resource.getName(), host, exitCode, compact(output));
        return new CheckResult(successful, duration, details, successful ? null : compact(output));
    }

    private List<String> systemPingCommand(String host, Duration timeout) {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("ping", "-n", "1", "-w", Long.toString(Math.max(timeout.toMillis(), 1L)), host);
        }
        return List.of("ping", "-c", "1", "-W", Long.toString(Math.max(timeout.toSeconds(), 1L)), host);
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

    CheckResult checkTcpConnect(Resource resource, Check check) throws IOException {
        String host = check.getHost();
        int port = requiredPort(check, "TCP_CONNECT check requires port");
        require(StringUtils.hasText(host), "TCP_CONNECT check requires host");

        Instant startedAt = Instant.now();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis(check));
        }
        Duration duration = Duration.between(startedAt, Instant.now());

        String details = "resource=%s host=%s port=%d connected=true".formatted(resource.getName(), host, port);
        return new CheckResult(true, duration, details);
    }

    CheckResult checkTlsCertificate(Resource resource, Check check) throws IOException, CertificateException {
        String host = check.getHost();
        int port = portOrDefault(check, 443);
        require(StringUtils.hasText(host), "TLS_CERTIFICATE check requires host");

        Instant startedAt = Instant.now();
        X509Certificate certificate;
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis(check));
            socket.setSoTimeout(timeoutMillis(check));

            SSLParameters sslParameters = socket.getSSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(sslParameters);

            socket.startHandshake();
            certificate = peerCertificate(socket);
            certificate.checkValidity();
        }
        Duration duration = Duration.between(startedAt, Instant.now());

        String details = "resource=%s host=%s port=%d subject=\"%s\" issuer=\"%s\" notBefore=%s notAfter=%s"
                .formatted(resource.getName(), host, port, certificate.getSubjectX500Principal(),
                        certificate.getIssuerX500Principal(), certificate.getNotBefore().toInstant(),
                        certificate.getNotAfter().toInstant());
        return new CheckResult(true, duration, details);
    }

    CheckResult checkDnsLookup(Resource resource, Check check) throws IOException {
        String host = check.getHost();
        require(StringUtils.hasText(host), "DNS_LOOKUP check requires host");

        Instant startedAt = Instant.now();
        InetAddress[] addresses = InetAddress.getAllByName(host);
        Duration duration = Duration.between(startedAt, Instant.now());

        boolean successful = addresses.length > 0;
        String resolvedAddresses = Arrays.stream(addresses)
                .map(InetAddress::getHostAddress)
                .collect(Collectors.joining(","));
        String details = "resource=%s host=%s resolvedCount=%d addresses=%s"
                .formatted(resource.getName(), host, addresses.length, resolvedAddresses);
        return new CheckResult(successful, duration, details);
    }

    private X509Certificate peerCertificate(SSLSocket socket) throws SSLPeerUnverifiedException {
        Certificate[] certificates = socket.getSession().getPeerCertificates();
        if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate certificate)) {
            throw new SSLPeerUnverifiedException("TLS peer did not provide an X.509 certificate");
        }
        return certificate;
    }

    private Duration timeout(Check check) {
        return check.getTimeout() == null ? Duration.ofSeconds(10) : check.getTimeout();
    }

    private Duration interval(Check check) {
        return positive(check.getInterval(), "check interval");
    }

    private void validateScheduledCheck(Resource resource, Check check) {
        require(StringUtils.hasText(resource.getName()), "resource name is required");
        require(StringUtils.hasText(check.getName()), "check name is required");
        require(check.getType() != null, "check type is required");
        positive(check.getInterval(), "check interval");
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

    private int timeoutMillis(Check check) {
        long millis = timeout(check).toMillis();
        return Math.toIntExact(Math.clamp(millis, 1L, Integer.MAX_VALUE));
    }

    private int requiredPort(Check check, String message) {
        require(check.getPort() != null, message);
        return validatePort(check.getPort());
    }

    private int portOrDefault(Check check, int defaultPort) {
        return validatePort(check.getPort() == null ? defaultPort : check.getPort());
    }

    private int validatePort(int port) {
        require(port > 0 && port <= 65535, "check port must be between 1 and 65535");
        return port;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private String checkTypeName(CheckType type) {
        return type == null ? "UNKNOWN" : type.name();
    }

    private String operationDescription(CheckTask checkTask) {
        return "connectivity check resource=%s check=%s".formatted(
                checkTask.resource().getName(), checkTask.check().getName());
    }

    private record CheckTask(Resource resource, Check check) {
    }
}
