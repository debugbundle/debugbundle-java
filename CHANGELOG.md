# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

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
