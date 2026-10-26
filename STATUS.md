# Development status

## Milestones
- [x] Java 21/Spring Boot backend foundation, Maven Wrapper, Flyway baseline, and Actuator health endpoint.
- [x] Shop/operator authentication foundation: BCrypt, server-side sessions, CSRF, and shop-bound operator identity.
- [x] Public submission, private S3-compatible storage integration, upload validation/finalization, and shop-scoped document APIs.
- [x] Separate DOCX conversion worker and lifecycle cleanup/reconciliation implementation.
- [x] Operator dashboard, customer submission app, local Docker Compose configuration, and product documentation.
- [x] Added a small configurable end-to-end upload load exercise. It was not run because the Docker daemon is unavailable.

## Verification actually run
- `.\mvnw.cmd -f backend\pom.xml verify` — BUILD SUCCESS; 15 tests, 0 failures, 0 errors, 4 Testcontainers integration tests skipped.
- `npm run build` and `npm test` in `frontend\dashboard` — build succeeded (Vite reported a large lazy-loaded PDF preview chunk); 7 tests passed.
- `npm run build` and `npm test` in `frontend\submit` — build succeeded; 3 tests passed.
- Added the DOCX checksum trailing-byte regression check and conversion-timeout unit test; both passed in the backend run above.

## Not verified / remaining risks
- Docker daemon was unreachable (`npipe:////./pipe/dockerDesktopLinuxEngine`); PostgreSQL/MinIO integration, container builds, Compose startup, real uploads, conversion, expiration/deletion, and the load exercise were not run.
- Cross-shop and storage deletion integration tests are present but were skipped with the other Testcontainers tests.
- No cloud deployment, TLS, backup/restore drill, representative load test, malware scanning, or multi-instance rate limiting/reconciliation has been verified.
- `RETENTION_HOURS=48` remains a provisional default, not an approved business commitment.
- Rate limiting is in-memory and intended for a single backend instance; deployment behind multiple instances requires shared enforcement.
