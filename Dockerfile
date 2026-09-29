# ---- build stage -------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:11-jre
WORKDIR /app
RUN mkdir -p /app/data/jobs /app/tmp
COPY --from=build /build/target/heatnet-router-1.0.0.jar /app/app.jar
ENV JAVA_OPTS="-Xmx6g -XX:+UseG1GC" \
    HEATNET_STORAGE_DIR=/app/data/jobs \
    HEATNET_TMP_DIR=/app/tmp
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
