# syntax=docker/dockerfile:1

# ---- Stage 1: build (JDK 21) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Warm the wrapper/dependency cache (best-effort).
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Stage 2: runtime (slim JRE 21) ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd -r polymind && useradd -r -g polymind polymind
COPY --from=build /app/build/libs/polymind.jar ./polymind.jar
USER polymind
EXPOSE 9090
# ZGC (generational) for low-pause streaming; virtual threads for concurrency.
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Dspring.threads.virtual.enabled=true"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:9090/v1/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar polymind.jar"]
