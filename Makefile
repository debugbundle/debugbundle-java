SHELL := /bin/sh
.DEFAULT_GOAL := test

JAVA_VERSION ?= 21
SPRING_BOOT_VERSION ?= 3.5.0
MAVEN_IMAGE ?= maven:3.9.11-eclipse-temurin-$(JAVA_VERSION)
WORKDIR := /workspace

MAVEN_COMMAND := mvn
ifeq ($(origin MAVEN_IMAGE),file)
ifeq ($(JAVA_VERSION),26)
MAVEN_IMAGE := debugbundle-java-maven:3.9.11-jdk26
JAVA_BASE_IMAGE := eclipse-temurin:26-jdk
else ifeq ($(JAVA_VERSION),27)
MAVEN_IMAGE := debugbundle-java-maven:3.9.11-jdk27
JAVA_BASE_IMAGE := amazoncorretto:27
endif
endif

.PHONY: maven-runtime-image
maven-runtime-image:
ifneq ($(JAVA_BASE_IMAGE),)
	docker build --build-arg JAVA_BASE_IMAGE=$(JAVA_BASE_IMAGE) -f tooling/maven-runtime.Dockerfile -t $(MAVEN_IMAGE) .
endif

MAVEN_RUN = docker run --rm -t \
	-v "$(CURDIR):$(WORKDIR)" \
	-v debugbundle-java-maven-cache:/root/.m2 \
	-w "$(WORKDIR)" \
	$(MAVEN_IMAGE)

.PHONY: test test-core-focused verify smoke smoke-wildfly shell

test: maven-runtime-image
	$(MAVEN_RUN) $(MAVEN_COMMAND) -Dspring.boot.version=$(SPRING_BOOT_VERSION) clean test

TEST_CLASS ?= DebugBundleCaptureSafetyTest
test-core-focused: maven-runtime-image
	$(MAVEN_RUN) $(MAVEN_COMMAND) -Dspring.boot.version=$(SPRING_BOOT_VERSION) -pl debugbundle-java-core -Dtest='$(TEST_CLASS)' test

test-web-focused: maven-runtime-image
	$(MAVEN_RUN) $(MAVEN_COMMAND) -Dspring.boot.version=$(SPRING_BOOT_VERSION) -pl debugbundle-java-web -am -Dtest='$(TEST_CLASS)' -Dsurefire.failIfNoSpecifiedTests=false test

coverage-only: maven-runtime-image
	$(MAVEN_RUN) $(MAVEN_COMMAND) -Dspring.boot.version=$(SPRING_BOOT_VERSION) clean test org.jacoco:jacoco-maven-plugin:0.8.15:report@write-coverage-report org.jacoco:jacoco-maven-plugin:0.8.15:check@enforce-source-file-coverage

artifact-network-check: maven-runtime-image
	$(MAVEN_RUN) sh -lc 'curl -fsS -o /dev/null -w 'HTTP %{http_code}\n' --max-time 15 https://repo.maven.apache.org/maven2/org/apache/maven/shared/file-management/3.1.0/file-management-3.1.0.pom'

artifact-resolution-check: maven-runtime-image
	$(MAVEN_RUN) $(MAVEN_COMMAND) -U -e -DskipTests -pl debugbundle-java-core -am package

verify: maven-runtime-image
	$(MAVEN_RUN) $(MAVEN_COMMAND) -Dspring.boot.version=$(SPRING_BOOT_VERSION) clean verify

smoke: maven-runtime-image
	$(MAVEN_RUN) sh -c 'bash ./smoke/run-app-driven-smoke.sh'

smoke-wildfly:
	$(MAVEN_RUN) sh -c 'mvn -q -DskipTests -Djacoco.skip=true install && mvn -q -f smoke/wildfly-multiwar/orders/pom.xml package && mvn -q -f smoke/wildfly-multiwar/identity/pom.xml package && mvn -q -f smoke/wildfly-multiwar-javax/orders-legacy/pom.xml package && mvn -q -f smoke/wildfly-multiwar-javax/identity-legacy/pom.xml package'
	./smoke/run-wildfly-multiwar-smoke.sh

shell:
	$(MAVEN_RUN) sh

.PHONY: evidence-performance
evidence-performance: maven-runtime-image
	$(MAVEN_RUN) sh perf/run-evidence-comparison.sh

# Mirror maintained CI runtimes sequentially; they share module target directories.
evidence-runtime-matrix:
	mkdir -p .tmp/evidence-runtime
	$(MAKE) verify JAVA_VERSION=21 > .tmp/evidence-runtime/verify-21.log 2>&1
	$(MAKE) smoke JAVA_VERSION=21 > .tmp/evidence-runtime/installed-21.log 2>&1
	$(MAKE) verify JAVA_VERSION=25 > .tmp/evidence-runtime/verify-25.log 2>&1
	$(MAKE) smoke JAVA_VERSION=25 > .tmp/evidence-runtime/installed-25.log 2>&1
	$(MAKE) verify JAVA_VERSION=26 > .tmp/evidence-runtime/verify-26.log 2>&1
	$(MAKE) smoke JAVA_VERSION=26 > .tmp/evidence-runtime/installed-26.log 2>&1
	$(MAKE) verify JAVA_VERSION=27 > .tmp/evidence-runtime/verify-27.log 2>&1
	$(MAKE) smoke JAVA_VERSION=27 > .tmp/evidence-runtime/installed-27.log 2>&1
	$(MAKE) verify JAVA_VERSION=27 SPRING_BOOT_VERSION=4.1.1 > .tmp/evidence-runtime/spring-4.log 2>&1

# Resume only the remaining lanes after an external Maven/DNS interruption.
evidence-runtime-tail:
	mkdir -p .tmp/evidence-runtime
	$(MAKE) smoke JAVA_VERSION=27 > .tmp/evidence-runtime/installed-27-retry.log 2>&1
	$(MAKE) verify JAVA_VERSION=27 SPRING_BOOT_VERSION=4.1.1 > .tmp/evidence-runtime/spring-4.log 2>&1
