# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY platform-common/pom.xml platform-common/
COPY platform-open-api/pom.xml platform-open-api/
RUN mvn -q -B -pl platform-common,platform-open-api -am dependency:go-offline || true
COPY platform-common platform-common
COPY platform-open-api platform-open-api
RUN mvn -q -B -pl platform-open-api -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# 目录 schema 随 jar 打包（classpath:db/schema.sql）
COPY --from=build /build/platform-open-api/target/platform-open-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
