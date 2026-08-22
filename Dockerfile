# Two stages so the final image carries only a JRE and one jar. Building inside
# the image rather than copying a locally built jar keeps the result identical
# regardless of whose machine it was built on.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the pom first so the dependency cache survives source changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests need a database via Docker and we are already inside Docker; CI runs them.
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Non-root: this app opens user-uploaded files with POI/PDFBox.
RUN groupadd --system --gid 1001 aiagent \
 && useradd  --system --uid 1001 --gid aiagent --create-home aiagent

# fontconfig is needed for PDFBox page rendering (OCR); without it OCR runs but
# every glyph comes out as a box.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
 && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Ho_Chi_Minh
ENV LANG=C.UTF-8

COPY --from=build /build/target/AIAgent-*.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R aiagent:aiagent /app

USER aiagent
EXPOSE 8080

# Without MaxRAMPercentage the JVM sizes the heap from host RAM and gets
# OOM-killed when the container is capped.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
