# Security notes

## Threats addressed in this first implementation

- Cross-shop access: operator identity maps to one shop; list, preview, print, retry, and delete queries include the principal's server-derived shop ID. Public shop slugs permit submission only.
- Account credentials: passwords are stored as BCrypt hashes. Operator accounts are provisioned from environment variables at bootstrap; there is no public registration.
- Browser sessions: server-side sessions use HttpOnly, SameSite=Lax cookies, secure cookies by default, logout invalidation, and Spring Security CSRF tokens.
- Upload abuse: exact PDF/DOCX extension and MIME pairing, declared size, file count, shop quota, per-IP/per-shop rate limits, actual object size/type, PDF signature, DOCX ZIP structure, ZIP entry and expansion limits, and SHA-256 are checked.
- Storage privacy: object keys are random and private. Short-lived signed PUT URLs are returned only to the submitting client. Shop previews are proxied through an authorization check; storage URLs are not returned to operators.
- Cache and logs: preview responses use `private, no-store`; document bytes, credentials, and signed URLs are not intentionally logged.
- Retention: objects are deleted before DB rows are marked deleted. Failed deletion keeps the document inaccessible and scheduled for retry.

## Not protected / known limitations

- Rate limiting is in-memory and resets on restart; it is not suitable for multiple backend replicas. Configure a shared edge limit before scaling.
- The backend trusts `X-Forwarded-*` headers. The local Compose setup keeps the backend off host ports and has Nginx overwrite forwarded client IP headers. Production must keep the API private behind a trusted proxy that overwrites these headers.
- No MFA, account recovery, malware scanning, content-disarm, or independent security audit is included.
- The upload URL remains reusable until expiry, but it targets an isolated staging key; finalize copies validated bytes to a separate server-only key, preventing later PUTs from changing an accepted document. Size is checked at finalize, so storage-level quotas/lifecycle controls are also necessary to mitigate oversize pre-finalization uploads.
- Browser rendering and OS printing may place temporary data in memory, browser caches, or print spools. `no-store` reduces caching but cannot control the OS spool.
- Local Compose credentials are placeholders and must be replaced. Do not use them in production. Use TLS, managed secrets, restrictive bucket policies, backups, and least-privilege storage credentials.
- The first bootstrap operator is created only if its four `BOOTSTRAP_*` settings are present. Protect and remove bootstrap credentials after provisioning; no administrative account-management API is provided.
