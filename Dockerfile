FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl ca-certificates && addgroup -S spring && adduser -S spring -G spring

WORKDIR /app
RUN mkdir -p /app/logs && chown -R spring:spring /app

COPY --from=build --chown=spring:spring /workspace/target/*.jar /app/app.jar

USER spring
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
