import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import Dashboard from "./Dashboard";
import type { PrintDocument } from "./types";

vi.mock("./DocumentPreview", () => ({
  default: ({ document, printRequestToken }: { document: PrintDocument; printRequestToken?: number }) =>
    <div data-testid="selected-document" data-document-id={document.id} data-print-token={printRequestToken ?? ""} />,
}));

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Shop Desk document actions", () => {
  it("routes an individual row Print action only to that document", async () => {
    const documents: PrintDocument[] = ["doc-a", "doc-b"].map((id) => ({
      id,
      submissionId: "submission-a",
      customerName: "Student",
      filename: `${id}.pdf`,
      contentType: "application/pdf",
      sizeBytes: 100,
      uploadedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      status: "READY",
    }));
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(documents)));
    render(<Dashboard operator={{
      email: "operator@example.test",
      shopName: "Cafe",
      shopSlug: "cafe",
      submissionUrl: "http://localhost:5174/shop/cafe",
    }} onSessionExpired={vi.fn()} />);

    await screen.findByText("doc-a.pdf");
    const rows = screen.getAllByRole("listitem");
    fireEvent.click(within(rows[1]).getByRole("button", { name: "Print" }));

    const selected = await screen.findByTestId("selected-document");
    expect(selected).toHaveAttribute("data-document-id", "doc-b");
    expect(selected).toHaveAttribute("data-print-token", "1");

    fireEvent.click(within(rows[0]).getByRole("button", { name: "Print" }));
    expect(await screen.findByTestId("selected-document")).toHaveAttribute("data-document-id", "doc-a");
    expect(screen.getByTestId("selected-document")).toHaveAttribute("data-print-token", "2");
  });
});
