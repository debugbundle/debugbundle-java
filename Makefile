SHELL := /bin/sh

JAVA_VERSION ?= 21
SPRING_BOOT_VERSION ?= 3.5.0
MAVEN_IMAGE ?= maven:3.9.11-eclipse-temurin-$(JAVA_VERSION)
WORKDIR := /workspace

ifeq ($(JAVA_VERSION),26)
MAVEN_IMAGE := eclipse-temurin:26-jdk
MAVEN_COMMAND := sh -lc 'apt-get update >/dev/null && apt-get install -y --no-install-recommends maven >/dev/null && mvn "$$@"' mvn
else ifeq ($(JAVA_VERSION),27)
MAVEN_IMAGE := amazoncorretto:27
MAVEN_COMMAND := sh -c 'dnf install -y -q maven >/dev/null && mvn "$$@"' mvn
else
MAVEN_COMMAND := mvn
endif

MAVEN_RUN = docker run --rm -t \
	-v "$(PWD):$(WORKDIR)" \
	-w "$(WORKDIR)" \
	$(MAVEN_IMAGE)

.PHONY: test verify smoke smoke-wildfly shell

test:
	$(MAVEN_RUN) $(MAVEN_COMMAND) -Dspring.boot.version=$(SPRING_BOOT_VERSION) clean test

verify:
	$(MAVEN_RUN) $(MAVEN_COMMAND) -Dspring.boot.version=$(SPRING_BOOT_VERSION) clean verify

smoke:
ifeq ($(JAVA_VERSION),26)
	$(MAVEN_RUN) sh -lc 'apt-get update >/dev/null && apt-get install -y --no-install-recommends maven >/dev/null && bash ./smoke/run-app-driven-smoke.sh'
else ifeq ($(JAVA_VERSION),27)
	$(MAVEN_RUN) sh -c 'dnf install -y -q maven >/dev/null && bash ./smoke/run-app-driven-smoke.sh'
else
	$(MAVEN_RUN) sh -lc 'bash ./smoke/run-app-driven-smoke.sh'
endif

smoke-wildfly:
	$(MAVEN_RUN) sh -lc 'mvn -q -DskipTests install && mvn -q -f smoke/wildfly-multiwar/orders/pom.xml package && mvn -q -f smoke/wildfly-multiwar/identity/pom.xml package && mvn -q -f smoke/wildfly-multiwar-javax/orders-legacy/pom.xml package && mvn -q -f smoke/wildfly-multiwar-javax/identity-legacy/pom.xml package'
	./smoke/run-wildfly-multiwar-smoke.sh

shell:
	$(MAVEN_RUN) sh
