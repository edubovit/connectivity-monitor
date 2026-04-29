# connectivity-monitor

Spring Boot application that checks configured resources on a schedule, writes check results to application logs,
persists them to a local H2 file database, and visualizes availability in a web UI.

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

## Default resources and checks

The default configuration runs all checks every 60 seconds. Each configured resource can include these check types:

- `HTTP_GET`: performs a GET request to `url`, validates `expected-status`, and can require a non-empty body.
- `REACHABILITY`: checks host reachability using Java's `InetAddress.isReachable(...)`.
- `PING`: runs the operating system `ping` command once for `host`.
- `TCP_CONNECT`: opens a TCP connection to `host` + `port`.
- `TLS_CERTIFICATE`: connects to `host` + `port` (default `443`) and verifies the TLS handshake, hostname, trust chain, and current certificate validity.
- `DNS_LOOKUP`: resolves `host` and succeeds when at least one address is returned.

Each check defines its own endpoint (`url` for `HTTP_GET`, `host` for `REACHABILITY`/`PING`/`DNS_LOOKUP`, `host` + `port` for `TCP_CONNECT`/`TLS_CERTIFICATE`); resources do not define a shared target.

Checks are executed on Java virtual threads. Use `connectivity.concurrency` to limit how many checks can run at once;
the default is `10`.

Configuration is in `src/main/resources/application.yml` under the `connectivity` prefix.

## Persistence and UI

- Check results are persisted in a local H2 file database: `./connectivity-monitor-data.*`.
- The UI shows resource status cards and a green/red resource status timeline graph for the selected range.
- A resource is shown as online only when every check for that resource is successful in a scheduled run.
- REST endpoints are available under `/api`:
  - `GET /api/resources`
  - `GET /api/results?from=<instant>&to=<instant>`
  - `GET /api/availability?from=<instant>&to=<instant>`
- Availability is calculated as `online resource samples / total resource samples * 100` for the selected time range.

If upgrading from an earlier schema, delete the local `connectivity-monitor-data.*` files to reset history.
