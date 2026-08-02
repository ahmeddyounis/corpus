# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# Warm the dependency cache in its own layer (tolerates config-only failures).
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies --quiet > /dev/null 2>&1 || true
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
    && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

FROM eclipse-temurin:25-jre AS runtime
ARG SOURCE_COMMIT=unknown
ARG VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.source="https://github.com/ahmeddyounis/corpus" \
      org.opencontainers.image.revision="${SOURCE_COMMIT}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.title="corpus"
RUN groupadd --system corpus && useradd --system --gid corpus corpus
# Pre-create the model cache with the right ownership: Docker copies this
# directory's permissions when it first initialises an empty named volume here,
# which is what lets the non-root user write to it.
RUN mkdir -p /var/cache/corpus-onnx && chown corpus:corpus /var/cache/corpus-onnx
WORKDIR /app

# Set through JAVA_TOOL_OPTIONS rather than wrapping the entrypoint in a shell:
# java must stay PID 1 so SIGTERM reaches Spring's graceful shutdown, which the
# whole drain budget depends on.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=50 \
-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./
USER corpus
EXPOSE 8080

# The JRE image has no curl; bash /dev/tcp plus a body check gives a real
# readiness probe rather than a bare port check.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/localhost/8080 && \
    printf "GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3 && \
    grep -q UP <&3' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
