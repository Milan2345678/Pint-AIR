# API

All API paths are same-origin under `/api`. Operator endpoints use an HttpOnly session cookie and require the CSRF token returned by `GET /api/auth/csrf` for state-changing requests. JSON errors use HTTP status codes; unauthorized operator requests return 401. Document lookups outside the authenticated shop return 404.

## Authentication and shop QR

- `GET /api/auth/csrf` — returns a CSRF header name and token.
- `POST /api/auth/login` — form fields `email` and `password`; returns 204 or 401.
- `GET /api/auth/me` — authenticated operator and shop name/slug plus the public submission URL. That URL contains only the public shop slug.
- `POST /api/auth/logout` — invalidates the session; CSRF required.

The dashboard renders a QR code from the public submission URL returned by `/api/auth/me`; no credential is encoded.

## Public customer submission

- `POST /api/public/shops/{slug}/submissions`
- Body: `{"submissionId":"<uuid>","customerName":"Student","files":[{"uploadId":"<uuid>","fileName":"assignment.pdf","contentType":"application/pdf","sizeBytes":12345}]}`
- The request creates one submission with one independent upload intent per file. It returns an `uploads` array containing each file's `uploadId`, short-lived PUT URL, expected content type, expiry, and internal state. Repeating the same request IDs is idempotent; they do not expose documents.
- `PUT` each returned URL with the expected `Content-Type` and file bytes.
- `POST /api/public/uploads/{uploadId}/finalize` after a successful PUT. Each file finalizes independently and returns `{"result":"accepted"}` after validation; repeated finalize is safe.
- `POST /api/public/uploads/{uploadId}/retry` returns a fresh upload grant for that file after an upload/finalization failure. Successfully finalized files remain finalized and are not uploaded again.

PDF, DOCX, JPG, and JPEG are accepted. There is no application-level per-file size or file-count cap. The existing configurable 1 GiB shop-wide quota, rate limits, and positive-size checks remain enforced. Provider, proxy, and deployment limits may still apply. No customer status or tracking endpoint is provided.
The checked-in customer Nginx proxy retains its default 1 MiB request-body limit for the JSON submission declaration; document bytes are uploaded directly to the backend or object store.

## Operator document access

- `GET /api/operator/documents` — current, unexpired documents for the authenticated operator's shop.
- `GET /api/operator/documents/{id}/preview` — authorized inline PDF or original JPEG stream; no-store. DOCX is previewed from its converted PDF.
- `GET /api/operator/documents/{id}/print` — authorized stream for one document: the original PDF, converted DOCX PDF, or original JPEG bytes.
- `POST /api/operator/documents/{id}/conversion/retry` — retry a failed DOCX conversion for the owning shop.
- `DELETE /api/operator/documents/{id}` — delete the source and converted object for the owning shop.

The API has no normal document-download endpoint.
