SHELL := /bin/sh

JAVA_VERSION ?= 21
MAVEN_IMAGE ?= maven:3.9.11-eclipse-temurin-$(JAVA_VERSION)
WORKDIR := /workspace

ifeq ($(JAVA_VERSION),26)
MAVEN_IMAGE := eclipse-temurin:26-jdk
MAVEN_COMMAND := sh -lc 'apt-get update >/dev/null && apt-get install -y --no-install-recommends maven >/dev/null && mvn "$$@"' mvn
else
MAVEN_COMMAND := mvn
endif

MAVEN_RUN = docker run --rm -t \
	-v "$(PWD):$(WORKDIR)" \
	-w "$(WORKDIR)" \
	$(MAVEN_IMAGE)

.PHONY: test verify shell

test:
	$(MAVEN_RUN) $(MAVEN_COMMAND) clean test

verify:
	$(MAVEN_RUN) $(MAVEN_COMMAND) clean verify

shell:
	$(MAVEN_RUN) sh
