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
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [qrOpen, setQrOpen] = useState(false);

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

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="Print AIR dashboard"><span className="brand-mark small">PA</span><span>Print AIR</span></a>
        <div className="shop-identity"><span>{operator.shopName}</span><span className="operator-email">{operator.email}</span></div>
        <button className="quiet-button" onClick={() => setQrOpen((open) => !open)}>Shop QR</button>
        <button className="quiet-button" onClick={logout}>Sign out</button>
      </header>
      {qrOpen && <section className="qr-card" aria-label="Shop submission QR">
        <div><h2>Customer submission link</h2><p>Scan to open this shop's upload page. It does not provide access to submitted documents.</p>
          <a href={operator.submissionUrl}>{operator.submissionUrl}</a></div>
        <QRCodeSVG value={operator.submissionUrl} size={132} level="M" />
      </section>}
      <section className="workspace">
        <aside className="document-rail">
          <div className="rail-heading"><div><p className="eyebrow">INCOMING FILES</p><h1>Documents</h1></div>
            <button className="icon-button" aria-label="Refresh documents" onClick={() => void loadDocuments()}>↻</button></div>
          <p className="muted count-label">{documents.length} available</p>
          {error && <div className="inline-error" role="alert"><span>{error}</span><button className="text-button" onClick={() => void loadDocuments()}>Retry</button></div>}
          {notice && <p className="success-message" role="status">{notice}</p>}
          {loading ? <p className="rail-state" role="status">Loading documents…</p>
            : documents.length === 0 ? <div className="empty-state"><span className="empty-icon">▤</span><h2>No documents yet</h2><p>New customer submissions will appear here.</p></div>
              : <ul className="document-list">
                {documents.map((document) => <li key={document.id}>
                  <button className={`document-row ${selectedId === document.id ? "selected" : ""}`}
                    onClick={() => setSelectedId(document.id)}>
                    <span className={`file-icon ${document.contentType === "application/pdf" ? "pdf" : "docx"}`}>
                      {document.contentType === "application/pdf" ? "PDF" : "DOCX"}</span>
                    <span className="file-info"><strong>{document.filename}</strong><span>{document.customerName} · {formatBytes(document.sizeBytes)}</span>
                      <span>Expires {formatRemaining(document.expiresAt)}</span></span>
                    <span className="file-date">{new Date(document.uploadedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
                  </button>
                  <div className="row-actions">
                    {document.status === "CONVERSION_FAILED" && <button className="text-button" onClick={() => void retryConversion(document)}>Retry conversion</button>}
                    <button className="text-button danger-text" onClick={() => void remove(document)}>Delete</button>
                  </div>
                </li>)}
              </ul>}
        </aside>
        <section className="preview-area" aria-label="Document preview">
          {selected ? <Suspense fallback={<div className="preview-state" role="status">Loading preview tools…</div>}>
            <DocumentPreview key={selected.id} document={selected}
              onDelete={() => void remove(selected)} onRetry={() => void retryConversion(selected)}
              onSessionExpired={onSessionExpired} />
          </Suspense>
            : <div className="preview-empty"><span>▧</span><h2>Select a document</h2><p>Choose a file to preview and print.</p></div>}
        </section>
      </section>
    </main>
  );
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
