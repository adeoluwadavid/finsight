# Stage 1 — Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy all POMs first for dependency caching
COPY pom.xml .
COPY auth-service/pom.xml auth-service/
COPY document-service/pom.xml document-service/
COPY conversation-service/pom.xml conversation-service/
COPY api-gateway/pom.xml api-gateway/

RUN mvn dependency:go-offline -pl auth-service -am -q

COPY auth-service/src auth-service/src
RUN mvn package -pl auth-service -am -DskipTests -q

# Stage 2 — Run
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd -r finsight && useradd -r -g finsight finsight
USER finsight

COPY --from=builder /app/auth-service/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]