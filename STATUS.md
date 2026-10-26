# Development status

## Environment and backend foundation
- [x] Repository instructions preserved; Git initialized.
- [x] Maven Wrapper, Java 21/Spring Boot backend foundation, environment-based settings, baseline migration, Actuator health, README, and `.gitignore` created.
- [x] `.\mvnw.cmd -f backend\pom.xml -DskipTests validate` — BUILD SUCCESS.
- [x] `.\mvnw.cmd -f backend\pom.xml verify` — BUILD SUCCESS; 1 integration test skipped because Docker is unavailable.
- [ ] PostgreSQL startup/connectivity integration has not been verified. Docker CLI 29.3.0 is installed, but its daemon is unreachable.
- Java 21 is available. Global Maven is absent; the Maven Wrapper is included for fresh clones.

## Shop auth and isolation
- [x] Added shop/operator schema, BCrypt password storage, one-operator-per-shop binding, server-side session cookies, CSRF tokens, and per-IP login throttling.
- [x] `.\mvnw.cmd -f backend\pom.xml verify` — BUILD SUCCESS; rate-limit unit test passed; PostgreSQL Testcontainers integration test skipped because Docker is unavailable.
- [ ] Cross-shop endpoint isolation remains to be verified after document APIs are implemented.

## Remaining
- [ ] Shop operator authentication and shop isolation.
- [ ] Private object storage, submission uploads, and validation.
- [ ] Preview/print and conversion worker.
- [ ] Retention and cleanup.
- [ ] Operator dashboard and customer submission app.
- [ ] Infrastructure, docs, tests, load script, final review.
