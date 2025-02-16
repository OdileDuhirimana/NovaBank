# Build stage
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml ./
COPY src ./src

RUN mvn -DskipTests clean package

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /build/target/nova-bank-core-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
