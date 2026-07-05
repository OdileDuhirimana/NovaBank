# Build stage
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml ./
COPY src ./src

RUN mvn -DskipTests clean package

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

# The Spring Boot executable jar now carries an "exec" classifier (see pom.xml's
# spring-boot-maven-plugin configuration) so the plain nova-bank-core-*.jar stays a normal,
# flat-classpath artifact for tooling like the maven-failsafe-plugin's integration tests.
COPY --from=build /build/target/nova-bank-core-0.0.1-SNAPSHOT-exec.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
