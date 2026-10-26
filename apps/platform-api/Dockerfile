FROM gradle:8.14.3-jdk21-alpine AS build
WORKDIR /workspace
COPY settings.gradle build.gradle gradle.lockfile ./
COPY src ./src
RUN gradle clean bootJar --no-daemon

FROM eclipse-temurin:21.0.8_9-jre-alpine
RUN addgroup -S ontoflow && adduser -S ontoflow -G ontoflow
WORKDIR /app
COPY --from=build /workspace/build/libs/platform-api-0.1.0.jar app.jar
USER ontoflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
