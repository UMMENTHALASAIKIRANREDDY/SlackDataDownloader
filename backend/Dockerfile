# ---- Build stage: compile the Spring Boot fat jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first (only re-runs when pom.xml changes)
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline

# Build
COPY src ./src
RUN mvn -B -ntp -q clean package -DskipTests

# ---- Runtime stage: small JRE image ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built jar (artifactId-version.jar)
COPY --from=build /build/target/*.jar app.jar

# Exports are written here; mount a volume in docker-compose
RUN mkdir -p /app/exports
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
