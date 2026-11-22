# API

All API paths are same-origin under `/api`. Operator endpoints use an HttpOnly session cookie and require the CSRF token returned by `GET /api/auth/csrf` for state-changing requests. JSON errors use HTTP status codes; unauthorized operator requests return 401. Document lookups outside the authenticated shop return 404.

## Authentication and shop QR

- `GET /api/auth/csrf` — returns a CSRF header name and token.
- `POST /api/auth/login` — form fields `email` and `password`; returns 204 or 401.
- `POST /api/auth/register` — JSON fields `shopName`, `email`, and `password`; creates a pending cafe account and sends a one-time email verification link. Passwords must be at least 12 characters and no more than 72 UTF-8 bytes. Requires configured email delivery.
- `POST /api/auth/resend-verification` — JSON field `email`; sends a fresh link for a pending account without revealing whether an account exists.
- `POST /api/auth/verify-email` — JSON field `token`; enables the operator account when its one-time token is valid and unexpired.
- `GET /api/auth/me` — authenticated operator and shop name/slug plus the public submission URL. That URL contains only the public shop slug.
- `POST /api/auth/logout` — invalidates the session; CSRF required.

The dashboard renders a QR code from the public submission URL returned by `/api/auth/me`; no credential is encoded. The QR can be downloaded or printed from the dashboard.
Email verification uses `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, and `DASHBOARD_BASE_URL`. SMTP credentials are required when `MAIL_SMTP_AUTH=true`. Web application startup fails clearly if required settings are missing. Local Compose sends to Mailpit at `http://localhost:8025`; messages are captured rather than sent externally. Registration and resend return the same success response for known and unknown addresses. A runtime mail failure leaves a pending account with a hashed, expiring token and is logged without address, token, or transport details; resend can rotate the token and retry delivery. Verification consumes the unexpired token atomically; reuse, expiry, or an invalid token is rejected.

## Public customer submission

- `GET /api/public/shops/{slug}` — returns only `{"shopName":"..."}` for the customer submission page.
- `POST /api/public/shops/{slug}/submissions`
- Body: `{"submissionId":"<uuid>","customerName":"Student","files":[{"uploadId":"<uuid>","fileName":"assignment.pdf","contentType":"application/pdf","sizeBytes":12345}]}`
- The request creates one submission with one independent upload intent per file. It returns an `uploads` array containing each file's `uploadId`, short-lived PUT URL, expected content type, expiry, and internal state. Repeating the same request IDs is idempotent; they do not expose documents.
- `PUT` each returned URL with the expected `Content-Type` and file bytes.
- `POST /api/public/uploads/{uploadId}/finalize` after a successful PUT. Each file finalizes independently and returns `{"result":"accepted"}` after validation; repeated finalize is safe.
- `POST /api/public/uploads/{uploadId}/retry` returns a fresh upload grant for that file after an upload/finalization failure. Successfully finalized files remain finalized and are not uploaded again.

PDF, DOCX, JPG, and JPEG are accepted. There is no application-level per-file size or file-count limit. Unexpired documents plus pending upload reservations count against each shop's `SHOP_QUOTA_BYTES` aggregate limit (default 1 GiB); actual stored size must match the declared reservation before finalization. Quota exhaustion returns HTTP 429. Per-IP and per-shop request rate limits and positive-size checks remain enforced. Provider, proxy, and deployment limits may still apply. No customer status or tracking endpoint is provided.
The checked-in customer Nginx proxy retains its default 1 MiB request-body limit for the JSON submission declaration; document bytes are uploaded directly to the backend or object store.

## Operator document access

- `GET /api/operator/documents` — current, unexpired documents for the authenticated operator's shop.
- `GET /api/operator/documents/{id}/preview` — authorized inline PDF or original JPEG stream; no-store. DOCX is previewed from its converted PDF.
- `GET /api/operator/documents/{id}/print` — authorized stream for one document: the original PDF, converted DOCX PDF, or original JPEG bytes.
- `POST /api/operator/documents/{id}/conversion/retry` — retry a failed DOCX conversion for the owning shop.
- `DELETE /api/operator/documents/{id}` — delete the source and converted object for the owning shop.

The API has no normal document-download endpoint.
