import { readFile } from "node:fs/promises";
import { basename } from "node:path";
import { performance } from "node:perf_hooks";

const baseUrl = process.env.BASE_URL ?? "http://localhost:5174";
const shopSlug = process.env.SHOP_SLUG;
const filePath = process.env.FILE_PATH;
const requests = Number(process.env.REQUESTS ?? 5);
const concurrency = Number(process.env.CONCURRENCY ?? 2);

if (!shopSlug || !filePath || !Number.isSafeInteger(requests) || requests < 1
        || !Number.isSafeInteger(concurrency) || concurrency < 1) {
  throw new Error("Set SHOP_SLUG and FILE_PATH; REQUESTS and CONCURRENCY must be positive integers.");
}

const fileName = basename(filePath);
const contentType = fileName.toLowerCase().endsWith(".pdf")
  ? "application/pdf"
  : fileName.toLowerCase().endsWith(".docx")
    ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    : null;
if (!contentType) throw new Error("FILE_PATH must point to a PDF or DOCX file.");

const bytes = await readFile(filePath);
const results = [];
let nextRequest = 0;
const started = performance.now();

async function uploadOnce() {
  const index = nextRequest++;
  if (index >= requests) return;

  const requestStarted = performance.now();
  try {
    const initiation = await fetch(
      `${baseUrl}/api/public/shops/${encodeURIComponent(shopSlug)}/submissions`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          customerName: `Load test ${index + 1}`,
          files: [{ fileName, contentType, sizeBytes: bytes.length }],
        }),
      },
    );
    if (!initiation.ok) throw new Error(`initiation HTTP ${initiation.status}`);
    const { uploads } = await initiation.json();
    if (!Array.isArray(uploads) || uploads.length !== 1) {
      throw new Error("unexpected upload grant response");
    }

    const [grant] = uploads;
    const put = await fetch(grant.url, {
      method: "PUT",
      headers: { "Content-Type": grant.contentType },
      body: bytes,
    });
    if (!put.ok) throw new Error(`object upload HTTP ${put.status}`);

    const finalize = await fetch(`${baseUrl}/api/public/uploads/${grant.uploadId}/finalize`, {
      method: "POST",
    });
    if (!finalize.ok) throw new Error(`finalize HTTP ${finalize.status}`);
    results.push({ ok: true, milliseconds: performance.now() - requestStarted });
  } catch (error) {
    results.push({
      ok: false,
      milliseconds: performance.now() - requestStarted,
      error: error instanceof Error ? error.message : "unknown error",
    });
  }

  await uploadOnce();
}

await Promise.all(Array.from({ length: Math.min(concurrency, requests) }, uploadOnce));
const elapsedSeconds = (performance.now() - started) / 1000;
const latencies = results.map((result) => result.milliseconds).sort((a, b) => a - b);
const percentile = (p) => latencies[Math.max(0, Math.ceil(p * latencies.length) - 1)] ?? null;
const succeeded = results.filter((result) => result.ok).length;

console.log(JSON.stringify({
  attempted: results.length,
  succeeded,
  failed: results.length - succeeded,
  concurrency: Math.min(concurrency, requests),
  fileBytes: bytes.length,
  elapsedSeconds: Number(elapsedSeconds.toFixed(2)),
  successfulSubmissionsPerSecond: Number((succeeded / elapsedSeconds).toFixed(2)),
  latencyMs: {
    p50: percentile(0.5) === null ? null : Math.round(percentile(0.5)),
    p95: percentile(0.95) === null ? null : Math.round(percentile(0.95)),
  },
  failures: results.filter((result) => !result.ok).map((result) => result.error),
}, null, 2));

if (succeeded !== results.length) process.exitCode = 1;
