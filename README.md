# Print AIR

Print AIR is a shop-scoped document submission and printing platform. Customers submit PDF, DOCX, JPG, or JPEG files without sharing a phone number; operators preview and print each document individually from their shop dashboard.

## Local development setup

Requirements: Docker Desktop with its Linux container engine, Java 25, and Node.js 22+.

This project uses a local persistent file backend by default so the upload flow can be tested without a cloud account or AIStor license.

1. Copy `.env.example` to `.env`.
2. Replace every `REPLACE_*` value with a unique local secret. Do not commit `.env`.
3. Start the stack from the repository root:

   ```powershell
   docker compose up --build
   ```

4. Open the dashboard at `http://localhost:5173`; use the operator email and password set by `BOOTSTRAP_OPERATOR_EMAIL` and `BOOTSTRAP_OPERATOR_PASSWORD` in `.env`.
5. To verify a newly registered shop, open the local email inbox at `http://localhost:8025` and use the verification link in the captured message. Mailpit captures mail locally; it does not deliver messages externally, and its in-memory inbox may be cleared when the container restarts.
6. The customer submission page for the sample shop is `http://localhost:5174/shop/demo-cafe`.
7. Check backend health with:

   ```powershell
   docker compose exec backend curl -fsS http://localhost:8080/actuator/health/readiness
   ```

Stop with `docker compose down`. This preserves the local PostgreSQL and document-storage volumes. `docker compose down -v` permanently removes those volumes.

## Build and tests

```powershell
.\mvnw.cmd -f backend\pom.xml verify
npm ci --prefix frontend\dashboard
npm run build --prefix frontend\dashboard
npm test --prefix frontend\dashboard
npm ci --prefix frontend\submit
npm run build --prefix frontend\submit
npm test --prefix frontend\submit
```

## Local storage behavior

The default storage backend is `local` and is configured through `printair.storage.backend` and `printair.storage.root-dir` in [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml). The backend writes uploaded files to the Docker volume `printair-storage` so they persist across backend restarts without requiring MinIO or AIStor.

The S3 configuration remains available when a remote object store is explicitly configured by setting `STORAGE_BACKEND=s3` and providing the required S3 environment values.

## Submission limits

A customer can add any number of supported files to one submission. Print AIR enforces no application-level file-count or per-file size limit, but reserves pending uploads and counts unexpired stored documents against each shop's aggregate `SHOP_QUOTA_BYTES` limit (1 GiB by default). Quota exhaustion returns HTTP 429. The backend checks actual object size against the reservation before accepting an upload. Storage providers, reverse proxies, and deployment infrastructure may impose additional request or object-size limits. DOCX validation retains its ZIP expansion and entry-count protections.

The checked-in customer Nginx config does not override Nginx's default 1 MiB request-body limit for the submission metadata request. File bytes are uploaded directly to the local backend or configured object store, not through that Nginx proxy.

Production web deployments must explicitly configure `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, and `DASHBOARD_BASE_URL`. When `MAIL_SMTP_AUTH=true`, they must also configure `MAIL_USERNAME` and `MAIL_PASSWORD`. Missing settings stop web-application startup with an actionable error. Local Compose uses Mailpit; verify real local delivery by registering a test cafe, opening `http://localhost:8025`, and using the captured link. If sending fails or the process stops after registration commits but before sending, the account remains pending and can request a new verification email; it is never automatically activated. The API returns a generic response to avoid account enumeration, while a sanitized backend warning signals delivery failures. This synchronous post-commit approach is not a durable mail outbox.

The login, registration, resend, and verification throttles use in-memory IP-based windows and apply per backend process. They are not shared across instances; multi-instance deployments need an edge/shared rate limiter before relying on them for fleet-wide protection.
