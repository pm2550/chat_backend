# Multi-stage build for chat-app-backend.
# Build stage uses JDK 21 (matches host) so we don't have to maintain a separate
# JDK pin for the Hibernate ORM bytecode generator.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd -r chatapp && useradd -r -g chatapp chatapp

COPY --from=build /app/target/chat-app-backend-*.jar app.jar

RUN mkdir -p /app/uploads/avatars /app/uploads/messages && \
    chown -R chatapp:chatapp /app

USER chatapp

EXPOSE 8080

# Note: actuator path is /actuator/health (no /api context-path).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
