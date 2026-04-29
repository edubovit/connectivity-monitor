package net.edubovit.connectivity.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConnectivityMonitorApplicationTest {

    @Test
    void expandsConfigArgumentWithSeparatePath() {
        assertThat(ConnectivityMonitorApplication.expandConfigArgument(new String[] {"--config", "config.yaml", "--server.port=9090"}))
                .containsExactly(
                        "--spring.config.additional-location=file:config.yaml",
                        "--server.port=9090");
    }

    @Test
    void expandsConfigArgumentWithEqualsPath() {
        assertThat(ConnectivityMonitorApplication.expandConfigArgument(new String[] {"--config=config.yaml"}))
                .containsExactly("--spring.config.additional-location=file:config.yaml");
    }

    @Test
    void failsWhenConfigPathIsMissing() {
        assertThatThrownBy(() -> ConnectivityMonitorApplication.expandConfigArgument(new String[] {"--config"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--config requires a configuration file path");
    }
}
