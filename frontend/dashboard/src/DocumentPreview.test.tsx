import { useEffect } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import DocumentPreview from "./DocumentPreview";
import type { PrintDocument } from "./types";

vi.mock("react-pdf", () => ({
  pdfjs: { GlobalWorkerOptions: { workerSrc: "" } },
  Document: ({ children, onLoadSuccess, onLoadError }: {
    children: React.ReactNode; onLoadSuccess: (document: { numPages: number }) => void;
    onLoadError: (error: Error) => void;
  }) => {
    useEffect(() => { onLoadSuccess({ numPages: 1 }); }, []);
    return <div data-testid="pdf-document"><button onClick={() => onLoadError(new Error("preview failed"))}>Fail preview</button>{children}</div>;
  },
  Page: ({ onRenderSuccess }: { onRenderSuccess: () => void }) => {
    useEffect(() => { onRenderSuccess(); }, []);
    return <div data-testid="pdf-page" />;
  },
}));

afterEach(() => cleanup());

const document: PrintDocument = {
  id: "doc-a",
  customerName: "Student",
  filename: "report.pdf",
  contentType: "application/pdf",
  sizeBytes: 100,
  uploadedAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
  status: "READY",
};

describe("document preview", () => {
  it("shows preview failure and a retry action", async () => {
    render(<DocumentPreview document={document} onDelete={vi.fn()} onRetry={vi.fn()}
      onSessionExpired={vi.fn()} />);
    await screen.findByTestId("pdf-document");
    fireEvent.click(screen.getByRole("button", { name: "Fail preview" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Preview could not be loaded");
    expect(screen.getByRole("button", { name: "Retry preview" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /download/i })).not.toBeInTheDocument();
  });

  it("starts browser printing after the PDF pages render", async () => {
    const print = vi.spyOn(window, "print").mockImplementation(() => {});
    render(<DocumentPreview document={document} onDelete={vi.fn()} onRetry={vi.fn()}
      onSessionExpired={vi.fn()} />);
    const printButton = await screen.findByRole("button", { name: "Print" });
    await waitFor(() => expect(printButton).toBeEnabled());
    fireEvent.click(printButton);
    expect(print).toHaveBeenCalled();
  });
});
