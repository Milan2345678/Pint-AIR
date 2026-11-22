# Architecture

## Current shape

The backend is a Java 25 / Spring Boot 3.5 modular monolith. PostgreSQL stores shops, one operator per shop, submission metadata, upload intents, document metadata, and conversion jobs. Document bytes live in configured private object storage. The operator dashboard and customer submission site are separate React applications served by Nginx. LibreOffice runs only in the conversion-worker container.
```text
Customer browser -> submission UI -> one submission / per-file grants -> Java API -> PostgreSQL
                   per-file PUT -> private object storage
Operator browser -> dashboard -> Java API -> authorized original/converted document stream
                                  -> PostgreSQL / private object storage
Conversion worker -> PostgreSQL job table -> private object storage
```

## Document lifecycle

1. A public shop slug resolves only a submission destination. The customer sends a submission UUID, name, and supported file declarations. The API validates the shop, extension/content-type pairing, positive declared sizes, request rate limits, and aggregate shop capacity. Unexpired document bytes plus unexpired pending upload reservations count against `SHOP_QUOTA_BYTES` (1 GiB by default); a pessimistic shop-row lock serializes concurrent reservations.
2. The API creates one submission and one independent expiring upload intent per file, returning short-lived PUT grants for random staging keys. The customer uploads files separately to private storage. Repeating the request with the same submission and upload UUIDs is idempotent.
3. Each file finalizes independently. The server checks stored metadata and size, validates the PDF signature, validates the DOCX package, or decodes a JPEG using the JDK image reader, calculates SHA-256, and makes that document available. The staging key is deleted and verified; a reusable PUT URL cannot overwrite a finalized object. A failed file can receive a new upload grant without re-uploading other finalized files. DOCX alone gets an internal conversion job.
4. The dashboard lists each unexpired document separately and includes its submission reference and processing state. Preview and print requests check shop ownership and expiry. PDF uses the original PDF, DOCX uses its converted PDF, and JPG/JPEG uses the original JPEG bytes. The browser and OS may temporarily handle document data while previewing or printing.
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

- Spring Boot 3.5.16, Maven, PostgreSQL 16, Java 25, and React/Vite/TypeScript are selected for this first implementation.
- Storage uses the existing ObjectStorage abstraction. Compose defaults to a local persistent volume; an S3-compatible backend is available, and the cloud provider is not selected.
- Retention defaults to 48 hours after a validated upload. This is provisional and requires confirmation with pilot shops; `RETENTION_HOURS` can change it.
- The browser renders PDF pages and invokes the OS print dialog. Printing necessarily transfers rendered bytes to the browser and possibly the OS spool; there is no normal dashboard download action.
- DOCX conversion uses a DB-backed job and a separate LibreOffice worker container, without a message broker.
- Login, registration, resend, verification, and submission rate limits are process-local. They are suitable only for a single backend instance; multi-instance deployment needs a shared limiter or an edge rate limit.
- Initial operator bootstrapping is an environment-driven one-time creation flow. Public shop registration creates a disabled operator until a one-time email verification link is used. Registration commits the pending account and hashed token before SMTP delivery; failed delivery can be retried through resend. Delivery is not a durable outbox: a process crash after commit but before sending also requires resend. Local Compose captures verification mail in Mailpit; deployed web environments must explicitly configure their SMTP relay and dashboard origin.

## Failure handling and trade-offs

- A failed DB commit after upload leaves an expiring pending intent; cleanup deletes the object. An object-store failure prevents finalization or deletion from being marked complete.
- A failed storage deletion leaves the document in `DELETING`, which blocks access and is retried by the scheduled cleanup.
- A worker crash leaves a processing job that is eligible for recovery after five minutes.
- A DOCX conversion failure is visible as an operator error; retry is shop-authorized.
- Presigned PUT limits are enforced at finalize as well as against declared size. A malicious client can temporarily upload more than declared before finalize; production deployments must enforce request/bucket quotas and lifecycle policies at the storage edge.
- There is no application-level file-count or per-file size limit. Aggregate shop quota controls accepted document storage and active upload reservations, but an oversized object may temporarily exist in staging before finalization rejects it. A storage provider, proxy, or deployment should also enforce a suitable request/object limit.
- The checked-in customer Nginx config uses Nginx's default 1 MiB request-body limit for the JSON submission declaration; it does not carry file bytes. File bytes go directly to the local backend upload endpoint or the configured object store.
- DOCX validation limits ZIP expansion to 150 MiB and the archive to 10,000 entries. JPEG validation decodes with source subsampling and uses a temporary ImageIO cache file that is closed after validation; this is not a permanent document copy.
- The initial implementation has no malware scanner, distributed rate limiter, high-availability worker, or cloud-provider-specific deployment. These require a measured threat/workload review.
