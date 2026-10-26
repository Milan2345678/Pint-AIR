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
- Body: `{"customerName":"Student","files":[{"fileName":"assignment.pdf","contentType":"application/pdf","sizeBytes":12345}]}`
- Returns one `uploadId`, short-lived PUT URL, expected content type, and expiry per file. These IDs only authorize upload finalization; they cannot list or view documents.
- `PUT` each returned URL with the expected `Content-Type` and file bytes.
- `POST /api/public/uploads/{uploadId}/finalize` after a successful PUT. Returns `{"result":"accepted"}` after validation; repeated finalize is safe.

Only PDF and DOCX are accepted. File limits and shop quota are enforced server-side. No customer status or tracking endpoint is provided.

## Operator document access

- `GET /api/operator/documents` — current, unexpired documents for the authenticated operator's shop.
- `GET /api/operator/documents/{id}/preview` — authorized inline PDF stream; no-store.
- `GET /api/operator/documents/{id}/print` — same authorized inline PDF stream for browser printing.
- `POST /api/operator/documents/{id}/conversion/retry` — retry a failed DOCX conversion for the owning shop.
- `DELETE /api/operator/documents/{id}` — delete the source and converted object for the owning shop.

The API has no normal document-download endpoint.
