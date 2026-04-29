package net.edubovit.connectivity.monitor.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "connectivity")
public class ConnectivityMonitorProperties {

    private Duration interval = Duration.ofSeconds(60);

    private Duration initialDelay = Duration.ZERO;

    private int concurrency = 10;

    private List<Resource> resources = defaultResources();

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(List<Resource> resources) {
        this.resources = resources;
    }

    private static List<Resource> defaultResources() {
        Check homepage = new Check();
        homepage.setName("homepage-get");
        homepage.setType(CheckType.HTTP_GET);
        homepage.setUrl(URI.create("https://www.google.com/"));
        homepage.setExpectedStatus(200);
        homepage.setRequireNonEmptyBody(true);

        Check ping = new Check();
        ping.setName("host-ping");
        ping.setType(CheckType.REACHABILITY);
        ping.setHost("google.com");

        Check tcpConnect = new Check();
        tcpConnect.setName("https-tcp-connect");
        tcpConnect.setType(CheckType.TCP_CONNECT);
        tcpConnect.setHost("www.google.com");
        tcpConnect.setPort(443);

        Check tlsCertificate = new Check();
        tlsCertificate.setName("https-tls-certificate");
        tlsCertificate.setType(CheckType.TLS_CERTIFICATE);
        tlsCertificate.setHost("www.google.com");
        tlsCertificate.setPort(443);

        Check dnsLookup = new Check();
        dnsLookup.setName("dns-lookup");
        dnsLookup.setType(CheckType.DNS_LOOKUP);
        dnsLookup.setHost("www.google.com");

        Resource google = new Resource();
        google.setName("Google");
        google.setChecks(new ArrayList<>(List.of(homepage, ping, tcpConnect, tlsCertificate, dnsLookup)));

        return new ArrayList<>(List.of(google));
    }

    public static class Resource {

        private String name;

        private List<Check> checks = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Check> getChecks() {
            return checks;
        }

        public void setChecks(List<Check> checks) {
            this.checks = checks;
        }
    }

    public static class Check {

        private String name;

        private CheckType type;

        private URI url;

        private String host;

        private Integer port;

        private int expectedStatus = 200;

        private boolean requireNonEmptyBody = true;

        private Duration timeout = Duration.ofSeconds(10);

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public CheckType getType() {
            return type;
        }

        public void setType(CheckType type) {
            this.type = type;
        }

        public URI getUrl() {
            return url;
        }

        public void setUrl(URI url) {
            this.url = url;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public int getExpectedStatus() {
            return expectedStatus;
        }

        public void setExpectedStatus(int expectedStatus) {
            this.expectedStatus = expectedStatus;
        }

        public boolean isRequireNonEmptyBody() {
            return requireNonEmptyBody;
        }

        public void setRequireNonEmptyBody(boolean requireNonEmptyBody) {
            this.requireNonEmptyBody = requireNonEmptyBody;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
