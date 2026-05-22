# debugbundle-java

Java SDK and Spring Boot starter for DebugBundle.

![Maven Central](https://img.shields.io/maven-central/v/com.debugbundle/debugbundle-java-core?label=maven)
![CI](https://img.shields.io/github/actions/workflow/status/debugbundle/debugbundle-java/ci.yml?branch=main&label=ci)
![License](https://img.shields.io/badge/license-AGPL--3.0--only-blue)

Use this repository to capture Java backend exceptions, request metadata, Logback or `java.util.logging` records, runtime context, and probe data. The primary integration is Spring Boot 3.x servlet MVC.

Requires Java 17 or newer.

## Modules

| Module | Artifact | Purpose |
| --- | --- | --- |
| `debugbundle-java-core` | `com.debugbundle:debugbundle-java-core` | Core SDK facade, client, transports, redaction, probes, and vanilla Java hooks |
| `debugbundle-spring-boot-starter` | `com.debugbundle:debugbundle-spring-boot-starter` | Spring Boot auto-configuration, servlet request capture, MVC exception capture, Logback capture, and browser relay |

## Installation

Spring Boot applications should install the starter:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.debugbundle:debugbundle-spring-boot-starter:0.1.0")
```

Non-Spring Java applications can install the core SDK:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-core</artifactId>
  <version>0.1.0</version>
</dependency>
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

## Current Scope

This release targets Java 17+ and Spring Boot 3.x servlet MVC. It does not include WebFlux, standalone Jakarta Servlet containers, Micronaut, Quarkus, Dropwizard, gRPC Java, Spring Boot 2.x, `javax.servlet`, or Java agent bytecode instrumentation.

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
- Repository: <https://github.com/debugbundle/debugbundle-java>

## License

AGPL-3.0-only. See `LICENSE`.
