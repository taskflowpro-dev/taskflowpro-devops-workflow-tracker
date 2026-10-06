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

# Build the Spring Boot application
RUN ./mvnw clean package -DskipTests


# =========================
# Runtime stage
# =========================
FROM eclipse-temurin:21-jre

WORKDIR /app/backend

# Copy generated JAR
COPY --from=build /app/target/*.jar app.jar

# Copy Flyway migrations
COPY database/migrations ../database/migrations

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]