# debugbundle-java

Java SDK, servlet adapters, and Spring Boot starter for DebugBundle.

![Maven Central](https://img.shields.io/maven-central/v/com.debugbundle/debugbundle-java-core?label=maven)
![CI](https://img.shields.io/github/actions/workflow/status/debugbundle/debugbundle-java/ci.yml?branch=main&label=ci)
![License](https://img.shields.io/badge/license-AGPL--3.0--only-blue)

Use this repository to capture Java backend exceptions, request metadata, Logback or `java.util.logging` records, runtime context, and probe data. The SDK has a framework-neutral core, shared web helpers, servlet and JAX-RS adapters for Spring and non-Spring web applications, and a startup javaagent for app-server bootstrap.

Requires Java 17 or newer.

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
  <version>0.1.2</version>
</dependency>
```

```kotlin
implementation("com.debugbundle:debugbundle-spring-boot-starter:0.1.2")
```

Non-Spring Java applications can install the core SDK:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-core</artifactId>
  <version>0.1.2</version>
</dependency>
```

Servlet WAR applications should add exactly one servlet adapter that matches their container namespace:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-servlet-jakarta</artifactId>
  <version>0.1.2</version>
</dependency>
```

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-servlet-javax</artifactId>
  <version>0.1.2</version>
</dependency>
```

JAX-RS applications can add the matching namespace adapter alongside the servlet adapter when they want provider-based request and exception capture:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-jaxrs-jakarta</artifactId>
  <version>0.1.2</version>
</dependency>
```

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-jaxrs-javax</artifactId>
  <version>0.1.2</version>
</dependency>
```

App-server operators that prefer JVM startup injection can add the bootstrap agent:

```text
-javaagent:/opt/debugbundle/debugbundle-java-agent-0.1.2.jar=config=/etc/debugbundle/debugbundle.properties,capture-jul=true,capture-uncaught=true
```

Import the published Java BOM when you install more than one DebugBundle artifact so every module stays on the same version:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.debugbundle</groupId>
      <artifactId>debugbundle-java-parent</artifactId>
      <version>0.1.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```kotlin
dependencies {
    implementation(platform("com.debugbundle:debugbundle-java-parent:0.1.2"))
    implementation("com.debugbundle:debugbundle-java-core")
    implementation("com.debugbundle:debugbundle-java-servlet-jakarta")
}
```

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

The agent is a bootstrap path, not a bytecode-instrumentation product. It initializes the singleton SDK early and installs the existing uncaught-exception and `java.util.logging` hooks.

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
| `requestTimeout` | `5 seconds` | HTTP transport timeout. |
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
```

Override the Java lane when needed:

```bash
make verify JAVA_VERSION=17
make verify JAVA_VERSION=26
```

Supported validation lanes are Java 17, Java 21, Java 25, and Java 26.

## Release

GitHub Actions publishes stable releases to Maven Central through `.github/workflows/release.yml`. The workflow expects a stable project version committed in all published `pom.xml` files and runs `mvn clean deploy -Prelease` with source, javadoc, and signed artifacts.

## Documentation

- Java SDK docs: <https://debugbundle.com/docs/sdks/java>
- SDK overview: <https://debugbundle.com/docs/sdks>
- Browser relay: <https://debugbundle.com/docs/sdks/browser-relay>
- Local config and verification reference: `CONFIGURATION.md`
- Smoke fixtures and commands: `smoke/README.md`
- Repository: <https://github.com/debugbundle/debugbundle-java>

## License

AGPL-3.0-only. See `LICENSE`.
