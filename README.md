# Print AIR

Print AIR provides a private, shop-scoped document submission and printing workflow. The repository is being built incrementally; this initial foundation is not yet a production-ready product.

## Local development

Requirements: Java 21 and Docker Desktop with its Linux container engine running.

1. Copy `.env.example` to `.env` only after it is created in the infrastructure increment. Local defaults are for development only.
2. Start dependencies: `docker compose up -d postgres minio`
3. Start the backend: `.\mvnw.cmd -f backend\pom.xml spring-boot:run -Dspring-boot.run.profiles=dev`
4. Check health: `curl http://localhost:8080/actuator/health`

## Build and tests

- `.\mvnw.cmd -f backend\pom.xml verify`
- Frontend commands will be documented when the frontend workspaces are added.

Docker-backed Testcontainers tests require Docker Desktop to be running. They are skipped when Docker is unavailable; a skipped integration test is not evidence of database integration.
