FROM eclipse-temurin:17-jre-jammy

ARG WILDFLY_VERSION=23.0.2.Final

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system jboss \
    && useradd --system --gid jboss --home-dir /opt/jboss --shell /usr/sbin/nologin jboss \
    && mkdir -p /opt/jboss \
    && curl -fsSL "https://download.jboss.org/wildfly/${WILDFLY_VERSION}/wildfly-${WILDFLY_VERSION}.tar.gz" -o /tmp/wildfly.tgz \
    && tar -xzf /tmp/wildfly.tgz -C /opt/jboss \
    && mv "/opt/jboss/wildfly-${WILDFLY_VERSION}" /opt/jboss/wildfly \
    && rm /tmp/wildfly.tgz \
    && chown -R jboss:jboss /opt/jboss

ENV JBOSS_HOME=/opt/jboss/wildfly
WORKDIR /opt/jboss/wildfly

COPY --chown=jboss:jboss debugbundle-java-agent/target/debugbundle-java-agent-1.3.1.jar /opt/debugbundle/debugbundle-java-agent.jar
COPY --chown=jboss:jboss smoke/wildfly-multiwar-javax/debugbundle.properties /opt/debugbundle/debugbundle.properties
COPY --chown=jboss:jboss smoke/wildfly-multiwar-javax/orders-legacy/target/orders-legacy.war standalone/deployments/orders-legacy.war
COPY --chown=jboss:jboss smoke/wildfly-multiwar-javax/identity-legacy/target/identity-legacy.war standalone/deployments/identity-legacy.war

ENV JAVA_OPTS="-javaagent:/opt/debugbundle/debugbundle-java-agent.jar=config=/opt/debugbundle/debugbundle.properties,capture-jul=false,capture-uncaught=true -Ddebugbundle.config=/opt/debugbundle/debugbundle.properties"

USER jboss

EXPOSE 8080 9990

CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
