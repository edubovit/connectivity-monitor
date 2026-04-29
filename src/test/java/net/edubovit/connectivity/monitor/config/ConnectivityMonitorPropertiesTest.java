package net.edubovit.connectivity.monitor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ConnectivityMonitorPropertiesTest {

    @Test
    void defaultsMatchRequestedResources() {
        ConnectivityMonitorProperties properties = new ConnectivityMonitorProperties();

        assertThat(properties.getConcurrency()).isEqualTo(10);
        assertThat(properties.getResources()).hasSize(1);
        assertThat(properties.getMetrics()).isEmpty();
        assertThat(properties.getResources().getFirst().getName()).isEqualTo("Google");
        assertThat(properties.getResources().getFirst().getChecks()).hasSize(5);
        assertThat(properties.getResources().getFirst().getChecks().getFirst().getType()).isEqualTo(CheckType.HTTP_GET);
        assertThat(properties.getResources().getFirst().getChecks().getFirst().getUrl().toString())
                .isEqualTo("https://www.google.com/");
        assertThat(properties.getResources().getFirst().getChecks().getFirst().getExpectedStatus()).isEqualTo(200);
        assertThat(properties.getResources().getFirst().getChecks().getFirst().isRequireNonEmptyBody()).isTrue();
        assertThat(properties.getResources().getFirst().getChecks().getFirst().getInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getResources().getFirst().getChecks().get(1).getType()).isEqualTo(CheckType.REACHABILITY);
        assertThat(properties.getResources().getFirst().getChecks().get(1).getHost()).isEqualTo("google.com");
        assertThat(properties.getResources().getFirst().getChecks().get(1).getInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getResources().getFirst().getChecks().get(2).getType()).isEqualTo(CheckType.TCP_CONNECT);
        assertThat(properties.getResources().getFirst().getChecks().get(2).getHost()).isEqualTo("www.google.com");
        assertThat(properties.getResources().getFirst().getChecks().get(2).getPort()).isEqualTo(443);
        assertThat(properties.getResources().getFirst().getChecks().get(3).getType()).isEqualTo(CheckType.TLS_CERTIFICATE);
        assertThat(properties.getResources().getFirst().getChecks().get(3).getHost()).isEqualTo("www.google.com");
        assertThat(properties.getResources().getFirst().getChecks().get(3).getPort()).isEqualTo(443);
        assertThat(properties.getResources().getFirst().getChecks().get(4).getType()).isEqualTo(CheckType.DNS_LOOKUP);
        assertThat(properties.getResources().getFirst().getChecks().get(4).getHost()).isEqualTo("www.google.com");
    }
}
