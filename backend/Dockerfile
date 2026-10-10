FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
# Pinned, source-independent dependency cache. Even a cold Render builder needs
# no requests to Maven Central or the plugin portal. Refresh via Dockerfile.dependencies.
ADD --checksum=sha256:8c61e4ad7ab1c8e94144f40295f276f2b35a03fa2353ed9b535b29b0eaf49df9 \
    https://github.com/HANN-Creator/shelter-connect/releases/download/deployment-gradle-cache-20261010-abc5a6f/gradle-cache.tar.gz /tmp/gradle-cache.tar.gz
ENV GRADLE_USER_HOME=/opt/gradle-cache/gradle-home
RUN --network=none mkdir -p /opt/gradle-cache \
    && tar -xzf /tmp/gradle-cache.tar.gz -C /opt/gradle-cache \
    && rm /tmp/gradle-cache.tar.gz \
    && sha256sum -c /opt/gradle-cache/inputs.sha256 \
    && chmod +x gradlew
COPY src/main ./src/main
COPY scripts/styled_dog ./scripts/styled_dog
COPY asset-styles ./asset-styles
RUN --network=none ./gradlew --no-daemon --max-workers=2 --offline bootJar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*
COPY motion-requirements.txt /tmp/motion-requirements.txt
RUN python3 -m venv /opt/motion \
    && /opt/motion/bin/pip install --no-cache-dir -r /tmp/motion-requirements.txt \
    && rm /tmp/motion-requirements.txt
RUN groupadd --system --gid 10001 shelter \
    && useradd --system --uid 10001 --gid shelter --no-create-home shelter
WORKDIR /app
COPY --chown=shelter:shelter certs ./certs
COPY --from=build --chown=shelter:shelter /build/build/libs/shelter-connect-api.jar ./app.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
ENV ASSET_HARNESS_PYTHON=/opt/motion/bin/python
EXPOSE 8080
# Favor startup latency on the development service's 0.1 CPU allocation.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-XX:TieredStopAtLevel=1", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
