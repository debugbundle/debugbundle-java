# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

## [2.0.0] - 2026-09-21

### Security

- Enforce native `telemetry-privacy-v1` checks before context and probe retention, after `beforeSend`, and before buffering, transport, and browser relay. Custom keys extend the mandatory baseline.

### Changed

- Add source verification lanes for Java 27 and Spring Boot 4.1 while retaining older Java and Spring Boot 3 compatibility.

## [1.4.0] - 2026-09-12

### Changed

- License first-party SDK code under Apache-2.0 and ship consistent package licensing metadata and license text.

## 1.3.1 - 2026-08-27

- Emit Java runtime memory using the canonical `rss`, `heap_total`, `heap_used`, `external`, and `peak` fields, and keep JVM-specific facts under `framework_extras` so captured exceptions pass strict ingestion validation.
- Validate `beforeSend` runtime mutations against the canonical runtime-memory shape before queueing them.

## 1.3.0 - 2026-07-28

- Added the universal `DebugBundleBeforeSend` event hook and canonical object wrapping for scalar/list probe values.
- Reconcile connected ingestion acknowledgements per event, retaining only retryable rejections and withholding delivery health when no event was accepted.
- Added executable Jakarta and Javax WildFly multi-WAR release lanes and a self-contained javaagent artifact while retaining explicit adapter requirements for request capture.
- Enforce at least 80% coverage for every production Java source file and run the gate across the supported Java 21 and 26 release lanes.

## 1.2.0 - 2026-07-17

- Corrected the semantic release line for browser-relay analytics support. Relay handlers accept credential-free `analytics_event` envelopes while preserving only the required analytics correlation fields and stripping browser-supplied credentials.

## 1.1.2 - 2026-07-17

- Added browser-relay support for `analytics_event` envelopes, preserving only the analytics correlation fields needed for aggregation while continuing to strip browser-supplied credentials.

## 1.1.1 - 2026-06-19

- Normalized canonical event-envelope emission so custom app context now stays in envelope `context`, request events avoid legacy payload extras, and installed projects stop tripping malformed ingestion rejects after upgrade.

## 1.1.0 - 2026-06-08

- Added path-scoped immediate client-error incident promotion support in the shared remote capture-policy handling so explicitly configured `4xx` routes can emit standalone `request_event` incident signals without widening the status globally.
- Preserved `5xx` handling while keeping unpromoted client-error request telemetry context-only under repeated traffic across the Java SDK family, smoke fixtures, and published-install guidance.

## 1.0.0 - 2026-05-31

- Promoted the Java SDK family to stable `1.0.0` across the core client, servlet and JAX-RS adapters, Spring Boot starter, javaagent, release smoke fixtures, and published-install documentation.

## 0.1.3 - 2026-05-29

- Added `OPTIONS /debugbundle/browser` preflight support and matching CORS headers across the shared relay implementation, servlet adapters, and Spring Boot handler so split-host browser relay works for explicitly allowed origins.

## 0.1.2 - 2026-05-27

- Hardened the Maven Central release workflow with published-state validation so full-family releases skip safely when the target version already exists and fail closed on partial publication.
- Added a Java SDK configuration reference covering support labels, config precedence, dependency alignment, relay behavior, service naming, startup status semantics, and app-driven verification.
- Added an app-driven published-artifact smoke path and refreshed the smoke fixtures, release docs, and embedded SDK metadata for the aligned `0.1.2` release.

## 0.1.1 - 2026-05-25

- Expanded the Java SDK from Spring Boot-only coverage to a framework-neutral core with Spring Boot, servlet, JAX-RS, WildFly/JBoss, and javaagent bootstrap modules.
- Added Jakarta and Javax servlet adapters with request capture, per-WAR service isolation, browser relay servlets, and local/connected transport support.
- Added Jakarta and Javax JAX-RS adapters with request/response capture, fallback exception mappers, and route/resource metadata when available.
- Added shared browser relay compliance coverage, including same-origin validation, credential stripping, durable spool behavior, server-side project-token forwarding, and vendored fixture parity.
- Added startup javaagent bootstrap for config loading, uncaught exceptions, and JUL/JBoss LogManager-compatible logging.
- Added WildFly/JBoss multi-WAR smoke fixtures and updated public Java, setup, and SDK index documentation.

## 0.1.0 - 2026-05-21

- Initial repository scaffold.
- Initial core Java SDK facade, buffered event client, file/HTTP transport selection, and redaction pipeline.
- Initial Spring Boot starter scaffold with servlet request and exception capture wiring.
