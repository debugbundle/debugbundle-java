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

USER jboss

EXPOSE 8080 9990

CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]