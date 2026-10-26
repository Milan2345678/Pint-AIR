# Architecture

## Current shape

The backend is a Java 21 / Spring Boot 3.5 modular monolith. PostgreSQL stores shops, one operator per shop, submission metadata, upload intents, document metadata, and conversion jobs. PDF and DOCX bytes live in a private S3-compatible bucket. The operator dashboard and customer submission site are separate React applications served by Nginx. LibreOffice runs only in the conversion-worker container.

```text
Customer browser -> submission UI -> Java API -> PostgreSQL
                                  -> short-lived PUT -> private object storage
Operator browser -> dashboard -> Java API -> authorized inline PDF stream
                                  -> PostgreSQL / private object storage
Conversion worker -> PostgreSQL job table -> private object storage
```

## Document lifecycle

1. A public shop slug resolves only a submission destination. The customer sends a name and file declarations; the API validates the shop, file count, extension, content type, declared size, rate limits, and quota.
2. The API stores an expiring upload intent and returns a narrowly scoped, short-lived PUT URL for a random staging object key. The customer uploads directly to the private bucket.
3. Finalize copies the staged object to a server-only document key, checks stored metadata and size, reads and validates the PDF signature or DOCX package structure, calculates SHA-256, and atomically makes the metadata visible. The original upload key is deleted and verified; a reusable PUT URL cannot overwrite the finalized object. Repeated finalization is idempotent, including recovery when the copy succeeded but the DB transaction did not. DOCX gets an internal conversion job.
4. The dashboard lists only unexpired documents belonging to the authenticated operator's shop. Preview and print requests check the same ownership and expiry constraints and stream an inline PDF with `no-store` headers. PDF data may exist temporarily in the browser and OS print spool.
5. LibreOffice converts DOCX to PDF in a separate worker container with a 60-second timeout and bounded output. Errors are stored internally and can be retried by the owning shop.
6. Operator deletion and scheduled expiry first mark a document unavailable, delete source and preview objects, verify absence, then mark the row deleted. Abandoned upload intents are cleaned up after their upload window.

## Folder structure

```text
backend/                  Spring Boot API and conversion-worker code
frontend/dashboard/       Shop operator web application
frontend/submit/          Customer upload web application
infra/                    Local storage CORS and Nginx configuration
docs/                     Product technical and operational notes
scripts/                  Local load-test script
```

## Decisions made (provisional)

- Spring Boot 3.5.16, Maven, PostgreSQL 16, Java 21, and React/Vite/TypeScript are selected for this first implementation.
- Object storage uses S3 APIs; local development uses MinIO. The actual cloud provider is not selected.
- Retention defaults to 48 hours after a validated upload. This is provisional and requires confirmation with pilot shops; `RETENTION_HOURS` can change it.
- The browser renders PDF pages and invokes the OS print dialog. Printing necessarily transfers rendered bytes to the browser and possibly the OS spool; there is no normal dashboard download action.
- DOCX conversion uses a DB-backed job and a separate LibreOffice worker container, without a message broker.
- Login and submission rate limits are process-local. They are suitable only for a single backend instance; multi-instance deployment needs a shared limiter or an edge rate limit.
- Initial operator bootstrapping is an environment-driven one-time creation flow. No public operator registration endpoint exists.

## Failure handling and trade-offs

- A failed DB commit after upload leaves an expiring pending intent; cleanup deletes the object. An object-store failure prevents finalization or deletion from being marked complete.
- A failed storage deletion leaves the document in `DELETING`, which blocks access and is retried by the scheduled cleanup.
- A worker crash leaves a processing job that is eligible for recovery after five minutes.
- A DOCX conversion failure is visible as an operator error; retry is shop-authorized.
- Presigned PUT limits are enforced at finalize as well as against declared size. A malicious client can temporarily upload more than declared before finalize; production deployments must enforce request/bucket quotas and lifecycle policies at the storage edge.
- The initial implementation has no malware scanner, distributed rate limiter, high-availability worker, or cloud-provider-specific deployment. These require a measured threat/workload review.
