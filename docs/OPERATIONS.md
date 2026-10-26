# Operations

## Local stack

1. Install Java 21, Node.js 22+, and Docker Desktop.
2. Copy `.env.example` to `.env`; replace every `REPLACE_*` value with local random values. Keep `.env` untracked.
3. Run `docker compose up --build`.
4. Open the operator dashboard at `http://localhost:5173` and the customer submission page at `http://localhost:5174/shop/demo-cafe`.
5. The demo operator email and password are the values in `.env` (`BOOTSTRAP_OPERATOR_EMAIL` and `BOOTSTRAP_OPERATOR_PASSWORD`).
6. MinIO console is `http://localhost:9001`; use its local credentials from `.env`.
7. Stop with `docker compose down`. This keeps named data volumes. `docker compose down -v` destroys the local database and object data; only run it if that data is intentionally disposable.

## Deployment

- Put the backend behind a TLS-terminating trusted reverse proxy; do not expose its port directly when trusting forwarded headers. Configure the proxy to overwrite, not append, client forwarding headers.
- Set `COOKIE_SECURE=true`, a stable `PUBLIC_BASE_URL`, exact allowed CORS origins, private object storage endpoint, and separate internal/public S3 endpoints.
- Use managed secret storage and least-privilege credentials restricted to the private document bucket.
- Keep the conversion worker isolated, memory-limited, and able to reach PostgreSQL and the object store only. Set an appropriate temporary-storage limit.
- Apply database migrations automatically at startup. Back up PostgreSQL and object storage together and periodically test restore/deletion recovery.
- Configure object lifecycle rules for abandoned multipart uploads and alert on failed conversions, repeated storage errors, queue age, database health, and cleanup failures.
- Run one backend instance until distributed rate limiting and multi-instance lifecycle behavior are implemented and tested.

## Verification and monitoring

- Backend: `.\mvnw.cmd -f backend\pom.xml verify` (Docker must be running for Testcontainers integration tests).
- Frontend: `npm ci --prefix frontend\dashboard`; `npm test --prefix frontend\dashboard`; similarly for `frontend\submit`.
- Health: `/actuator/health` is exposed; only health/info are exposed by Actuator.
- `scripts/load-test.mjs` is a small local exercise, not a capacity benchmark. Record the actual command and output for each run.

## Known operational risks

No cloud deployment, TLS, restore drill, malware scanning, multi-instance limit, or representative workload has been verified by this repository change. Printing depends on the shop computer's browser, printer configuration, and OS print spool.
