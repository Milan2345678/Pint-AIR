import { useEffect, useRef, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import { ApiError } from "./api";
import type { PrintDocument } from "./types";

pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

export default function DocumentPreview({ document, printRequestToken, onDelete, onRetry, onSessionExpired }: {
  document: PrintDocument;
  printRequestToken?: number;
  onDelete: () => void;
  onRetry: () => void;
  onSessionExpired: () => void;
}) {
  const [pageCount, setPageCount] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState(false);
  const [documentKey, setDocumentKey] = useState(0);
  const [printState, setPrintState] = useState<"idle" | "loading" | "ready">("idle");
  const [printFailure, setPrintFailure] = useState(false);
  const [printUrl, setPrintUrl] = useState("");
  const printFrame = useRef<HTMLIFrameElement>(null);
  const handledPrintRequest = useRef<number | null>(null);
  const isJpeg = document.contentType === "image/jpeg";

  function startPrint() {
    if (printState === "ready") {
      printFrame.current?.contentWindow?.print();
      return;
    }
    setPrintFailure(false);
    setPrintState("loading");
    setPrintUrl(`/api/operator/documents/${document.id}/print`);
  }

  useEffect(() => {
    if (printRequestToken === undefined || document.status !== "READY"
      || handledPrintRequest.current === printRequestToken) return;
    handledPrintRequest.current = printRequestToken;
    startPrint();
  }, [document.id, document.status, printRequestToken]);

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
          <button className="primary-button" disabled={failure || loading || printState === "loading"}
            onClick={startPrint}>{printState === "loading" ? "Preparing…" : "Print"}</button>
        </div>
      </header>
      {printFailure && <p className="inline-error" role="alert">The document could not be loaded for printing. Retry the print action.</p>}
      {failure ? <div className="preview-error" role="alert"><h3>Preview could not be loaded</h3>
        <p>The document is still stored securely. Retry the preview when the connection is restored.</p>
        <button className="quiet-button" onClick={() => { setFailure(false); setLoading(true); setDocumentKey((key) => key + 1); }}>Retry preview</button>
      </div> : isJpeg ? <div className="image-stage">
        {loading && <div className="pdf-loading" role="status"><span className="spinner" />Loading preview…</div>}
        <img src={`/api/operator/documents/${document.id}/preview`} alt={document.filename}
          onLoad={() => setLoading(false)} onError={() => void handleError(new Error("Image preview failed"))} />
      </div> : <div className="pdf-stage">
        {loading && <div className="pdf-loading" role="status"><span className="spinner" />Loading preview…</div>}
        <Document key={documentKey} file={`/api/operator/documents/${document.id}/preview`}
          options={{ withCredentials: true }} onLoadSuccess={({ numPages }) => {
            setPageCount(numPages);
            setLoading(false);
          }} onLoadError={handleError}>
          <Page pageNumber={page} width={760} renderAnnotationLayer={false} renderTextLayer={false} />
        </Document>
      </div>}
      {!isJpeg && !failure && printState !== "loading" && pageCount > 1 && <footer className="page-controls">
        <button className="quiet-button" disabled={page === 1} onClick={() => setPage((value) => value - 1)}>Previous</button>
        <span>Page {page} of {pageCount}</span>
        <button className="quiet-button" disabled={page === pageCount} onClick={() => setPage((value) => value + 1)}>Next</button>
      </footer>}
      <p className="privacy-note">The original document is sent to this computer's print system. No file download is offered.</p>
      {printUrl && <iframe ref={printFrame} className="pdf-print-frame" title="Document print source"
        aria-hidden="true" tabIndex={-1}
        src={isJpeg ? undefined : printUrl}
        srcDoc={isJpeg ? imagePrintDocument(printUrl) : undefined}
        onLoad={() => {
          if (printState !== "loading") return;
          const frameDocument = printFrame.current?.contentDocument;
          const image = isJpeg ? frameDocument?.querySelector("img") : null;
          const loaded = isJpeg
            ? image?.complete && image.naturalWidth > 0 && image.naturalHeight > 0
            : frameDocument?.contentType === "application/pdf";
          if (!loaded) {
            setPrintState("idle");
            setPrintUrl("");
            setPrintFailure(true);
            return;
          }
          setPrintState("ready");
          printFrame.current?.contentWindow?.print();
        }} />}
    </div>
  );
}

function imagePrintDocument(imageUrl: string) {
  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>
@page { margin: 0.5in; }
html, body { width: 100%; height: 100%; margin: 0; background: #fff; }
body { display: grid; place-items: center; }
img {
  display: block;
  max-width: 100%;
  max-height: 100%;
  width: auto;
  height: auto;
  object-fit: contain;
  image-orientation: from-image;
  break-inside: avoid;
  page-break-inside: avoid;
}
</style>
</head>
<body><img src="${imageUrl}" alt=""></body>
</html>`;
}

function formatBytes(bytes: number) {
  return bytes < 1_000_000 ? `${Math.max(1, Math.round(bytes / 1000))} KB` : `${(bytes / 1_000_000).toFixed(1)} MB`;
}
