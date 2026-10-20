# Build natively on the host platform (the jar is platform-independent); only the small runtime stage is built for the target
# platform, so `buildx --platform linux/amd64` on an Apple-silicon Mac does not run Maven under emulation.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B -q
COPY src ./src
RUN mvn clean package -DskipTests -B -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/voice-insights-azureapi-1.0.0.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
