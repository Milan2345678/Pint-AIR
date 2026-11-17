import { useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { ApiError, api } from "./api";

type UploadState = "waiting" | "uploading" | "sent" | "failed";
type FileStatus = { uploadId: string; file: File; progress: number; state: UploadState; error?: string };
type UploadGrant = {
  uploadId: string;
  url: string | null;
  contentType: string;
  expiresAt: string;
  status: "PENDING" | "FINALIZED";
};

const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
const JPEG = "image/jpeg";

export default function App() {
  const slug = useMemo(() => {
    const match = window.location.pathname.match(/^\/shop\/([^/]+)\/?$/);
    return match ? decodeURIComponent(match[1]) : "";
  }, []);
  const [submissionId] = useState(() => crypto.randomUUID());
  const [name, setName] = useState("");
  const [selectedFiles, setSelectedFiles] = useState<FileStatus[]>([]);
  const [busy, setBusy] = useState(false);
  const [submissionStarted, setSubmissionStarted] = useState(false);
  const [submissionCreated, setSubmissionCreated] = useState(false);
  const [error, setError] = useState("");
  const submitting = useRef(false);
  const complete = submissionCreated && selectedFiles.length > 0
    && selectedFiles.every((entry) => entry.state === "sent");

  function chooseFiles(fileList: FileList | null) {
    setError("");
    const files = Array.from(fileList ?? []);
    if (files.some((file) => !isSupported(file) || file.size === 0)) {
      setError("Choose non-empty PDF, DOCX, JPG, or JPEG files only.");
      return;
    }
    setSelectedFiles((current) => [...current, ...files.map((file) => ({
      uploadId: crypto.randomUUID(), file, progress: 0, state: "waiting" as const,
    }))]);
  }

  function updateFile(uploadId: string, updates: Partial<FileStatus>) {
    setSelectedFiles((current) => current.map((entry) =>
      entry.uploadId === uploadId ? { ...entry, ...updates } : entry));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    if (!name.trim() || selectedFiles.length === 0) {
      setError("Enter your name and select at least one document.");
      return;
    }
    submitting.current = true;
    setBusy(true);
    setSubmissionStarted(true);
    setError("");
    try {
      const response = await api<{ uploads: UploadGrant[] }>(
        `/api/public/shops/${encodeURIComponent(slug)}/submissions`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            submissionId,
            customerName: name.trim(),
            files: selectedFiles.map(({ uploadId, file }) => ({
              uploadId,
              fileName: file.name,
              contentType: contentTypeFor(file),
              sizeBytes: file.size,
            })),
          }),
        },
      );
      const grants = new Map(response.uploads.map((grant) => [grant.uploadId, grant]));
      if (grants.size !== selectedFiles.length || selectedFiles.some((entry) => !grants.has(entry.uploadId))) {
        throw new Error("The server returned an incomplete upload response.");
      }
      setSubmissionCreated(true);
      for (const entry of selectedFiles) {
        const grant = grants.get(entry.uploadId);
        if (!grant) throw new Error("The server returned an incomplete upload response.");
        if (grant.status === "FINALIZED") {
          updateFile(entry.uploadId, { progress: 100, state: "sent", error: undefined });
          continue;
        }
        await uploadEntry(entry, grant);
      }
    } catch (failure) {
      setError(failure instanceof ApiError
        ? `Submission setup failed (${failure.status}). Retry this submission.`
        : "Submission setup failed. Retry this submission.");
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  async function retry(uploadId: string) {
    if (submitting.current) return;
    const entry = selectedFiles.find((file) => file.uploadId === uploadId);
    if (!entry || entry.state !== "failed") return;
    submitting.current = true;
    setBusy(true);
    setError("");
    updateFile(uploadId, { progress: 0, state: "uploading", error: undefined });
    try {
      const grant = await api<UploadGrant>(`/api/public/uploads/${uploadId}/retry`, { method: "POST" });
      if (grant.status === "FINALIZED") {
        updateFile(uploadId, { progress: 100, state: "sent" });
      } else {
        await uploadEntry(entry, grant);
      }
    } catch {
      updateFile(uploadId, { state: "failed", error: "Upload failed. Retry this file." });
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  async function uploadEntry(entry: FileStatus, grant: UploadGrant) {
    updateFile(entry.uploadId, { progress: 0, state: "uploading", error: undefined });
    try {
      if (!grant.url) throw new Error("Upload permission was not returned.");
      await putFile(grant.url, grant.contentType, entry.file, (progress) =>
        updateFile(entry.uploadId, { progress, state: "uploading" }));
      await api<void>(`/api/public/uploads/${grant.uploadId}/finalize`, { method: "POST" });
      updateFile(entry.uploadId, { progress: 100, state: "sent", error: undefined });
    } catch {
      updateFile(entry.uploadId, { state: "failed", error: "Upload failed. Retry this file." });
    }
  }

  if (!slug) return <main className="page-wrap"><section className="card"><h1>Invalid shop link</h1>
    <p>Scan the current QR code at the print shop or check the link with the shop operator.</p></section></main>;

  return <main className="page-wrap">
    <header className="submit-brand"><span className="brand-mark">PA</span><span>Print AIR</span></header>
    {complete ? <section className="card success-card" role="status">
      <span className="success-icon">✓</span><p className="eyebrow">SUBMISSION COMPLETE</p>
      <h1>Your documents are on their way.</h1>
      <p>The shop can now access the submitted files. This page does not provide document tracking.</p>
      <ul className="file-list" aria-label="Submitted documents">
        {selectedFiles.map((entry) => <li key={entry.uploadId}>
          <div className="file-details"><strong>{entry.file.name}</strong>
            <small>{fileTypeLabel(entry.file)} · {formatBytes(entry.file.size)}</small></div>
          <span className="sent-label">Submitted</span>
        </li>)}
      </ul>
    </section> : <section className="card">
      <p className="eyebrow">PRIVATE DOCUMENT SUBMISSION</p>
      <h1>Send files to the print shop</h1>
      <p className="intro">Enter your name and choose PDF, DOCX, JPG, or JPEG files. Your phone number is not requested.</p>
      <form onSubmit={submit}>
        <label>Your name<input value={name} required maxLength={100} autoComplete="name" disabled={submissionStarted}
          onChange={(event) => setName(event.target.value)} placeholder="Name for the shop operator" /></label>
        <label className="file-picker">Documents
          <input type="file" accept=".pdf,.docx,.jpg,.jpeg,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,image/jpeg"
            multiple disabled={busy || submissionStarted} onChange={(event) => {
              chooseFiles(event.target.files);
              event.currentTarget.value = "";
            }} />
          <span className="picker-box"><span className="upload-icon">↑</span><strong>Add documents</strong>
            <small>PDF, DOCX, JPG, or JPEG</small></span>
        </label>
        {selectedFiles.length > 0 && <ul className="file-list" aria-label="Selected documents">
          {selectedFiles.map((entry) => <li key={entry.uploadId}>
            <div className="file-details"><strong>{entry.file.name}</strong>
              <small>{fileTypeLabel(entry.file)} · {formatBytes(entry.file.size)}</small>
              {entry.error && <small className="file-error" role="alert">{entry.error}</small>}
            </div>
            {entry.state === "uploading" ? <div className="progress-wrap" aria-label={`Uploading ${entry.file.name}`}>
              <div className="progress-track"><span style={{ width: `${entry.progress}%` }} /></div><small>{entry.progress}%</small>
            </div> : entry.state === "sent" ? <span className="sent-label">Submitted</span>
              : entry.state === "failed" ? <button type="button" className="retry-button" disabled={busy}
                onClick={() => void retry(entry.uploadId)}>Retry</button> : <span className="waiting-label">Ready</span>}
            {!submissionStarted && <button type="button" className="remove-file-button" aria-label={`Remove ${entry.file.name}`}
              onClick={() => setSelectedFiles((current) => current.filter((file) => file.uploadId !== entry.uploadId))}>Remove</button>}
          </li>)}
        </ul>}
        {error && <p className="error-message" role="alert">{error}</p>}
        {!submissionCreated && <button className="submit-button" disabled={busy || selectedFiles.length === 0}>
          {busy ? "Preparing submission…" : submissionStarted ? "Retry submission" : "Submit documents"}
        </button>}
      </form>
      <div className="privacy-note"><span aria-hidden="true">◆</span><p>Files are sent to this shop's private storage. Print AIR does not ask for your phone number or create a tracking page.</p></div>
    </section>}
    <footer className="footer">Print AIR · Private document submission</footer>
  </main>;
}

function putFile(url: string, contentType: string, file: File, onProgress: (value: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open("PUT", url);
    request.setRequestHeader("Content-Type", contentType);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    request.onload = () => request.status >= 200 && request.status < 300
      ? resolve() : reject(new Error("Object upload failed."));
    request.onerror = () => reject(new Error("Object upload failed."));
    request.send(file);
  });
}

function contentTypeFor(file: File) {
  const extension = file.name.toLowerCase().split(".").pop();
  if (extension === "pdf") return "application/pdf";
  if (extension === "docx") return DOCX;
  return JPEG;
}

function isSupported(file: File) {
  const extension = file.name.toLowerCase().split(".").pop();
  return ["pdf", "docx", "jpg", "jpeg"].includes(extension ?? "");
}

function fileTypeLabel(file: File) {
  const extension = file.name.toLowerCase().split(".").pop();
  return extension === "pdf" ? "PDF" : extension === "docx" ? "DOCX" : "JPEG";
}

function formatBytes(bytes: number) {
  if (bytes < 1000) return `${bytes} B`;
  return bytes < 1_000_000 ? `${(bytes / 1000).toFixed(0)} KB` : `${(bytes / 1_000_000).toFixed(1)} MB`;
}
