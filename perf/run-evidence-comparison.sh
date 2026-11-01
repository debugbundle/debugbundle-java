#!/bin/sh
set -eu
mkdir -p .tmp/evidence-performance
mvn -q -pl debugbundle-java-core -am -DskipTests compile
mvn -q -f debugbundle-java-core/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/debugbundle-evidence-classpath
classpath="debugbundle-java-core/target/classes:$(cat /tmp/debugbundle-evidence-classpath)"
javac -cp "$classpath" -d .tmp/evidence-performance perf/EvidenceCapture.java
java -cp ".tmp/evidence-performance:$classpath" com.debugbundle.sdk.EvidenceCapture
