
# Print AIR — Project Instructions

## Project goal
Build a web-based document submission platform for print shops.
Customers scan a shop QR code, upload documents, preview them
in the browser, and submit print jobs without sharing phone numbers.

## Working rules
- Inspect the existing code before changing anything.
- Explain the proposed approach before substantial implementation.
- Make small, focused changes; do not rewrite unrelated files.
- Do not invent existing files, APIs, environment variables, or schemas.
- Explain why each important change is needed.
- Run relevant tests and report which checks passed or failed.
- Never claim a test passed unless it was actually run.

## Security and privacy
- Treat uploaded documents as private.
- Never expose permanent public file URLs for private documents.
- Validate file type, size, and content on the server.
- Enforce shop and print-job authorization on the server.
- Never store secrets in source code or commit .env files.
- Do not collect customer phone numbers unless a justified
  requirement is explicitly approved.

## Workflow
- Review git status and existing changes before editing.
- Preserve unrelated user changes.
- Summarize changed files and potential risks.
- Ask before destructive commands, dependency changes,
  database migrations, or production deployments.
