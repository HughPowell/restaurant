FROM clojure:temurin-24-tools-deps-1.12.0.1530-alpine AS builder

RUN mkdir -p /artifacts
RUN wget -O /artifacts/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.14.0/opentelemetry-javaagent.jar

RUN mkdir -p /build
WORKDIR /build

COPY deps.edn /build/
RUN clojure -P -X:build

COPY ./src /build/src
RUN mkdir -p /build/infra
COPY ./infra/build.clj /build/infra/
RUN clojure -T:build uber

FROM eclipse-temurin:23.0.2_7-jre-alpine AS final

LABEL org.opencontainers.image.source=https://github.com/HughPowell/restaurant
LABEL org.opencontainers.image.description="Restaurant reservation application"
LABEL org.opencontainers.image.licenses="MPL-2.0"

RUN apk add --no-cache \
    dumb-init~=1.2.5

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    clojure

RUN mkdir -p /service && chown -R clojure. /service

USER clojure

WORKDIR /service
COPY --from=builder --chown=clojure:clojure /build/target/restaurant.jar /service/restaurant.jar
COPY --from=builder --chown=clojure:clojure /artifacts/opentelemetry-javaagent.jar /service/opentelemetry-javaagent.jar

EXPOSE 3000

ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["java", \
     "-Djdk.tracePinnedThreads=full", \
     "-javaagent:/service/opentelemetry-javaagent.jar", \
     "-jar", \
     "/service/restaurant.jar"]
