# debugbundle-java

DebugBundle SDK for Java and Spring Boot.

## Status

This repository contains the release-ready Java core SDK plus the Spring Boot starter:

- a standalone multi-module Maven repository,
- the core Java facade and client contract,
- Java-idiomatic singleton and instance-based API entrypoints,
- buffered event capture with explicit flush semantics,
- HTTP transport for connected environments and file transport for local/development environments,
- request/log/exception/message envelope generation with runtime facts,
- recursive redaction for configured sensitive fields,
- explicit no-throw degradation and retry backoff handling for failed transport attempts,
- remote config polling with local capture-policy enforcement for logs and request events,
- polling-based remote probe activation with standalone `probe_event` shipping,
- request-scoped trigger-token activation and probe correlation,
- vanilla Java hooks for uncaught exceptions and `java.util.logging`,
- a Spring Boot starter with auto-configuration,
- servlet request, exception, browser relay, and Logback logging integration,
- Docker-backed core and starter test coverage,
- Maven Central metadata, source/javadoc jar generation, and release signing profile.

This first release targets Java 17+ and Spring Boot 3.x servlet MVC. It does **not** include WebFlux, standalone Jakarta Servlet containers, Micronaut, Quarkus, Dropwizard, gRPC Java, Spring Boot 2.x, `javax.servlet`, or Java agent bytecode instrumentation.

## Installation

Spring Boot applications should install the starter:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```kotlin
implementation("com.debugbundle:debugbundle-spring-boot-starter:0.1.0-SNAPSHOT")
```

Non-Spring Java applications can install the core SDK directly:

```xml
<dependency>
  <groupId>com.debugbundle</groupId>
  <artifactId>debugbundle-java-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

### Vanilla Java

```java
import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleConfig;

DebugBundle.init(DebugBundleConfig.builder()
        .projectToken("dbundle_proj_test")
        .service("checkout-api")
        .environment("production")
        .build());

DebugBundle.captureException(new RuntimeException("boom"));
DebugBundle.flush().join();
```

### Spring Boot

```yaml
debugbundle:
  project-token: ${DEBUGBUNDLE_TOKEN}
  service: patients-api
  environment: production
  project-mode: connected
  relay:
    enabled: true
```

The starter auto-configures:

- a servlet filter for request/response metadata,
- an MVC exception resolver that preserves existing `@RestControllerAdvice`,
- Logback capture with MDC values,
- the browser relay route at `POST /debugbundle/browser`,
- request ID and `X-DebugBundle-Trace-Id` correlation.

If Spring Security protects every route, permit the relay endpoint explicitly:

```java
http.authorizeHttpRequests(authorize -> authorize
        .requestMatchers(HttpMethod.POST, "/debugbundle/browser").permitAll()
        .anyRequest().authenticated());
```

## Modules

| Module | Purpose |
| --- | --- |
| `debugbundle-java-core` | Core SDK API, config model, client contract, and vanilla Java hooks |
| `debugbundle-spring-boot-starter` | Spring Boot auto-configuration plus servlet integration |

## Development

This repository is published at <https://github.com/debugbundle/debugbundle-java>.

Build and test commands:

```bash
make test
make verify
```

These targets run Maven inside a disposable Docker container (`maven:3.9.11-eclipse-temurin-<JAVA_VERSION>`) so the host machine does not need a local JDK or Maven install. Java 26 uses the current `eclipse-temurin:26-jdk` image and installs Maven inside the disposable container until the Maven image line publishes a Java 26 variant.

Override `JAVA_VERSION` when needed:

```bash
make test JAVA_VERSION=17
make verify JAVA_VERSION=26
```

Supported validation lanes are Java 17, Java 21, Java 25, and Java 26.

## Privacy Defaults

The SDK is conservative by default:

- request and response bodies are not captured,
- request headers are allowlisted,
- sensitive fields are recursively redacted before transport,
- browser relay requests cannot smuggle server credentials,
- SDK failures are swallowed internally and never throw into host application code.

Enable payload capture only with an explicit application-owned review for PHI, PCI, or other regulated data.

## License

AGPL-3.0-only
