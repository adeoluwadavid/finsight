# Stage 1 — Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

COPY pom.xml .
COPY auth-service/pom.xml auth-service/
COPY document-service/pom.xml document-service/
COPY conversation-service/pom.xml conversation-service/
COPY api-gateway/pom.xml api-gateway/

RUN mvn dependency:go-offline -pl document-service -am -q

COPY document-service/src document-service/src
RUN mvn package -pl document-service -am -DskipTests -q

# Stage 2 — Run
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd -r finsight && useradd -r -g finsight finsight && \
    mkdir -p /app/uploads && chown finsight:finsight /app/uploads
USER finsight

COPY --from=builder /app/document-service/target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]