# syntax=docker/dockerfile:1
FROM node:24-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
COPY --from=frontend /build/frontend/dist/ frontend/dist/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S backroom && adduser -S backroom -G backroom
COPY --from=backend --chown=backroom:backroom /build/target/backroom-0.1.0-SNAPSHOT.jar app.jar
USER backroom
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
