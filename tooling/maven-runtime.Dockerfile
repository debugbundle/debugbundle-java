ARG JAVA_BASE_IMAGE=amazoncorretto:27
FROM maven:3.9.11-eclipse-temurin-21 AS maven
FROM ${JAVA_BASE_IMAGE}
COPY --from=maven /usr/share/maven /opt/maven
ENV MAVEN_HOME=/opt/maven
ENV PATH=/opt/maven/bin:${PATH}
