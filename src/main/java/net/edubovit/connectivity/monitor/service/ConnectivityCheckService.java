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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConnectivityCheckService {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityCheckService.class);

    private final ConnectivityMonitorProperties properties;

    private final CheckResultRepository resultRepository;

    private final HttpClient httpClient;

    public ConnectivityCheckService(ConnectivityMonitorProperties properties, CheckResultRepository resultRepository) {
        this.properties = properties;
        this.resultRepository = resultRepository;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Scheduled(
            fixedDelayString = "${connectivity.interval:60s}",
            initialDelayString = "${connectivity.initial-delay:0s}"
    )
    public void checkAll() {
        List<Resource> resources = properties.getResources();
        if (resources == null || resources.isEmpty()) {
            log.warn("No connectivity resources configured");
            return;
        }

        String runId = UUID.randomUUID().toString();
        List<CheckTask> checkTasks = checkTasks(resources);
        if (checkTasks.isEmpty()) {
            log.warn("No connectivity checks configured");
            return;
        }

        int concurrency = concurrencyLimit();
        log.info("Starting connectivity checks: resources={} checks={} concurrency={}",
                resources.size(), checkTasks.size(), concurrency);
        runChecks(runId, checkTasks, concurrency);
        log.info("Finished connectivity checks: resources={} checks={}", resources.size(), checkTasks.size());
    }

    private List<CheckTask> checkTasks(List<Resource> resources) {
        List<CheckTask> checkTasks = new ArrayList<>();
        for (Resource resource : resources) {
            List<Check> checks = resource.getChecks();
            if (checks == null || checks.isEmpty()) {
                log.warn("No connectivity checks configured for resource: resource={}", resource.getName());
                continue;
            }

            checks.stream()
                    .map(check -> new CheckTask(resource, check))
                    .forEach(checkTasks::add);
        }
        return checkTasks;
    }

    private void runChecks(String runId, List<CheckTask> checkTasks, int concurrency) {
        Semaphore semaphore = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (CheckTask checkTask : checkTasks) {
                futures.add(executor.submit(() -> runCheckWithPermit(runId, checkTask, semaphore)));
            }

            awaitChecks(futures);
        }
    }

    private void runCheckWithPermit(String runId, CheckTask checkTask, Semaphore semaphore) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            check(runId, checkTask.resource(), checkTask.check());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Connectivity check task interrupted before execution: resource={} check={}",
                    checkTask.resource().getName(), checkTask.check().getName());
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
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

    private int concurrencyLimit() {
        int configuredConcurrency = properties.getConcurrency();
        if (configuredConcurrency < 1) {
            log.warn("Invalid connectivity concurrency configured: {}. Using 1 instead", configuredConcurrency);
            return 1;
        }
        return configuredConcurrency;
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

    private record CheckTask(Resource resource, Check check) {
    }
}
