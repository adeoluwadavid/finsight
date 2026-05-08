# Stage 1 — Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

COPY pom.xml .
COPY auth-service/pom.xml auth-service/
COPY document-service/pom.xml document-service/
COPY conversation-service/pom.xml conversation-service/
COPY api-gateway/pom.xml api-gateway/

RUN mvn dependency:go-offline -pl conversation-service -am -q

COPY conversation-service/src conversation-service/src
RUN mvn package -pl conversation-service -am -DskipTests -q

# Stage 2 — Run
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd -r finsight && useradd -r -g finsight finsight
USER finsight

COPY --from=builder /app/conversation-service/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]