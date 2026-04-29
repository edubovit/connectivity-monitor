package net.edubovit.connectivity.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.edubovit.connectivity.monitor.config.CheckType;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Check;
import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties.Resource;
import net.edubovit.connectivity.monitor.data.CheckResultRepository;
import org.junit.jupiter.api.Test;

class ConnectivityCheckServiceTest {

    @Test
    void checkAllUsesVirtualThreadsWithConfiguredConcurrencyLimit() {
        ConnectivityMonitorProperties properties = new ConnectivityMonitorProperties();
        properties.setConcurrency(2);
        properties.setResources(new ArrayList<>(List.of(resourceWithChecks(5))));
        AtomicInteger activeChecks = new AtomicInteger();
        AtomicInteger maxActiveChecks = new AtomicInteger();

        ScheduledOperationExecutor operationExecutor = new ScheduledOperationExecutor(properties);
        ConnectivityCheckService service = new ConnectivityCheckService(properties, mock(CheckResultRepository.class), operationExecutor) {
            @Override
            CheckResult executeCheck(Resource resource, Check check) throws IOException, InterruptedException, CertificateException {
                int active = activeChecks.incrementAndGet();
                maxActiveChecks.updateAndGet(currentMax -> Math.max(currentMax, active));
                try {
                    Thread.sleep(100);
                    return new CheckResult(true, Duration.ofMillis(100), "test check");
                } finally {
                    activeChecks.decrementAndGet();
                }
            }
        };

        service.checkAll();
        operationExecutor.close();

        assertThat(maxActiveChecks).hasValue(2);
    }

    @Test
    void tcpConnectSucceedsWhenSocketAcceptsConnections() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Check check = new Check();
            check.setHost(InetAddress.getLoopbackAddress().getHostAddress());
            check.setPort(serverSocket.getLocalPort());
            check.setTimeout(Duration.ofSeconds(1));

            CheckResult result = service().checkTcpConnect(resource(), check);

            assertThat(result.successful()).isTrue();
            assertThat(result.details()).contains("connected=true", "port=" + serverSocket.getLocalPort());
        }
    }

    @Test
    void tcpConnectRequiresPort() {
        Check check = new Check();
        check.setHost("127.0.0.1");

        assertThatThrownBy(() -> service().checkTcpConnect(resource(), check))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TCP_CONNECT check requires port");
    }

    @Test
    void dnsLookupSucceedsWhenHostResolves() throws Exception {
        Check check = new Check();
        check.setHost("localhost");

        CheckResult result = service().checkDnsLookup(resource(), check);

        assertThat(result.successful()).isTrue();
        assertThat(result.details()).contains("host=localhost", "resolvedCount=");
    }

    @Test
    void pingRequiresHost() {
        Check check = new Check();

        assertThatThrownBy(() -> service().checkPing(resource(), check))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PING check requires host");
    }

    @Test
    void tlsCertificateRequiresHost() {
        Check check = new Check();

        assertThatThrownBy(() -> service().checkTlsCertificate(resource(), check))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TLS_CERTIFICATE check requires host");
    }

    private ConnectivityCheckService service() {
        ConnectivityMonitorProperties properties = new ConnectivityMonitorProperties();
        return new ConnectivityCheckService(properties, mock(CheckResultRepository.class), new ScheduledOperationExecutor(properties));
    }

    private Resource resource() {
        Resource resource = new Resource();
        resource.setName("local-resource");
        return resource;
    }

    private Resource resourceWithChecks(int checkCount) {
        Resource resource = resource();
        List<Check> checks = new ArrayList<>();
        for (int i = 0; i < checkCount; i++) {
            Check check = new Check();
            check.setName("check-" + i);
            check.setType(CheckType.DNS_LOOKUP);
            check.setHost("localhost");
            checks.add(check);
        }
        resource.setChecks(checks);
        return resource;
    }
}
