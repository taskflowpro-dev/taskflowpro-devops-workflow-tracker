# =========================
# Build stage
# =========================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven files
COPY backend/pom.xml backend/mvnw ./
COPY backend/.mvn ./.mvn

RUN chmod +x mvnw

# Download dependencies
RUN ./mvnw dependency:go-offline

# Copy backend source
COPY backend/src ./src

# Include the shared repository migrations in the application classpath.
COPY database/migrations ./src/main/resources/db/migration

# Build the Spring Boot application
RUN ./mvnw -B clean package -DskipTests


# =========================
# Runtime stage
# =========================
FROM eclipse-temurin:21-jre

WORKDIR /app/backend

# Copy generated JAR
COPY --from=build /app/target/*.jar app.jar

# Flyway migrations are run from the packaged application, so no host path is
# required at runtime.

RUN useradd --system --uid 10001 --create-home appuser
USER 10001

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
