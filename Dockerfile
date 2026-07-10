FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S concert && adduser -S concert -G concert
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER concert
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
