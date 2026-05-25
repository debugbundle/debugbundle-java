# Java SDK Configuration and Verification

This reference closes the first-install gaps for the DebugBundle Java SDK family. It covers support labels, dependency alignment, configuration source precedence, relay behavior, service naming, safe startup diagnostics, and the app-driven smoke path used before release.

## Support Labels

| Surface | Label | Notes |
| --- | --- | --- |
| Java 17 | minimum compatibility | Minimum supported runtime and compile target (`--release 17`). |
| Java 21 | recommended production | Current LTS lane used for day-to-day development and release smoke. |
| Java 25 | validation lane | Compatibility lane for current GA JVMs. |
| Java 26 | validation lane | Compatibility lane for the newest GA JVM. |
| Spring Boot 3.x / Spring Framework 6.x | supported | First-class Spring MVC starter surface. |
| `jakarta.servlet` 5.x/6.x | supported | First-class servlet adapter lane. |
| `jakarta.ws.rs` 3.x | supported | First-class JAX-RS adapter lane. |
| `javax.servlet` 4.x on Java 17+ | installed-base compatibility | Classic Java EE namespace support for app-server deployments. |
| `javax.ws.rs` 2.x / RESTEasy on Java 17+ | installed-base compatibility | WildFly and JBoss compatibility lane. |
| Spring Boot 2.x | out of scope | Not supported in V1. |
| Java 8 / Java 11 runtimes | out of scope | Use Java 17+ for every supported surface. |

## Dependency Alignment

Import the published BOM whenever you use more than one DebugBundle Java artifact.

Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.debugbundle</groupId>
      <artifactId>debugbundle-java-parent</artifactId>
      <version>0.1.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Gradle:

```kotlin
dependencies {
    implementation(platform("com.debugbundle:debugbundle-java-parent:0.1.1"))
    implementation("com.debugbundle:debugbundle-java-core")
    implementation("com.debugbundle:debugbundle-java-servlet-jakarta")
    implementation("com.debugbundle:debugbundle-java-jaxrs-jakarta")
}
```

Keep every DebugBundle artifact on the same version. Do not mix `core`, `web`, servlet, JAX-RS, starter, or agent versions.

## Configuration Source Precedence

Direct SDK usage:

1. Programmatic `DebugBundleConfig.builder()` values
2. SDK defaults

Core config loader (`DebugBundleConfigLoader.load(primary, secondary, fallbackService)`):

1. `primary` lookup
2. `secondary` lookup
3. JVM system properties
4. Environment variables
5. `fallbackService` or builder defaults

Javaagent bootstrap:

1. Inline `-javaagent:` arguments such as `project-token=...`
2. Properties file supplied by `config=...`, `-Ddebugbundle.config`, or `DEBUGBUNDLE_CONFIG`
3. JVM system properties
4. Environment variables
5. Defaults

Servlet and JAX-RS WAR installs:

1. Filter or servlet init params
2. Servlet context params
3. JVM system properties
4. Environment variables
5. Defaults

Spring Boot starter:

1. Spring configuration property resolution (`application.yml`, profile config, env, system properties, command-line args, config server, and so on)
2. Starter defaults

Capture policy is server-owned. Do not set capture-policy fields in local Java config; the SDK fetches them from `GET /v1/sdk/config`.

## Core SDK Keys

| Key | Builder setter | Environment variable | JVM system property | Default | Notes |
| --- | --- | --- | --- | --- | --- |
| `debugbundle.project-token` | `projectToken(String)` | `DEBUGBUNDLE_PROJECT_TOKEN` or `DEBUGBUNDLE_TOKEN` | `debugbundle.project-token` | none | Required for connected mode. Optional for `local-only` mode. |
| `debugbundle.environment` | `environment(String)` | `DEBUGBUNDLE_ENVIRONMENT` | `debugbundle.environment` | `development` | Backend service environment. |
| `debugbundle.service` | `service(String)` | `DEBUGBUNDLE_SERVICE` | `debugbundle.service` | `unknown-service` | Backend service name. Relay overrides are separate. |
| `debugbundle.enabled` | `enabled(boolean)` | `DEBUGBUNDLE_ENABLED` | `debugbundle.enabled` | `true` | Global kill switch. |
| `debugbundle.redact-fields` | `redactFields(List<String>)` | `DEBUGBUNDLE_REDACT_FIELDS` | `debugbundle.redact-fields` | sensitive defaults | Comma-separated in properties and env sources. |
| `debugbundle.sample-rate` | `sampleRate(double)` | `DEBUGBUNDLE_SAMPLE_RATE` | `debugbundle.sample-rate` | `1.0` | Applied before buffering and transport. |
| `debugbundle.batch-size` | `batchSize(int)` | `DEBUGBUNDLE_BATCH_SIZE` | `debugbundle.batch-size` | `25` | Max events per flush batch. |
| `debugbundle.flush-interval` | `flushInterval(Duration)` | `DEBUGBUNDLE_FLUSH_INTERVAL` | `debugbundle.flush-interval` | `5s` | Accepts `ms`, `s`, ISO-8601 duration, or plain millis. |
| `debugbundle.endpoint` | `endpoint(String)` | `DEBUGBUNDLE_ENDPOINT` | `debugbundle.endpoint` | `https://api.debugbundle.com/v1/events` | Connected ingestion endpoint. |
| `debugbundle.probes-poll-interval` | `probesPollInterval(Duration)` | `DEBUGBUNDLE_PROBES_POLL_INTERVAL` | `debugbundle.probes-poll-interval` | `60s` | Remote-probe and remote-config polling. |
| `debugbundle.max-probe-labels` | `maxProbeLabels(int)` | `DEBUGBUNDLE_MAX_PROBE_LABELS` | `debugbundle.max-probe-labels` | `50` | Ring-buffer label count. |
| `debugbundle.max-probe-entries-per-label` | `maxProbeEntriesPerLabel(int)` | `DEBUGBUNDLE_MAX_PROBE_ENTRIES_PER_LABEL` | `debugbundle.max-probe-entries-per-label` | `10` | Entries retained per label. |
| `debugbundle.probe-flush-on-error` | `probeFlushOnError(boolean)` | `DEBUGBUNDLE_PROBE_FLUSH_ON_ERROR` | `debugbundle.probe-flush-on-error` | `true` | Attach probe ring buffers to captured exceptions. |
| `debugbundle.log-level` | `logLevel(LogLevel)` | `DEBUGBUNDLE_LOG_LEVEL` | `debugbundle.log-level` | `warning` | Minimum captured log severity. |
| `debugbundle.request-timeout` | `requestTimeout(Duration)` | `DEBUGBUNDLE_REQUEST_TIMEOUT` | `debugbundle.request-timeout` | `5s` | HTTP transport timeout. |
| `debugbundle.project-mode` | `projectMode(String)` | `DEBUGBUNDLE_PROJECT_MODE` | `debugbundle.project-mode` | `connected` | `connected` or `local-only`. |
| `debugbundle.local-events-dir` | `localEventsDir(String)` | `DEBUGBUNDLE_LOCAL_EVENTS_DIR` | `debugbundle.local-events-dir` | `.debugbundle/local/events` | Local event file destination. |

Per-deployment app-server overrides:

| Key pattern | Purpose |
| --- | --- |
| `debugbundle.deployments.<deployment>.service` | Override service name for one WAR or deployable. |

## Relay Keys

Spring Boot property names live under the same `debugbundle.relay.*` prefix. Servlet and JAX-RS WAR installs use the same key names as init params, context params, system properties, or env vars where listed below.

| Key | Spring Boot property | Environment variable | JVM system property | Default | Notes |
| --- | --- | --- | --- | --- | --- |
| `debugbundle.relay.enabled` | `debugbundle.relay.enabled` | none | none | `true` | Spring Boot only. WAR installs enable relay by registering the servlet. |
| `debugbundle.relay.rate-limit-per-minute` | `debugbundle.relay.rate-limit-per-minute` | `DEBUGBUNDLE_RELAY_RATE_LIMIT_PER_MINUTE` | `debugbundle.relay.rate-limit-per-minute` | `60` | Per-IP rate limit. |
| `debugbundle.relay.durable-write` | `debugbundle.relay.durable-write` | `DEBUGBUNDLE_RELAY_DURABLE_WRITE` | `debugbundle.relay.durable-write` | `true` | In connected mode, write spool file before forwarding. |
| `debugbundle.relay.spool-dir` | `debugbundle.relay.spool-dir` | `DEBUGBUNDLE_RELAY_SPOOL_DIR` | `debugbundle.relay.spool-dir` | `.debugbundle/local/browser-relay-spool` | Durable relay spool directory. |
| `debugbundle.relay.allowed-origins` | `debugbundle.relay.allowed-origins` | `DEBUGBUNDLE_RELAY_ALLOWED_ORIGINS` | `debugbundle.relay.allowed-origins` | empty | Comma-separated list for split frontend/backend hosts. Empty means same-host origin validation. |
| `debugbundle.relay.service` | `debugbundle.relay.service` | `DEBUGBUNDLE_RELAY_SERVICE` | `debugbundle.relay.service` | unset | Optional explicit browser-service override. Leave unset to preserve browser-owned service identity. |
| `debugbundle.relay.environment` | `debugbundle.relay.environment` | `DEBUGBUNDLE_RELAY_ENVIRONMENT` | `debugbundle.relay.environment` | unset | Optional explicit browser-environment override. |

## Javaagent Bootstrap Keys

| Inline argument | Properties-file key | Purpose |
| --- | --- | --- |
| `config=/path/to/debugbundle.properties` | `debugbundle.config` | External properties file path. |
| `project-token=...` | `debugbundle.project-token` | Connected-mode token. |
| `service=...` | `debugbundle.service` | Backend service name. |
| `environment=...` | `debugbundle.environment` | Backend environment name. |
| `project-mode=connected|local-only` | `debugbundle.project-mode` | Transport mode. |
| `capture-jul=true|false` | none | Install JUL and JBoss LogManager-compatible capture. |
| `capture-uncaught=true|false` | none | Install the uncaught-exception hook. |

## Service Naming Guidance

Use explicit backend service names in production.

Examples:

```properties
debugbundle.service=identity-api
debugbundle.deployments.portal.service=web-portal
debugbundle.deployments.orders.service=orders-api
```

Browser relay defaults:

- The relay preserves the browser event's own `service.name` and `service.environment` by default.
- Set `debugbundle.relay.service` or `debugbundle.relay.environment` only when you intentionally want the backend relay to rewrite browser-owned identity.
- Keep backend and browser services distinct when they represent different deployables, even when they share a project and trace IDs.

## Relay Behavior and Security

Every full relay surface accepts `POST /debugbundle/browser` and enforces the same contract:

- same-origin validation using `Origin`, with `Referer` fallback when `Origin` is absent,
- explicit allowlist support for split frontend/backend hosts through `debugbundle.relay.allowed-origins`,
- `Content-Type: application/json` enforcement,
- `256 KB` maximum request size,
- per-IP rate limiting,
- schema validation plus unsupported-event rejection,
- stripping browser-supplied `project_token`, `organization_id`, auth headers, cookies, and other trust-boundary fields,
- preservation of browser-owned correlation keys (`trace_id`, `request_id`, `session_id`, `user_id_hash`).

Delivery modes:

- `local-only`: accepts browser batches without a project token and writes event files to `.debugbundle/local/events`.
- `connected` with `debugbundle.relay.durable-write=true`: writes a spool file first, then forwards with the server-side project token when present. If the token is missing, the spool file is retained and the request still returns `202`.
- `connected` with `debugbundle.relay.durable-write=false`: forwards immediately. If the project token is missing or invalid, forwarding fails and the relay returns a server error instead of claiming success.
- Spring Boot can disable the route entirely with `debugbundle.relay.enabled=false`. WAR installs disable it by not registering the relay servlet.

Spring Security example:

```java
http.authorizeHttpRequests(authorize -> authorize
        .requestMatchers(HttpMethod.POST, "/debugbundle/browser").permitAll()
        .anyRequest().authenticated());
```

Java EE / Jakarta EE `web.xml` example:

```xml
<security-constraint>
  <web-resource-collection>
    <web-resource-name>DebugBundle browser relay</web-resource-name>
    <url-pattern>/debugbundle/browser</url-pattern>
    <http-method>POST</http-method>
  </web-resource-collection>
</security-constraint>
```

Leave out an `auth-constraint` for the relay path so the container does not require user login for browser delivery. Keep TLS and broader application auth policy in your normal container security configuration.

## Safe Startup and Status Semantics

`DebugBundle.status()` and `DebugBundle.lastEventAt()` are the primary startup diagnostics for Java applications.

Status meanings:

- `HEALTHY`: SDK is active. In connected mode the last delivery succeeded or no delivery has failed yet. In `local-only` mode the SDK is ready to write local event files, even when no project token is configured.
- `DEGRADED`: transport failures or rate limiting occurred. The SDK keeps buffering or writing locally and never throws into host code.
- `DISCONNECTED`: SDK is disabled, not initialized, or connected mode is configured without a usable project token.

Connected-mode startup rules:

- Missing token: the SDK does not throw, host startup continues, and `DebugBundle.status()` reports `DISCONNECTED`.
- Invalid or revoked token: startup remains non-throwing; the SDK moves to `DEGRADED` after the first failed delivery attempt.
- Local-only mode: a token is optional. This is the supported no-cloud path for first-event verification and offline workflows.

## App-Driven Verification

Local workspace smoke:

```bash
make smoke
```

Direct script form:

```bash
bash ./smoke/run-app-driven-smoke.sh
```

Published-artifact smoke:

```bash
bash ./smoke/run-app-driven-smoke.sh --published 0.1.1
```

The smoke app installs the published BOM plus `debugbundle-java-core`, starts a mock ingestion server, initializes the SDK, opens a request scope with trace and request IDs, captures an exception, flushes, and asserts that the received event contains the expected `service`, `environment`, SDK metadata, and correlation fields.