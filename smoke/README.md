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
bash ./smoke/run-app-driven-smoke.sh --published 0.1.3
```

## WildFly and JBoss fixtures

`wildfly-multiwar/` and `wildfly-multiwar-javax/` provide app-server smoke fixtures for one-JVM multi-WAR deployments. They exist to verify per-deployment service identity, servlet adapter wiring, and startup bootstrap examples across Jakarta and Javax namespace lanes.
