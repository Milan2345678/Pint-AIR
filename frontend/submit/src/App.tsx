import { useMemo, useState } from "react";
import type { FormEvent } from "react";
import { api } from "./api";

type FileStatus = { file: File; progress: number; state: "waiting" | "uploading" | "sent" | "failed" };
type UploadGrant = { uploadId: string; url: string; contentType: string; expiresAt: string };

const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

export default function App() {
  const slug = useMemo(() => {
    const match = window.location.pathname.match(/^\/shop\/([^/]+)\/?$/);
    return match ? decodeURIComponent(match[1]) : "";
  }, []);
  const [name, setName] = useState("");
  const [selectedFiles, setSelectedFiles] = useState<FileStatus[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);

  function chooseFiles(fileList: FileList | null) {
    setError("");
    setSuccess(false);
    const files = Array.from(fileList ?? []);
    if (files.some((file) => !isSupported(file))) {
      setSelectedFiles([]);
      setError("Choose PDF or DOCX documents only.");
      return;
    }
    if (files.length > 5) {
      setSelectedFiles([]);
      setError("Choose up to five documents at a time.");
      return;
    }
    setSelectedFiles(files.map((file) => ({ file, progress: 0, state: "waiting" })));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!name.trim() || selectedFiles.length === 0) {
      setError("Enter your name and select at least one document.");
      return;
    }
    setBusy(true);
    setError("");
    setSuccess(false);
    await Promise.all(selectedFiles.map(async (entry, index) => {
      updateFile(index, { ...entry, progress: 0, state: "uploading" });
      try {
        await uploadOne(slug, name.trim(), entry.file, (progress) =>
          updateFile(index, { ...entry, progress, state: "uploading" }));
        updateFile(index, { ...entry, progress: 100, state: "sent" });
      } catch {
        updateFile(index, { ...entry, state: "failed" });
      }
    }));
    setBusy(false);
    setSuccess(true);
  }

  function updateFile(index: number, status: FileStatus) {
    setSelectedFiles((current) => current.map((entry, itemIndex) => itemIndex === index ? status : entry));
  }

  async function retry(index: number) {
    const entry = selectedFiles[index];
    setBusy(true);
    setError("");
    updateFile(index, { ...entry, progress: 0, state: "uploading" });
    try {
      await uploadOne(slug, name.trim(), entry.file, (progress) =>
        updateFile(index, { ...entry, progress, state: "uploading" }));
      updateFile(index, { ...entry, progress: 100, state: "sent" });
    } catch {
      updateFile(index, { ...entry, state: "failed" });
      setError("Upload failed. Check your connection and retry this document.");
    } finally {
      setBusy(false);
    }
  }

  if (!slug) return <main className="page-wrap"><section className="card"><h1>Invalid shop link</h1>
    <p>Scan the current QR code at the print shop or check the link with the shop operator.</p></section></main>;

  return <main className="page-wrap">
    <header className="submit-brand"><span className="brand-mark">PA</span><span>Print AIR</span></header>
    {success && selectedFiles.every((entry) => entry.state === "sent") ? <section className="card success-card" role="status">
      <span className="success-icon">✓</span><p className="eyebrow">SUBMISSION COMPLETE</p>
      <h1>Your documents are on their way.</h1>
      <p>The shop can now access the submitted files. This page does not provide document tracking.</p>
    </section> : <section className="card">
      <p className="eyebrow">PRIVATE DOCUMENT SUBMISSION</p>
      <h1>Send files to the print shop</h1>
      <p className="intro">Enter your name and choose PDF or DOCX files. Your phone number is not requested.</p>
      <form onSubmit={submit}>
        <label>Your name<input value={name} required maxLength={100} autoComplete="name"
          onChange={(event) => setName(event.target.value)} placeholder="Name for the shop operator" /></label>
        <label className="file-picker">Documents
          <input type="file" accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            multiple disabled={busy} onChange={(event) => chooseFiles(event.target.files)} />
          <span className="picker-box"><span className="upload-icon">↑</span><strong>Choose documents</strong>
            <small>PDF or DOCX · up to 5 files</small></span>
        </label>
        {selectedFiles.length > 0 && <ul className="file-list" aria-label="Selected documents">
          {selectedFiles.map((entry, index) => <li key={`${entry.file.name}-${index}`}>
            <div className="file-details"><strong>{entry.file.name}</strong><small>{formatBytes(entry.file.size)}</small></div>
            {entry.state === "uploading" ? <div className="progress-wrap" aria-label={`Uploading ${entry.file.name}`}>
              <div className="progress-track"><span style={{ width: `${entry.progress}%` }} /></div><small>{entry.progress}%</small>
            </div> : entry.state === "sent" ? <span className="sent-label">Submitted</span>
              : entry.state === "failed" ? <button type="button" className="retry-button" disabled={busy}
                onClick={() => void retry(index)}>Retry</button> : <span className="waiting-label">Ready</span>}
          </li>)}
        </ul>}
        {error && <p className="error-message" role="alert">{error}</p>}
        <button className="submit-button" disabled={busy || selectedFiles.length === 0}>
          {busy ? "Uploading documents…" : "Submit documents"}
        </button>
      </form>
      <div className="privacy-note"><span aria-hidden="true">◆</span><p>Files are sent to this shop's private storage. Print AIR does not ask for your phone number or create a tracking page.</p></div>
    </section>}
    <footer className="footer">Print AIR · Private document submission</footer>
  </main>;
}

async function uploadOne(slug: string, customerName: string, file: File, onProgress: (value: number) => void) {
  const contentType = file.type || (file.name.toLowerCase().endsWith(".pdf") ? "application/pdf" : DOCX);
  const response = await api<{ uploads: UploadGrant[] }>(`/api/public/shops/${encodeURIComponent(slug)}/submissions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ customerName, files: [{ fileName: file.name, contentType, sizeBytes: file.size }] }),
  });
  const grant = response.uploads[0];
  if (!grant) throw new Error("Upload permission was not returned.");
  await putFile(grant, file, onProgress);
  await api<void>(`/api/public/uploads/${grant.uploadId}/finalize`, { method: "POST" });
}

function putFile(grant: UploadGrant, file: File, onProgress: (value: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open("PUT", grant.url);
    request.setRequestHeader("Content-Type", grant.contentType);
    request.timeout = 120_000;
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    request.onload = () => request.status >= 200 && request.status < 300
      ? resolve() : reject(new Error("Object upload failed."));
    request.onerror = () => reject(new Error("Object upload failed."));
    request.ontimeout = () => reject(new Error("Upload timed out."));
    request.send(file);
  });
}

function isSupported(file: File) {
  return file.name.toLowerCase().endsWith(".pdf") || file.name.toLowerCase().endsWith(".docx");
}

function formatBytes(bytes: number) {
  return bytes < 1_000_000 ? `${Math.max(1, Math.round(bytes / 1000))} KB` : `${(bytes / 1_000_000).toFixed(1)} MB`;
}
