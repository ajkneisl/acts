FROM eclipse-temurin:20-jdk AS build

WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:20-jre AS runtime

LABEL org.opencontainers.image.title="acts" \
      org.opencontainers.image.description="Apple Calendar Todoist Sync" \
      org.opencontainers.image.source="https://github.com/ajkneisl/acts"

RUN groupadd --system --gid 1000 app \
 && useradd --system --uid 1000 --gid app --create-home app

COPY --from=build /src/build/install/acts /opt/acts

RUN chmod -R a+rX /opt/acts \
 && chmod a+x /opt/acts/bin/acts \
 && ln -s /opt/acts/bin/acts /usr/local/bin/acts

ENV ACTS_STATE=/data/state.json \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

RUN mkdir -p /data && chown -R app:app /data
VOLUME ["/data"]

USER app
WORKDIR /data

HEALTHCHECK --interval=2m --timeout=10s --start-period=30s --retries=3 \
  CMD curl -fsS "http://localhost:${ACTS_HTTP_PORT:-8080}${ACTS_HEALTH_PATH:-/health}" >/dev/null || exit 1

ENTRYPOINT ["acts"]
