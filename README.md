# Print AIR

Print AIR is a shop-scoped document submission and printing platform. Customers submit PDF or DOCX files without sharing a phone number; operators preview and print documents from their shop dashboard. This implementation is not production-ready until its Docker-backed integration tests and deployment controls are verified.

## Run the local stack

Requirements: Docker Desktop with its Linux container engine, Java 21, and Node.js 22+.

1. Copy `.env.example` to `.env`.
2. Replace every `REPLACE_*` value with a unique local secret. Do not commit `.env`.
3. Start the complete stack from the repository root:

   ```powershell
   docker compose up --build
   ```

4. Open the dashboard at `http://localhost:5173`; use the operator email and password set by `BOOTSTRAP_OPERATOR_EMAIL` and `BOOTSTRAP_OPERATOR_PASSWORD` in `.env`.
5. The customer submission page for the sample shop is `http://localhost:5174/shop/demo-cafe`.
6. Check backend health with `docker compose exec backend curl -fsS http://localhost:8080/actuator/health`.

Stop with `docker compose down`. This preserves named database and object-storage volumes. `docker compose down -v` permanently removes those local volumes.

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

The backend's Testcontainers integration tests require Docker. Their skips when Docker is unavailable do not prove PostgreSQL or MinIO integration.

## Small upload load exercise

With the local stack running and a shop slug available, set a real PDF or DOCX path and run:

```powershell
$env:SHOP_SLUG = "demo-cafe"
$env:FILE_PATH = "C:\path\to\sample.pdf"
$env:REQUESTS = "5"
$env:CONCURRENCY = "2"
node scripts\load-test.mjs
```

This measures end-to-end submission, direct object upload, and finalization latency for the chosen file and environment. It is not a capacity benchmark; default per-IP limits, machine resources, and network conditions affect results.
Each successful request creates a real submission that consumes shop quota and remains until deleted or expired; use a disposable local shop and test document.

See [docs/OPERATIONS.md](docs/OPERATIONS.md) for deployment, backup, monitoring, and known risks.
