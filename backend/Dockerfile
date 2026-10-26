FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw .
COPY backend/pom.xml backend/pom.xml
RUN chmod +x mvnw && ./mvnw -f backend/pom.xml -DskipTests dependency:go-offline
COPY backend backend
RUN ./mvnw -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --shell /usr/sbin/nologin printair
WORKDIR /app
COPY --from=build --chown=printair:printair /workspace/backend/target/printair-backend.jar /app/printair-backend.jar
USER printair
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/printair-backend.jar"]

FROM runtime AS conversion-worker
USER root
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-writer fonts-liberation \
    && rm -rf /var/lib/apt/lists/*
USER printair
