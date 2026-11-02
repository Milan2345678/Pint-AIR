# Development status

## Verified state
- [x] The backend compiles and its Maven test suite passes in the local environment.
- [x] The frontend dashboard and submit apps pass their Vitest suites.
- [x] The storage backend default has been corrected to a local persistent filesystem setup so the application does not require S3/AIStor for local development.
- [x] The conditional bean wiring prevents Spring from trying to create the S3 client when `printair.storage.backend=local`.

## Commands actually run
- `cd "C:\Users\DELL\Desktop\Print AIR"; $env:JAVA_HOME = "D:\java21"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd -f backend\pom.xml test -DskipITs` — passed.
- `cd "C:\Users\DELL\Desktop\Print AIR\frontend\dashboard"; npm test -- --run` — passed: 2 test files, 7 tests passed.
- `cd "C:\Users\DELL\Desktop\Print AIR\frontend\submit"; npm test -- --run` — passed: 1 test file, 3 tests passed.

## Not verified here
- Docker Compose startup and the full browser-based upload flow were not run in this environment.
- The local app stack was not started with `docker compose up` because Docker daemon availability in this environment was not confirmed.
- Real browser upload/list/retrieve validation across the backend, PostgreSQL and the dashboard is still pending a working Docker runtime.

## Current architecture status
- Java backend: working in local Maven test path.
- PostgreSQL: configured and validated through the backend Spring test wiring.
- Local file storage: active default, so the app can run without a remote S3/AIStor dependency.
- Docker stack: configuration exists, but end-to-end container startup remains unverified here.

## Remaining work
- Start the stack in a Docker-enabled environment and verify the full upload → metadata save → dashboard listing → document retrieval flow with both PDF and DOCX.
- Verify the customer frontend at `http://localhost:5174/shop/demo-cafe` and the shop dashboard at `http://localhost:5173` in a live browser session.
- Confirm persistence and restart behavior for stored documents and metadata.
