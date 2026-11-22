import { Suspense, lazy, useCallback, useEffect, useMemo, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { api, ApiError } from "./api";
import type { Operator, PrintDocument } from "./types";

const DocumentPreview = lazy(() => import("./DocumentPreview"));

export default function Dashboard({ operator, onSessionExpired }: {
  operator: Operator;
  onSessionExpired: () => void;
}) {
  const [documents, setDocuments] = useState<PrintDocument[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [printRequest, setPrintRequest] = useState<{ documentId: string; token: number } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [qrOpen, setQrOpen] = useState(false);
  const [qrError, setQrError] = useState("");

  const loadDocuments = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const result = await api<PrintDocument[]>("/api/operator/documents");
      setDocuments(result);
      setSelectedId((current) => result.some((document) => document.id === current)
        ? current : result[0]?.id ?? "");
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onSessionExpired();
      else setError("Documents could not be loaded. Retry the request.");
    } finally {
      setLoading(false);
    }
  }, [onSessionExpired]);

  useEffect(() => { void loadDocuments(); }, [loadDocuments]);
  useEffect(() => {
    if (qrOpen) setQrError("");
  }, [qrOpen]);

  const selected = useMemo(() => documents.find((document) => document.id === selectedId), [documents, selectedId]);

  async function remove(document: PrintDocument) {
    if (!window.confirm(`Delete ${document.filename}? This cannot be undone.`)) return;
    setNotice("");
    try {
      await api<void>(`/api/operator/documents/${document.id}`, { method: "DELETE" });
      setNotice("Document deleted.");
      await loadDocuments();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onSessionExpired();
      else setError("Deletion failed. The document is still listed; retry when the service is available.");
    }
  }

  async function retryConversion(document: PrintDocument) {
    try {
      await api<void>(`/api/operator/documents/${document.id}/conversion/retry`, { method: "POST" });
      setNotice("Conversion queued for retry.");
      await loadDocuments();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onSessionExpired();
      else setError("Conversion could not be retried. Please try again.");
    }
  }

  async function logout() {
    try {
      await api<void>("/api/auth/logout", { method: "POST" });
      onSessionExpired();
    } catch {
      setError("Could not sign out. Retry the request.");
    }
  }

  function printDocument(document: PrintDocument) {
    setSelectedId(document.id);
    setPrintRequest((current) => ({ documentId: document.id, token: (current?.token ?? 0) + 1 }));
  }

  function qrSvgSource() {
    const svg = document.querySelector<SVGSVGElement>("#shop-submission-qr svg");
    if (!svg) {
      setQrError("The QR code is not ready yet. Close this panel and try again.");
      return "";
    }
    setQrError("");
    return new XMLSerializer().serializeToString(svg);
  }

  function downloadQr() {
    const source = qrSvgSource();
    if (!source) return;
    const objectUrl = URL.createObjectURL(new Blob([source], { type: "image/svg+xml;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = `print-air-${operator.shopSlug.replace(/[^a-z0-9-]/gi, "-")}-qr.svg`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  }

  function printQr() {
    const source = qrSvgSource();
    if (!source) return;
    const printWindow = window.open("", "_blank");
    if (!printWindow) {
      setQrError("Allow pop-ups to print the shop QR code.");
      return;
    }
    printWindow.opener = null;
    printWindow.document.open();
    printWindow.document.write(`<!doctype html>
      <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
      <title>${escapeHtml(operator.shopName)} submission QR</title>
      <style>
        :root { --font-body: "Segoe UI Variable", "Segoe UI", system-ui, sans-serif; }
        body { margin: 0; min-height: 100vh; display: grid; place-items: center; font: 16px var(--font-body); }
        main { display: grid; justify-items: center; gap: 16px; max-width: 90%; text-align: center; }
        svg { width: min(72vw, 320px); height: auto; }
        p { overflow-wrap: anywhere; }
        @media print { body { min-height: auto; } main { max-width: 100%; } svg { width: min(72vw, 320px); } }
      </style></head>
      <body><main><h1>${escapeHtml(operator.shopName)}</h1>${source}
      <p>${escapeHtml(operator.submissionUrl)}</p></main></body></html>`);
    printWindow.document.close();
    printWindow.focus();
    printWindow.print();
  }

  return (
    <main className="app-shell">
      <a className="skip-link" href="#documents-heading">Skip to incoming documents</a>
      <header className="topbar">
        <a className="brand-lockup" href="/" aria-label="Print AIR Shop Desk">
          <span>Print AIR</span>
        </a>
        <div className="shop-identity">
          <strong>{operator.shopName}</strong>
          <span>{operator.email}</span>
        </div>
        <div className="topbar-actions">
          <button type="button" className="quiet-button" aria-expanded={qrOpen} aria-controls="shop-qr-panel"
            onClick={() => setQrOpen((open) => !open)}>
            {qrOpen ? "Close QR" : "Shop QR"}
          </button>
          <button type="button" className="quiet-button" onClick={logout}>Sign out</button>
        </div>
      </header>
      {qrOpen && <section className="qr-panel" id="shop-qr-panel" aria-label="Shop submission QR">
        <div className="qr-details">
          <h2>Customer QR code</h2>
          <p>Customers scan this code to open the upload page for {operator.shopName}. It does not open existing documents.</p>
          <a className="qr-url" href={operator.submissionUrl}>{operator.submissionUrl}</a>
          <div className="qr-actions">
            <button type="button" className="primary-button" onClick={downloadQr}>Download QR</button>
            <button type="button" className="quiet-button" onClick={printQr}>Print QR</button>
          </div>
          {qrError && <p className="form-message error-message" role="alert">{qrError}</p>}
        </div>
        <figure className="qr-code-sheet">
          <div id="shop-submission-qr" role="img" aria-label={`Submission QR code for ${operator.shopName}`}>
            <QRCodeSVG value={operator.submissionUrl} size={192} level="M" />
          </div>
          <figcaption>{operator.shopName}</figcaption>
        </figure>
      </section>}
      <section className="workspace">
        <aside className="document-rail" aria-labelledby="documents-heading">
          <div className="rail-heading">
            <div><h1 id="documents-heading">Incoming documents</h1><p>Files sent to your shop.</p></div>
            <button type="button" className="quiet-button refresh-button" onClick={() => void loadDocuments()}>Refresh</button>
          </div>
          <p className="count-label" aria-live="polite">
            {documents.length} {documents.length === 1 ? "document" : "documents"}
          </p>
          {error && <div className="inline-error" role="alert">
            <span>{error}</span><button type="button" className="text-button" onClick={() => void loadDocuments()}>Retry</button>
          </div>}
          {notice && <p className="success-message" role="status">{notice}</p>}
          {loading ? <ul className="document-list loading-list" aria-label="Loading documents" role="status">
            <li /><li /><li />
          </ul> : documents.length === 0 ? <div className="empty-state">
            <h2>No documents yet</h2><p>New customer submissions will appear here.</p>
          </div> : <ul className="document-list">
            {documents.map((document) => <li key={document.id}>
              <button type="button" className={`document-row ${selectedId === document.id ? "selected" : ""}`}
                aria-pressed={selectedId === document.id} onClick={() => setSelectedId(document.id)}>
                <span className={`file-type ${fileClass(document.contentType)}`}>{fileLabel(document.contentType)}</span>
                <span className="file-info">
                  <strong>{document.filename}</strong>
                  <span>{document.customerName}, {formatBytes(document.sizeBytes)}</span>
                  <span>Submission {document.submissionId.slice(0, 8)}, {statusLabel(document.status)}</span>
                  <span>Expires {formatRemaining(document.expiresAt)}</span>
                </span>
                <time className="file-date" dateTime={document.uploadedAt}>
                  {new Date(document.uploadedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                </time>
              </button>
              <div className="row-actions">
                <div className="row-action-group">
                  <button type="button" className="text-button" onClick={() => setSelectedId(document.id)}>Preview</button>
                  <button type="button" className="text-button" disabled={document.status !== "READY"}
                    onClick={() => printDocument(document)}>Print</button>
                  {document.status === "CONVERSION_FAILED" && <button type="button" className="text-button"
                    onClick={() => void retryConversion(document)}>Retry conversion</button>}
                </div>
                <button type="button" className="text-button danger-text" onClick={() => void remove(document)}>Delete</button>
              </div>
            </li>)}
          </ul>}
        </aside>
        <section className="preview-area" aria-label="Document preview">
          {selected ? <Suspense fallback={<div className="preview-state" role="status">Loading preview tools…</div>}>
            <DocumentPreview key={selected.id} document={selected}
              printRequestToken={printRequest?.documentId === selected.id ? printRequest.token : undefined}
              onDelete={() => void remove(selected)} onRetry={() => void retryConversion(selected)}
              onSessionExpired={onSessionExpired} />
          </Suspense>
            : <div className="preview-empty">
              <h2>Select a document</h2><p>Choose a file to preview and print.</p>
            </div>}
        </section>
      </section>
    </main>
  );
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;",
  })[character] ?? character);
}

function formatBytes(bytes: number) {
  return bytes < 1_000_000 ? `${Math.max(1, Math.round(bytes / 1000))} KB` : `${(bytes / 1_000_000).toFixed(1)} MB`;
}

function formatRemaining(expiresAt: string) {
  const hours = Math.max(0, (new Date(expiresAt).getTime() - Date.now()) / 3_600_000);
  if (hours < 1) return "in less than an hour";
  if (hours < 24) return `in ${Math.ceil(hours)}h`;
  return `in ${Math.ceil(hours / 24)}d`;
}

function fileClass(contentType: string) {
  return contentType === "application/pdf" ? "pdf"
    : contentType === "image/jpeg" ? "jpeg" : "docx";
}

function fileLabel(contentType: string) {
  return contentType === "application/pdf" ? "PDF"
    : contentType === "image/jpeg" ? "JPEG" : "DOCX";
}

function statusLabel(status: PrintDocument["status"]) {
  return status === "READY" ? "Ready"
    : status === "CONVERSION_FAILED" ? "Conversion failed" : "Processing";
}
