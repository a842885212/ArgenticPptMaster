# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package \
    && JAR="$(ls target/ArgenticPptMaster-*.jar | grep -v '\.original$' | head -n1)" \
    && cp "$JAR" /build/app.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl pandoc ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/var/ppt-master/logs /app/var/ppt-master/agent-sessions
COPY --from=build /build/app.jar /app/app.jar
ENV TZ=Asia/Shanghai
EXPOSE 8097
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8097/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
