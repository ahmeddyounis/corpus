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
RUN groupadd --system corpus && useradd --system --gid corpus corpus
WORKDIR /app
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./
USER corpus
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
