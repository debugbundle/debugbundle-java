# debugbundle-java

Java SDK, servlet adapters, and Spring Boot starter for DebugBundle.

`make evidence-performance` compares identical exception/recovery calls with
evidence disabled and enabled, including a full warning queue. It measures capture
caller time/allocation and the existing SDK worker's CPU/allocation separately,
checks native cause preservation, original-error priority and retained-byte
ceilings, and excludes setup/network/cleanup. The synthetic client uses local-only
mode with an in-memory transport; JVM allocation counters are required. Existing
capture-safety timing tests remain part of `make verify`.

![Maven Central](https://img.shields.io/maven-central/v/com.debugbundle/debugbundle-java-core?label=maven)
![CI](https://img.shields.io/github/actions/workflow/status/debugbundle/debugbundle-java/ci.yml?branch=main&label=ci)
![License](https://img.shields.io/badge/license-Apache--2.0-blue)

Use this repository to capture Java backend exceptions, request metadata, Logback or `java.util.logging` records, runtime context, and probe data. The SDK has a framework-neutral core, shared web helpers, servlet and JAX-RS adapters for Spring and non-Spring web applications, and a startup javaagent for app-server bootstrap.

Requires Java 17 or newer.

The Java26/27 Docker lanes copy the same pinned Maven 3.9.11 distribution into their native JDK images; they do not reinstall Maven through apt/dnf on each run. An explicit `MAVEN_IMAGE` override remains respected.

The installed smoke runs in a separate JVM using the [Maven external-process runner](https://www.mojohaus.org/exec-maven-plugin/examples/example-exec-for-java-programs.html), so native uncaught handlers are exercised without Maven thread-group interception.

Version 3.0 snapshots exceptions on its existing background worker through bounded weak references, preserving original stacks when the input is still reachable. Collected inputs have an explicit unavailable-details fallback. See [snapshot ownership and shutdown limits](MIGRATION-3.0.md#exception-snapshots-and-ownership).

The installation examples below target the **unpublished 3.1.0 source candidate**. Runtime and release qualification is still in progress. Review the [3.0 migration guide](MIGRATION-3.0.md) before upgrading; do not mix major versions across Java modules.

## Evidence candidate

New evidence uses the existing config and ingestion requests for capability discovery.
Unknown or older destinations keep the ordinary event shape. `captureExceptionEvidence`
defaults to true; `captureRecoveryObservations` defaults to false. A builder
`evidenceCapabilities(Map.of("version", 1, "features", ...))` pin asserts a verified
**complete** delivery and reader path, including relays and local processing.
Later discovery can revoke it; a pin does not override capture policy or privacy.

```java
client.runWithOperationContext(Map.of("kind", "job", "name", "queue.process", "limitMs", 1000), () -> {
    String failure = UUID.randomUUID().toString();
    String attempt = UUID.randomUUID().toString();
    client.captureException(new IllegalStateException("fictional fixture"), Map.of("failureRef", failure));
    client.captureRecovery(Map.of("failureRef", failure, "attemptId", attempt, "state", "attempt_started"));
    // Report recovery_observed only after the application verifies its semantic outcome.
    return null;
});
```

`captureRecovery` accepts `failureRef`, `attemptId`, `state`, optional
`evidenceScope`, `operation`, `statusCode` and `durationMs`. States are
`attempt_started`, `attempt_completed`, `attempt_failed`, `recovery_observed`
and `failure_persisted`. HTTP 200 and `attempt_completed` leave semantic recovery
unknown. Without an active operation, supply an explicit fresh operation/page
scope. IDs must be independently generated UUID v4 values. Capture return types
are unchanged. Operation callbacks preserve application results and exceptions;
elapsed time is monotonic, and a configured limit is not remaining time.
Completed request/operation owners revoke execution and automatic diagnostic
scope in decorated continuations. Existing decorated request correlation remains
available for compatibility.

Graphs retain native causes, suppressed exceptions and frames on the existing
single delivery worker. They exclude locals, arguments and object dumps. A native
JVM accessor may allocate a stack/suppressed array before SDK retention bounds
apply; overridden accessors can stall that same worker, never capture callers.
Redirected stderr evidence parses retained text and is labelled `runtime_reported`.
Uncaught native evidence bypasses application hooks and uses at most two nodes,
eight frames per node and 4 KiB of optional metadata. Delivery at process exit,
OOM and VM termination is best effort; no new shutdown wait is introduced.

Java does not collect browser timing or CSP observations. Its maintained browser
relays preserve those closed DTOs, validate privacy, attach server identity and
intersect downstream proof. Opted-in requests receive additive `evidence_errors`
and `evidence_capabilities`; legacy response text/status remains compatible.
Native forwarding requires complete chunk-local ACKs before a spool completion
marker is created. Legacy custom boolean forwarders remain accepted but leave
the spool pending; implement `forwardVerified` only with complete acceptance proof.
Spring, Jakarta/Javax servlet and JAX-RS request owners use the same core lease.

Config loader/agent properties support `debugbundle.capture-exception-evidence`,
`debugbundle.capture-recovery-observations`, and `debugbundle.evidence-capabilities`
(a descriptor JSON string), with corresponding `DEBUGBUNDLE_*` environment names.
Spring binds the same properties, with a descriptor map for `evidence-capabilities`.
Review custom redaction hooks for the new graph/observation fields before adoption,
or disable capture. A canonical hook may change any allowed field, including IDs
and type; failed validation budgets or valid unfit replacements are withheld.
Native HTTP uses actual UTF-8 request byte limits and bounded identity-encoded
ACKs. The existing request timeout covers streaming replies; cancellation of
native DNS/connect work remains subject to JVM HTTP implementation behaviour.

## Modules

| Module | Artifact | Purpose |
| --- | --- | --- |
| `debugbundle-java-core` | `com.debugbundle:debugbundle-java-core` | Core SDK facade, client, transports, redaction, probes, and vanilla Java hooks |
| `debugbundle-java-web` | `com.debugbundle:debugbundle-java-web` | Shared web request, response, header, query, and correlation helpers for adapters |
| `debugbundle-java-servlet-jakarta` | `com.debugbundle:debugbundle-java-servlet-jakarta` | `jakarta.servlet` filter, listener, and browser relay servlet for Jakarta EE / non-Spring servlet containers |
| `debugbundle-java-servlet-javax` | `com.debugbundle:debugbundle-java-servlet-javax` | `javax.servlet` filter, listener, and browser relay servlet for classic Java EE / app-server WAR deployments |
| `debugbundle-java-jaxrs-jakarta` | `com.debugbundle:debugbundle-java-jaxrs-jakarta` | `jakarta.ws.rs` request/response filter and fallback exception mapper for Jakarta EE stacks |
| `debugbundle-java-jaxrs-javax` | `com.debugbundle:debugbundle-java-jaxrs-javax` | `javax.ws.rs` request/response filter and fallback exception mapper for RESTEasy-style app-server stacks |
| `debugbundle-java-agent` | `com.debugbundle:debugbundle-java-agent` | Startup javaagent that loads config, initializes the SDK, and installs uncaught-exception and JUL hooks |
| `debugbundle-spring-boot-starter` | `com.debugbundle:debugbundle-spring-boot-starter` | Spring Boot auto-configuration, servlet request capture, MVC exception capture, Logback capture, and browser relay |

## Installation

Spring Boot applications should install the starter:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-spring-boot-starter</artifactId>
  <version>3.1.0</version>
</dependency>
```

```kotlin
implementation("com.debugbundle:debugbundle-spring-boot-starter:3.1.0")
```

Non-Spring Java applications can install the core SDK:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-core</artifactId>
  <version>3.1.0</version>
</dependency>
```

Servlet WAR applications should add exactly one servlet adapter that matches their container namespace:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-servlet-jakarta</artifactId>
  <version>3.1.0</version>
</dependency>
```

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-servlet-javax</artifactId>
  <version>3.1.0</version>
</dependency>
```

JAX-RS applications can add the matching namespace adapter alongside the servlet adapter when they want provider-based request and exception capture:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-jaxrs-jakarta</artifactId>
  <version>3.1.0</version>
</dependency>
```

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-jaxrs-javax</artifactId>
  <version>3.1.0</version>
</dependency>
```

App-server operators that prefer JVM startup injection can add the bootstrap agent:

```text
-javaagent:/opt/debugbundle/debugbundle-java-agent-3.1.0.jar=config=/etc/debugbundle/debugbundle.properties,capture-jul=true,capture-uncaught=true
```

Import the published Java BOM when you install more than one DebugBundle artifact so every module stays on the same version:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.debugbundle</groupId>
      <artifactId>debugbundle-java-parent</artifactId>
      <version>3.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```kotlin
dependencies {
  implementation(platform("com.debugbundle:debugbundle-java-parent:3.1.0"))
    implementation("com.debugbundle:debugbundle-java-core")
    implementation("com.debugbundle:debugbundle-java-servlet-jakarta")
}
```

## Runtime facts

Captured exceptions include safe JVM and process facts without reading environment variables. Runtime memory uses the canonical `rss`, `heap_total`, `heap_used`, `external`, and `peak` fields. Java reports `heap_total` and `heap_used`; metrics the JVM does not expose portably are `null`. JVM name and maximum heap ceiling are namespaced as `framework_extras.jvm_name` and `framework_extras.jvm_max_bytes`.

## Spring Boot Quick Start

```yaml
debugbundle:
  project-token: ${DEBUGBUNDLE_PROJECT_TOKEN}
  service: checkout-api
  environment: production
  project-mode: connected
  relay:
    enabled: true
```

The starter auto-configures:

- Servlet request and response metadata capture
- MVC exception capture while preserving existing `@RestControllerAdvice`
- Logback capture with MDC values
- Spring `TaskDecorator` propagation for `@Async` and application task executors
- Browser relay route at `POST /debugbundle/browser`
- Request ID and `X-DebugBundle-Trace-Id` correlation

If Spring Security protects every route, permit the relay endpoint explicitly:

```java
http.authorizeHttpRequests(authorize -> authorize
        .requestMatchers(HttpMethod.POST, "/debugbundle/browser").permitAll()
        .anyRequest().authenticated());
```

## Vanilla Java Quick Start

```java
import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleConfig;

DebugBundle.init(DebugBundleConfig.builder()
        .projectToken(System.getenv("DEBUGBUNDLE_PROJECT_TOKEN"))
        .service("checkout-api")
        .environment("production")
        .build());

DebugBundle.captureUncaughtExceptions();
DebugBundle.captureJavaUtilLogging();
```

Capture handled errors, logs, messages, and probes explicitly:

```java
DebugBundle.captureException(error);
DebugBundle.captureLog("payment retry failed", LogLevel.WARNING, Map.of("order_id", orderId));
DebugBundle.captureMessage("worker started");
DebugBundle.probe("checkout.cart", Map.of("item_count", cart.items().size()));

// Only during controlled teardown or tests; never on a request/logging thread.
DebugBundle.flush().join();
```

## Servlet WAR Quick Start

Register the DebugBundle listener so each WAR gets its own client instance and service identity:

```xml
<listener>
  <listener-class>com.debugbundle.servlet.jakarta.DebugBundleServletContextListener</listener-class>
</listener>
```

```xml
<listener>
  <listener-class>com.debugbundle.servlet.javax.DebugBundleServletContextListener</listener-class>
</listener>
```

If you prefer programmatic bootstrap, you can still initialize the core SDK yourself before request traffic reaches the filter:

```java
DebugBundle.init(DebugBundleConfig.builder()
        .projectToken(System.getenv("DEBUGBUNDLE_PROJECT_TOKEN"))
        .service("legacy-orders")
        .environment("production")
        .build());
```

For Jakarta Servlet containers, register the Jakarta filter:

```xml
<filter>
  <filter-name>debugbundle</filter-name>
  <filter-class>com.debugbundle.servlet.jakarta.DebugBundleServletFilter</filter-class>
</filter>
<filter-mapping>
  <filter-name>debugbundle</filter-name>
  <url-pattern>/*</url-pattern>
  <dispatcher>REQUEST</dispatcher>
  <dispatcher>ERROR</dispatcher>
</filter-mapping>
```

For classic `javax.servlet` containers, use the `javax` adapter class instead:

```xml
<filter>
  <filter-name>debugbundle</filter-name>
  <filter-class>com.debugbundle.servlet.javax.DebugBundleServletFilter</filter-class>
</filter>
```

The servlet adapters capture request/response metadata, request-scope correlation, handled servlet-chain exceptions, selected request headers, and query parameters. They do not capture request or response bodies by default.

To host the browser relay on standard servlet deployments, register the matching relay servlet at `POST /debugbundle/browser`:

```xml
<servlet>
  <servlet-name>debugbundleRelay</servlet-name>
  <servlet-class>com.debugbundle.servlet.jakarta.DebugBundleRelayServlet</servlet-class>
</servlet>
<servlet-mapping>
  <servlet-name>debugbundleRelay</servlet-name>
  <url-pattern>/debugbundle/browser</url-pattern>
</servlet-mapping>
```

For `javax.servlet` containers, use `com.debugbundle.servlet.javax.DebugBundleRelayServlet`.

## JAX-RS Quick Start

Register the matching DebugBundle filter and exception mapper with your JAX-RS application:

```java
@ApplicationPath("/api")
public class LegacyApplication extends ResourceConfig {
    public LegacyApplication() {
        register(com.debugbundle.jaxrs.jakarta.DebugBundleJaxrsFilter.class);
        register(com.debugbundle.jaxrs.jakarta.DebugBundleExceptionMapper.class);
    }
}
```

For `javax.ws.rs` / RESTEasy deployments, swap the package to `com.debugbundle.jaxrs.javax`.

The JAX-RS adapters capture request/response metadata, preserve request-scope correlation, and provide a fallback `Throwable` mapper that only handles otherwise-unmapped exceptions while preserving existing `WebApplicationException` responses.

## Java Agent Quick Start

The agent is a bootstrap path, not a bytecode-instrumentation product. Its
published JAR is self-contained, initializes the singleton SDK early, and
installs the existing uncaught-exception and `java.util.logging` hooks.

On WildFly and JBoss, the `capture-jul=true` path also covers JBoss LogManager records because the server backend emits JUL-compatible log records with thread and MDC metadata.

Supported agent arguments:

- `config=/path/to/debugbundle.properties`
- `project-token=...`
- `service=...`
- `environment=...`
- `project-mode=connected|local-only`
- `capture-jul=true|false`
- `capture-uncaught=true|false`

The agent uses JVM system properties and environment variables as fallbacks, so the simplest production setup is usually a properties file plus `-javaagent`.

Request and browser-relay capture still use the Spring starter or WAR-level servlet/JAX-RS adapters. The agent is intended for early core/log bootstrap and app-server startup wiring, not dynamic attachment to an already-running JVM.

## WildFly and JBoss

For app servers hosting multiple WARs in one JVM, configure explicit deployment service names so each WAR emits isolated event envelopes:

```properties
debugbundle.project-token=${DEBUGBUNDLE_PROJECT_TOKEN}
debugbundle.environment=production
debugbundle.project-mode=connected
debugbundle.deployments.orders.service=orders-service
debugbundle.deployments.identity.service=identity-service
```

Docker or `standalone.sh` startup can pass the shared config file through `JAVA_OPTS`:

```sh
JAVA_OPTS="$JAVA_OPTS -Ddebugbundle.config=/opt/debugbundle/debugbundle.properties"
exec /opt/jboss/wildfly/bin/standalone.sh -b 0.0.0.0
```

Use `debugbundle-java-servlet-javax` and `debugbundle-java-jaxrs-javax` for Java EE / RESTEasy-style deployments, and the `jakarta` artifacts for Jakarta EE 9+ deployments.

## Delivery Modes

Connected mode sends events to the DebugBundle ingestion API using the server-side project token:

```properties
debugbundle.project-mode=connected
debugbundle.project-token=${DEBUGBUNDLE_PROJECT_TOKEN}
debugbundle.endpoint=https://api.debugbundle.com/v1/events
```

Local-only mode writes atomic event files for `debugbundle process`:

```properties
debugbundle.project-mode=local-only
debugbundle.local-events-dir=.debugbundle/local/events
```

Browser relay delivery follows the same modes. Local-only writes to `.debugbundle/local/events`; connected durable writes a spool record before forwarding; low-latency connected forwarding can skip the durable spool when configured.

By default the relay preserves the browser event's own `service.name` and `service.environment`. Set `debugbundle.relay.service` or `debugbundle.relay.environment` only when you intentionally want the backend relay to override browser-owned identity.

## Zero-Install Fallback

When WAR changes or startup-script edits are not possible yet, emit canonical `debugbundle-ndjson` through existing logs or a sidecar and ingest it with the CLI:

```bash
debugbundle ingest app.debugbundle.ndjson
debugbundle watch app.debugbundle.ndjson --cloud
```

This fallback is not full SDK parity, but it gives operators a safe bridge until the Java SDK can be installed.

## Configuration

| Builder property | Default | Purpose |
| --- | --- | --- |
| `projectToken` | none | Write-only DebugBundle project token. |
| `service` | `unknown-service` | Service name shown on incidents and bundles. |
| `environment` | `development` | Runtime environment such as `production`, `staging`, or `development`. |
| `projectMode` | `connected` | Use `local-only` to write events to `.debugbundle/local/events`. |
| `endpoint` | `https://api.debugbundle.com/v1/events` | Ingestion endpoint for connected mode or self-hosting. |
| `enabled` | `true` | Disable all capture without removing instrumentation. |
| `redactFields` | common sensitive fields | Field names to redact recursively. |
| `logLevel` | `WARNING` | Minimum captured log severity. |
| `sampleRate` | `1.0` | Fraction of events to keep before transport. |
| `batchSize` | `25` | Events per batch before flushing. |
| `flushInterval` | `5 seconds` | Flush interval for buffered events. |
| `requestTimeout` | `5 seconds` | Ingestion connection/request timeout, capped at 60 seconds; nonpositive values use the default. Also supplied to remote config requests. |
| `probesPollInterval` | `60 seconds` | Remote probe config poll interval. |
| `localEventsDir` | `.debugbundle/local/events` | Local file transport directory. |
| `maxProbeLabels` | `50` | Maximum distinct probe labels buffered in memory. |
| `maxProbeEntriesPerLabel` | `10` | Maximum entries retained per probe label. |
| `probeFlushOnError` | `true` | Attach buffered probe data to captured exceptions. |
| `remoteConfigFetcher` | internal HTTP fetcher | Custom remote-config fetcher for tests or advanced routing. |

For the complete configuration-source precedence, relay settings, support labels, safe-startup status semantics, service naming guidance, and verification commands, see `CONFIGURATION.md`.

## Current Scope

This release targets Java 17+, Spring Boot 3.x servlet MVC, standard `jakarta.servlet` and `javax.servlet` app-server deployments, namespace-matched JAX-RS adapters, servlet browser relay servlets, and startup javaagent bootstrap. WebFlux, Micronaut, Quarkus, Dropwizard, gRPC Java, and Spring Boot 2.x remain out of scope.

## Safety Defaults

- SDK failures are caught internally and do not crash the host process.
- Request and response bodies are not captured by default.
- Request headers are allowlisted.
- Sensitive fields are recursively redacted before transport.
- Browser relay requests cannot smuggle server-side credentials.

## Development

Build and test commands run Maven inside a disposable Docker container:

```bash
make test
make verify
make smoke
make smoke-wildfly
```

Override the Java lane when needed:

```bash
make verify JAVA_VERSION=17
make verify JAVA_VERSION=26
```

Supported validation lanes are Java 17, Java 21, Java 25, and Java 26.

## Release

GitHub Actions publishes stable releases to Maven Central through `.github/workflows/release.yml`. The workflow expects a stable project version committed in all published `pom.xml` files and runs `mvn clean deploy -Prelease` with source, javadoc, and signed artifacts. Rerunning the workflow for a fully published version skips Maven deployment while still reconciling the GitHub release and running the published-artifact smoke test.

## Documentation

- Java SDK docs: <https://debugbundle.com/docs/sdks/java>
- SDK overview: <https://debugbundle.com/docs/sdks>
- Browser relay: <https://debugbundle.com/docs/sdks/browser-relay>
- Local config and verification reference: `CONFIGURATION.md`
- Smoke fixtures and commands: `smoke/README.md`
- Repository: <https://github.com/debugbundle/debugbundle-java>

## License

Apache-2.0. See `LICENSE`.

## Delivery acknowledgement and retry limits

The built-in HTTP transport requires the canonical ingestion acknowledgement (`accepted`, `rejected`, and `errors`). Empty responses, unrelated JSON, malformed counts, or invalid rejection indices retain the full batch with backoff. Valid acknowledgements remove accepted and terminally rejected events and retry only the indexed retryable rejections; an all-rejected batch does not advance `lastEventAt`.

File transports and explicitly supplied custom/legacy transports retain their documented bodyless success fallback. A custom transport that returns acknowledgement fields must return the complete canonical shape. Retry hints (delay seconds or HTTP dates) are bounded to five minutes and measured from response receipt, including partial/protocol acknowledgements and retryable server failures. Without a server hint, existing retry timing is preserved. Failures remain contained within the SDK's existing delivery path.

## Probe checkpoint guidance

Application instrumentation supplies probe labels and bounded data. Deploy a new
checkpoint before activating it. Remote activation affects future matching calls
when policy permits; it does not inject code or recover unretained measurements.
Built-in runtime/error/device collectors keep their documented SDK settings.

Use fixed labels and allowlisted technical states: `media.preview.checkpoint`
(stage/input kind/attempt), `auth.session.checkpoint` (observed session presence
and result), `job.dependency.checkpoint` (named dependency/attempt/result), or
`cache.preview.checkpoint` (hit/miss/coarse age bucket). Omit customer identifiers,
tokens/cookies, cache keys, URLs and raw payloads. HTTP 200 or attempt completion
alone does not establish semantic recovery.

Empty probe data means no matching retained observation for the selected sample
window. It does not confirm activation delivery to every SDK or execution of a
label. Check the deployed SDK/wrapper version, instrumentation, scope, expiry,
policy, sampling and delivery receipts. See the [probe recipes](https://debugbundle.com/docs/probes#bounded-checkpoint-recipes).
