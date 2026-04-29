# connectivity-monitor

Spring Boot application that checks configured resources on per-check schedules, records optional numeric shell-command
metrics, persists history to a local H2 file database, and visualizes availability and metrics in a web UI.

## Requirements

- Java 25
- Gradle 9.4.1 (or the Gradle wrapper, if generated/available)

## Build

```bash
./gradlew bootJar
```

On Windows, use `gradlew.bat bootJar`.

## Run

```bash
java -jar build/libs/connectivity-monitor-0.0.1-SNAPSHOT.jar
```

Open the monitor UI at <http://localhost:20001/>.

To override the packaged defaults with an external configuration file:

```bash
java -jar connectivity-monitor.jar --config config.yaml
```

`--config` is shorthand for Spring Boot's additional config location, so values in the external file override the
defaults bundled in the JAR while unspecified values keep their defaults. The file must exist.

## Default resources, checks, and metrics

Each configured check must define its own `interval`. There is no global check interval. Each configured resource can
include these check types:

- `HTTP_GET`: performs a GET request to `url`, validates `expected-status`, and can require a non-empty body.
- `REACHABILITY`: checks host reachability using Java's `InetAddress.isReachable(...)`.
- `PING`: runs the operating system `ping` command once for `host`.
- `TCP_CONNECT`: opens a TCP connection to `host` + `port`.
- `TLS_CERTIFICATE`: connects to `host` + `port` (default `443`) and verifies the TLS handshake, hostname, trust chain, and current certificate validity.
- `DNS_LOOKUP`: resolves `host` and succeeds when at least one address is returned.

Each check defines its own endpoint (`url` for `HTTP_GET`, `host` for `REACHABILITY`/`PING`/`DNS_LOOKUP`, `host` + `port` for `TCP_CONNECT`/`TLS_CERTIFICATE`); resources do not define a shared target.

Checks are executed on Java virtual threads. Use `connectivity.concurrency` to limit how many scheduled checks and metric
commands can run at once; the default is `10`.

Numeric metrics are configured at the top level under `connectivity.metrics`. Each metric must define `name`, `interval`,
and a shell `command`; `timeout` and `unit` are optional. The command runs through the operating-system shell
(`sh -c` on Linux/macOS, `cmd.exe /c` on Windows), so pipes and quoting are supported. After trimming stdout, the full
stdout must be one numeric value. Non-zero exit codes, timeouts, and non-numeric stdout are persisted as error samples
with no numeric value.

Example:

```yaml
connectivity:
  concurrency: 10
  initial-delay: 0s
  resources:
    - name: Google
      checks:
        - name: homepage-get
          type: HTTP_GET
          url: https://www.google.com/
          interval: 60s
          timeout: 5s
  metrics:
    - name: vpn-connections
      unit: connections
      interval: 30s
      timeout: 10s
      command: 'ss -Htanp "dst 138.124.6.125 or src 138.124.6.125" | wc -l'
```

Configuration is in `src/main/resources/application.yml` under the `connectivity` prefix.

## Persistence and UI

- Check results and metric measurements are persisted in a local H2 file database: `./connectivity-monitor-data.*`.
- The UI shows resource status cards, a green/red resource status timeline graph, and numeric metric graphs for the selected range.
- A resource is shown as online only when every latest known check result for that resource is successful.
- With independent check intervals, resource status is calculated from the latest known result of every configured check at
  each check-result timestamp. A resource timeline starts only after every configured check has reported at least once.
- REST endpoints are available under `/api`:
  - `GET /api/resources`
  - `GET /api/results?from=<instant>&to=<instant>`
  - `GET /api/availability?from=<instant>&to=<instant>`
  - `GET /api/metrics?from=<instant>&to=<instant>`
- Availability is calculated as `online resource samples / total resource samples * 100` for the selected time range.

This version is intended to start with an empty database when upgrading from earlier schemas. Delete the local
`connectivity-monitor-data.*` files to reset history.
