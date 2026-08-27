# syntax=docker/dockerfile:1.6
#
# Dockerfile for sigdep-sync (edge agent).
#
# Build context expects `target/sigdep-sync-<version>.jar` to be already
# built by Maven. CI does `mvn install` for sigdep-contracts followed
# by `mvn package` for sigdep-sync before `docker build`, see
# .github/workflows/release.yml.
#
# Run as a service via systemd in production (preferred), or as a
# container for sites that prefer Docker.

FROM eclipse-temurin:17-jre-jammy

# Métadonnées de build, passées par la CI (--build-arg) et exposées en
# labels OCI standard. Permettent d'identifier précisément l'image qui tourne
# sur un site (docker inspect / registry). Défauts 'unknown' pour un build
# local sans arguments.
ARG IMAGE_VERSION=unknown
ARG IMAGE_REVISION=unknown
ARG IMAGE_CREATED=unknown
LABEL org.opencontainers.image.title="SIGDEP-3 edge sync agent" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.created="${IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/ITECH-CI/sigdep-sync" \
      org.opencontainers.image.licenses="Proprietary"

# Non-root user for defence in depth.
RUN groupadd --system --gid 1001 sigdep \
 && useradd  --system --uid 1001 --gid sigdep \
              --home-dir /opt/sigdep --shell /bin/false sigdep

WORKDIR /opt/sigdep

# Persistent volume for the SQLite buffer + sync_state. The path
# defaults to /var/lib/sigdep-agent which matches the systemd unit so
# both deployment styles share the same on-disk layout.
RUN mkdir -p /var/lib/sigdep-agent \
 && chown -R sigdep:sigdep /var/lib/sigdep-agent
VOLUME /var/lib/sigdep-agent

# Embed the fat jar built by the CI.
COPY target/sigdep-sync-*.jar /opt/sigdep/sigdep-sync.jar
RUN chown sigdep:sigdep /opt/sigdep/sigdep-sync.jar

USER sigdep

# Defaults match application.yml ; override at runtime with env vars
# (SIGDEP_SITE_CODE, SIGDEP_CENTRAL_API_URL, SIGDEP_API_KEY, etc.).
ENV SIGDEP_BUFFER_PATH=/var/lib/sigdep-agent/buffer.sqlite

# Healthcheck. The agent is a scheduler-only app: it does NOT pull in
# spring-boot-starter-web, so there is no HTTP server and no
# /actuator/health endpoint to curl. Instead we prove liveness two ways:
#   1. the Java process (PID 1) is alive, and
#   2. the SQLite buffer was touched within the last sync interval × 2
#      (the agent reads/writes it every cycle — a stale file means the
#      scheduler is wedged).
# interval-ms default is 900000 (15 min) ; we allow 35 min of staleness
# before flagging unhealthy, and a generous start-period for the initial
# backfill which can run long.
HEALTHCHECK --interval=120s --timeout=10s --start-period=120s --retries=3 \
  CMD pgrep -f sigdep-sync.jar >/dev/null \
   && [ -f "$SIGDEP_BUFFER_PATH" ] \
   && [ "$(( $(date +%s) - $(stat -c %Y "$SIGDEP_BUFFER_PATH") ))" -lt 2100 ] \
   || exit 1

ENTRYPOINT ["java", "-jar", "/opt/sigdep/sigdep-sync.jar"]
