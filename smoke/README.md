# Smoke Fixtures

This directory contains the Java SDK smoke and compatibility fixtures.

## App-driven core smoke

`app-driven-core/` is the release smoke project. It installs the published BOM and `debugbundle-java-core`, starts a mock ingestion server, captures a real exception through the public SDK API, flushes, and asserts the received event envelope.

Run it against the local workspace version:

```bash
bash ./smoke/run-app-driven-smoke.sh
```

Run it against a published Maven Central version:

```bash
bash ./smoke/run-app-driven-smoke.sh --published 2.0.0
```

## WildFly and JBoss fixtures

`wildfly-multiwar/` and `wildfly-multiwar-javax/` provide app-server smoke fixtures for one-JVM multi-WAR deployments. They exist to verify per-deployment service identity, servlet adapter wiring, and startup bootstrap examples across Jakarta and Javax namespace lanes.

Run both lanes with:

```bash
make smoke-wildfly JAVA_VERSION=21
```

The smoke builds the SDK and four WARs from the checkout, starts WildFly 36
(Jakarta) and WildFly 23 (Javax) with the packaged javaagent, requests both
deployments, and verifies that local event files retain distinct deployment
service identities.
