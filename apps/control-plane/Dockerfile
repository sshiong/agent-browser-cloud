FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY apps/control-plane apps/control-plane
COPY packages/contracts/proto packages/contracts/proto
COPY database/migrations database/migrations

RUN chmod +x gradlew \
    && ./gradlew -p apps/control-plane bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install --no-install-recommends -y wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 65532 browsercloud \
    && useradd --uid 65532 --gid browsercloud --no-log-init --home-dir /app browsercloud
WORKDIR /app
COPY --from=build /workspace/apps/control-plane/build/libs/*.jar app.jar
COPY apps/control-plane/docker-entrypoint.sh /usr/local/bin/browsercloud-control-plane
RUN chmod 0555 /usr/local/bin/browsercloud-control-plane
USER browsercloud
EXPOSE 8080 9091
ENTRYPOINT ["/usr/local/bin/browsercloud-control-plane"]
