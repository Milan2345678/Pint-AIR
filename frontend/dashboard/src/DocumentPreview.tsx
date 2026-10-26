import { useEffect, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import { ApiError } from "./api";
import type { PrintDocument } from "./types";

pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

export default function DocumentPreview({ document, onDelete, onRetry, onSessionExpired }: {
  document: PrintDocument;
  onDelete: () => void;
  onRetry: () => void;
  onSessionExpired: () => void;
}) {
  const [pageCount, setPageCount] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState(false);
  const [printing, setPrinting] = useState(false);
  const [renderedPages, setRenderedPages] = useState(0);
  const [documentKey, setDocumentKey] = useState(0);

  useEffect(() => {
    if (printing && pageCount > 0 && renderedPages >= pageCount) {
      window.print();
      setPrinting(false);
      setRenderedPages(0);
      setDocumentKey((key) => key + 1);
      setPage(1);
    }
  }, [printing, pageCount, renderedPages]);

  if (document.status === "CONVERSION_FAILED") {
    return <div className="preview-state"><span className="state-symbol warning">!</span><h2>Preview unavailable</h2>
      <p>This DOCX could not be converted. Try the conversion again or delete it.</p>
      <button className="primary-button" onClick={onRetry}>Retry conversion</button></div>;
  }
  if (document.status !== "READY") {
    return <div className="preview-state" role="status"><span className="spinner" /><h2>Preparing preview</h2>
      <p>The document is not ready yet. This view will update after conversion.</p>
      <button className="quiet-button" onClick={() => window.location.reload()}>Refresh</button></div>;
  }

  async function handleError(error: Error) {
    setLoading(false);
    if (error instanceof ApiError && error.status === 401) onSessionExpired();
    else {
      try {
        const response = await fetch("/api/auth/me", { credentials: "include" });
        if (response.status === 401) onSessionExpired();
      } catch {
        setFailure(true);
        return;
      }
      setFailure(true);
    }
  }

  return (
    <div className="preview-panel">
      <header className="preview-header">
        <div><p className="eyebrow">DOCUMENT PREVIEW</p><h2>{document.filename}</h2><p>{document.customerName} · {formatBytes(document.sizeBytes)}</p></div>
        <div className="preview-actions">
          <button className="quiet-button" onClick={onDelete}>Delete</button>
          <button className="primary-button" disabled={failure || loading} onClick={() => {
            setPrinting(true);
            setRenderedPages(0);
            setDocumentKey((key) => key + 1);
          }}>Print</button>
        </div>
      </header>
      {failure ? <div className="preview-error" role="alert"><h3>Preview could not be loaded</h3>
        <p>The document is still stored securely. Retry the preview when the connection is restored.</p>
        <button className="quiet-button" onClick={() => { setFailure(false); setLoading(true); setDocumentKey((key) => key + 1); }}>Retry preview</button>
      </div> : <div className="pdf-stage">
        {loading && <div className="pdf-loading" role="status"><span className="spinner" />Loading preview…</div>}
        <Document key={documentKey} file={`/api/operator/documents/${document.id}/preview`}
          options={{ withCredentials: true }} onLoadSuccess={({ numPages }) => {
            setPageCount(numPages);
            setLoading(false);
          }} onLoadError={handleError}>
          {Array.from({ length: printing ? pageCount : 1 }, (_, index) => printing ? index + 1 : page).map((pageNumber) =>
            <Page key={pageNumber} pageNumber={pageNumber} width={760}
              renderAnnotationLayer={false} renderTextLayer={false}
              onRenderSuccess={() => printing && setRenderedPages((count) => count + 1)} />)}
        </Document>
      </div>}
      {!failure && !printing && pageCount > 1 && <footer className="page-controls">
        <button className="quiet-button" disabled={page === 1} onClick={() => setPage((value) => value - 1)}>Previous</button>
        <span>Page {page} of {pageCount}</span>
        <button className="quiet-button" disabled={page === pageCount} onClick={() => setPage((value) => value + 1)}>Next</button>
      </footer>}
      <p className="privacy-note">Printing sends rendered pages to this computer's print system. No file download is offered.</p>
    </div>
  );
}

function formatBytes(bytes: number) {
  return bytes < 1_000_000 ? `${Math.max(1, Math.round(bytes / 1000))} KB` : `${(bytes / 1_000_000).toFixed(1)} MB`;
}
