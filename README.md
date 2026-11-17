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
5. The customer submission page for the sample shop is `http://localhost:5174/shop/demo-cafe`.
6. Check backend health with:

   ```powershell
   docker compose exec backend curl -fsS http://localhost:8080/actuator/health
   ```

Stop with `docker compose down`. This preserves the local PostgreSQL and document-storage volumes. `docker compose down -v` permanently removes those volumes.

## Build and tests

```powershell
.\mvnw.cmd -f backend\pom.xml test
npm ci --prefix frontend\dashboard
npm run build --prefix frontend\dashboard
npm test --prefix frontend\dashboard -- --run
npm ci --prefix frontend\submit
npm run build --prefix frontend\submit
npm test --prefix frontend\submit -- --run
```

## Local storage behavior

The default storage backend is `local` and is configured through `printair.storage.backend` and `printair.storage.root-dir` in [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml). The backend writes uploaded files to the Docker volume `printair-storage` so they persist across backend restarts without requiring MinIO or AIStor.

The S3 configuration remains available when a remote object store is explicitly configured by setting `STORAGE_BACKEND=s3` and providing the required S3 environment values.

## Submission limits

A customer can add any number of supported files to one submission. There is no application-level per-file size limit; the existing configurable 1 GiB shop-wide storage quota still applies. Storage providers, reverse proxies, and deployment infrastructure may impose their own request or object-size limits, which must be checked for each deployment. DOCX validation also retains its ZIP expansion and entry-count protections.

The checked-in customer Nginx config does not override Nginx's default 1 MiB request-body limit for the submission metadata request. File bytes are uploaded directly to the local backend or configured object store, not through that Nginx proxy.
