FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src/main ./src/main
RUN chmod +x gradlew && ./gradlew --no-daemon --max-workers=2 bootJar

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system --gid 10001 shelter \
    && useradd --system --uid 10001 --gid shelter --no-create-home shelter
WORKDIR /app
COPY --from=build --chown=shelter:shelter /build/build/libs/shelter-connect-api.jar ./app.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
# Favor startup latency on the development service's 0.1 CPU allocation.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-XX:TieredStopAtLevel=1", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
