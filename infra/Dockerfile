FROM clojure:temurin-21-alpine AS builder

RUN mkdir -p /build
WORKDIR /build

COPY deps.edn /build/
RUN clojure -P -X:infra

COPY ./src/ /build/src
COPY ./infra/ /build/infra

RUN clojure -T:infra build/uber

FROM eclipse-temurin:21-jre-alpine AS final

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

ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["java", "-jar", "/service/restaurant.jar"]
