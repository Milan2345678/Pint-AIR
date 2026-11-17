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
    useEffect(() => { onLoadSuccess({ numPages: 4 }); }, []);
    return <div data-testid="pdf-document"><button onClick={() => onLoadError(new Error("preview failed"))}>Fail preview</button>{children}</div>;
  },
  Page: () => <div data-testid="pdf-page" />,
}));

afterEach(() => cleanup());

const document: PrintDocument = {
  id: "doc-a",
  submissionId: "submission-a",
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

  it("prints the original PDF stream instead of the rendered preview canvas", async () => {
    const print = vi.spyOn(window, "print").mockImplementation(() => {});
    render(<DocumentPreview document={document} onDelete={vi.fn()} onRetry={vi.fn()}
      onSessionExpired={vi.fn()} />);
    const printButton = await screen.findByRole("button", { name: "Print" });
    await waitFor(() => expect(printButton).toBeEnabled());
    expect(screen.getAllByTestId("pdf-page")).toHaveLength(1);

    fireEvent.click(printButton);
    const frame = await screen.findByTitle("Document print source") as HTMLIFrameElement;
    expect(frame).toHaveAttribute("src", "/api/operator/documents/doc-a/print");
    Object.defineProperty(frame.contentDocument!, "contentType", { configurable: true, value: "application/pdf" });
    const framePrint = vi.spyOn(frame.contentWindow!, "print").mockImplementation(() => {});
    fireEvent.load(frame);

    expect(framePrint).toHaveBeenCalledOnce();
    expect(print).not.toHaveBeenCalled();
    expect(screen.getAllByTestId("pdf-page")).toHaveLength(1);
  });

  it("prints the selected row's document when a row Print action requests it", async () => {
    render(<DocumentPreview document={document} printRequestToken={1} onDelete={vi.fn()} onRetry={vi.fn()}
      onSessionExpired={vi.fn()} />);
    const frame = await screen.findByTitle("Document print source") as HTMLIFrameElement;
    Object.defineProperty(frame.contentDocument!, "contentType", { configurable: true, value: "application/pdf" });
    const framePrint = vi.spyOn(frame.contentWindow!, "print").mockImplementation(() => {});
    fireEvent.load(frame);

    expect(frame).toHaveAttribute("src", "/api/operator/documents/doc-a/print");
    expect(framePrint).toHaveBeenCalledOnce();
  });

  it("uses the server-provided PDF print stream for converted DOCX documents", async () => {
    const docx = { ...document, filename: "report.docx",
      contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" };
    render(<DocumentPreview document={docx} onDelete={vi.fn()} onRetry={vi.fn()}
      onSessionExpired={vi.fn()} />);
    const printButton = await screen.findByRole("button", { name: "Print" });
    await waitFor(() => expect(printButton).toBeEnabled());

    fireEvent.click(printButton);

    expect(await screen.findByTitle("Document print source")).toHaveAttribute(
      "src", "/api/operator/documents/doc-a/print");
  });

  it("previews JPEGs and creates a page-fitted print document from the original image", async () => {
    const jpeg = { ...document, filename: "photo.jpeg", contentType: "image/jpeg" };
    render(<DocumentPreview document={jpeg} onDelete={vi.fn()} onRetry={vi.fn()}
      onSessionExpired={vi.fn()} />);
    const previewImage = await screen.findByRole("img", { name: "photo.jpeg" });
    expect(previewImage).toHaveAttribute("src", "/api/operator/documents/doc-a/preview");

    fireEvent.load(previewImage);
    const printButton = screen.getByRole("button", { name: "Print" });
    await waitFor(() => expect(printButton).toBeEnabled());
    fireEvent.click(printButton);
    const frame = await screen.findByTitle("Document print source") as HTMLIFrameElement;
    expect(frame).not.toHaveAttribute("src");
    const printDocument = frame.getAttribute("srcdoc")!;
    expect(printDocument).toContain('<img src="/api/operator/documents/doc-a/print"');
    expect(printDocument).toContain("@page { margin: 0.5in; }");
    expect(printDocument).toContain("max-width: 100%");
    expect(printDocument).toContain("max-height: 100%");
    expect(printDocument).toContain("object-fit: contain");
    expect(printDocument).toContain("image-orientation: from-image");
    expect(printDocument).toContain("page-break-inside: avoid");
    expect(screen.queryByTestId("pdf-page")).not.toBeInTheDocument();
  });

  it("does not print an error response returned by the print endpoint", async () => {
    render(<DocumentPreview document={document} onDelete={vi.fn()} onRetry={vi.fn()}
      onSessionExpired={vi.fn()} />);
    const printButton = await screen.findByRole("button", { name: "Print" });
    await waitFor(() => expect(printButton).toBeEnabled());
    fireEvent.click(printButton);
    const frame = await screen.findByTitle("Document print source");

    fireEvent.load(frame);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The document could not be loaded for printing");
    expect(screen.queryByTitle("Document print source")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Print" })).toBeEnabled();
  });
});
