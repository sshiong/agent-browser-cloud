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
RUN groupadd --system browsercloud \
    && useradd --system --gid browsercloud --home-dir /app browsercloud
WORKDIR /app
COPY --from=build /workspace/apps/control-plane/build/libs/*.jar app.jar
USER browsercloud
EXPOSE 8080 9091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
