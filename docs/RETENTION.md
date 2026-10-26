# Retention and deletion

The application defaults to **48 hours from successful upload finalization**. This is a provisional operational default, not a confirmed business policy; validate it with pilot shops and set `RETENTION_HOURS` accordingly.

The scheduled backend job selects expired records and marks them `DELETING` before removing their private source and preview objects. It verifies object absence before marking the record `DELETED`. Deletion failures remain unavailable to preview/print and are retried on subsequent runs. Dashboard operators can request the same deletion path.

Upload intents expire after 30 minutes; their signed PUT URLs default to five minutes. Expired pending intents and any uploaded object are removed by cleanup. The conversion worker uses an isolated `/tmp` tmpfs in Compose and attempts cleanup in a `finally` block; cleanup failures are logged without filenames or contents.

The application currently relies on its scheduled DB-driven cleanup and object checks. Configure bucket lifecycle rules for abandoned multipart uploads and a conservative safety expiry, and monitor cleanup failures. Do not configure a bucket rule that deletes valid documents earlier than the application policy. Deletion is not a guarantee that copies already sent to the browser or OS print spool have been erased.
