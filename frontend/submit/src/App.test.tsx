import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import App from "./App";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function response(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function setupUploadMocks(failingContent?: string) {
  const uploaded: string[] = [];
  const requests: Array<{ url: string; body: string }> = [];
  let nextId = 0;
  vi.stubGlobal("crypto", { randomUUID: () => `uuid-${++nextId}` });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "/api/auth/csrf") return response({ token: "csrf", headerName: "X-XSRF-TOKEN" });
    if (url.endsWith("/submissions")) {
      const body = JSON.parse(String(init?.body)) as {
        files: Array<{ uploadId: string; contentType: string }>;
      };
      requests.push({ url, body: String(init?.body) });
      return response({ uploads: body.files.map((file) => ({
        uploadId: file.uploadId, url: `upload://${file.uploadId}`, contentType: file.contentType,
        expiresAt: new Date().toISOString(), status: "PENDING",
      })) });
    }
    if (url.endsWith("/retry")) {
      const uploadId = url.split("/").at(-2)!;
      return response({ uploadId, url: `retry://${uploadId}`, contentType: "application/pdf",
        expiresAt: new Date().toISOString(), status: "PENDING" });
    }
    if (url.endsWith("/finalize")) return response({ result: "accepted" });
    throw new Error(`Unexpected request: ${url}`);
  }));
  class FakeXMLHttpRequest {
    upload = { onprogress: null as ((event: ProgressEvent) => void) | null };
    status = 200;
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    url = "";
    open(_method: string, url: string) { this.url = url; }
    setRequestHeader() {}
    send(file: File) {
      uploaded.push(file.name);
      this.upload.onprogress?.({ lengthComputable: true, loaded: file.size, total: file.size } as ProgressEvent);
      this.status = file.name === failingContent && this.url.startsWith("upload://") ? 500 : 200;
      queueMicrotask(() => this.onload?.());
    }
  }
  vi.stubGlobal("XMLHttpRequest", FakeXMLHttpRequest);
  return { uploaded, requests };
}

function openShop() {
  window.history.pushState({}, "", "/shop/cafe");
  render(<App />);
}

describe("customer submission", () => {
  it("asks only for a name and accepts the supported document formats", () => {
    openShop();

    expect(screen.getByLabelText("Your name")).toBeInTheDocument();
    const picker = screen.getByLabelText(/Documents/);
    expect(picker.getAttribute("accept")).toContain(".pdf");
    expect(picker.getAttribute("accept")).toContain(".docx");
    expect(picker.getAttribute("accept")).toContain(".jpg");
    expect(picker).toHaveAttribute("multiple");
    expect(screen.queryByLabelText(/phone/i)).not.toBeInTheDocument();
  });

  it("adds and removes files before submitting", () => {
    openShop();
    const picker = screen.getByLabelText(/Documents/);
    const first = new File(["pdf"], "resume.pdf", { type: "application/pdf" });
    const second = new File(["jpg"], "photo.jpg", { type: "image/jpeg" });
    fireEvent.change(picker, { target: { files: [first] } });
    fireEvent.change(picker, { target: { files: [second] } });

    expect(screen.getByText("resume.pdf")).toBeInTheDocument();
    expect(screen.getByText("photo.jpg")).toBeInTheDocument();
    expect(screen.getByText("PDF · 3 B")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Remove resume.pdf" }));
    expect(screen.queryByText("resume.pdf")).not.toBeInTheDocument();
    expect(screen.getByText("photo.jpg")).toBeInTheDocument();
  });

  it("rejects unsupported file types before creating a submission", () => {
    vi.stubGlobal("fetch", vi.fn());
    openShop();
    fireEvent.change(screen.getByLabelText(/Documents/), {
      target: { files: [new File(["image"], "photo.png", { type: "image/png" })] },
    });

    expect(screen.getByRole("alert")).toHaveTextContent("PDF, DOCX, JPG, or JPEG");
    expect(fetch).not.toHaveBeenCalled();
  });

  it("creates one submission for mixed files and uploads each file independently", async () => {
    const { uploaded, requests } = setupUploadMocks();
    openShop();
    fireEvent.change(screen.getByLabelText("Your name"), { target: { value: "Student" } });
    fireEvent.change(screen.getByLabelText(/Documents/), {
      target: { files: [
        new File(["pdf"], "resume.pdf", { type: "application/pdf" }),
        new File(["docx"], "assignment.docx", { type: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" }),
        new File(["jpeg"], "photo.jpeg", { type: "image/jpeg" }),
      ] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Submit documents" }));

    expect(await screen.findByText("Your documents are on their way.")).toBeInTheDocument();
    expect(requests).toHaveLength(1);
    expect(JSON.parse(requests[0].body).files).toHaveLength(3);
    expect(uploaded).toEqual(["resume.pdf", "assignment.docx", "photo.jpeg"]);
  });

  it("retries only the failed file within the original submission", async () => {
    const { uploaded, requests } = setupUploadMocks("failed.pdf");
    openShop();
    fireEvent.change(screen.getByLabelText("Your name"), { target: { value: "Student" } });
    fireEvent.change(screen.getByLabelText(/Documents/), {
      target: { files: [
        new File(["good"], "good.pdf", { type: "application/pdf" }),
        new File(["bad"], "failed.pdf", { type: "application/pdf" }),
      ] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Submit documents" }));

    const retryButton = await screen.findByRole("button", { name: "Retry" });
    const firstSubmission = JSON.parse(requests[0].body);
    const failedRow = retryButton.closest("li");
    expect(failedRow).not.toBeNull();
    expect(within(failedRow!).getByText("failed.pdf")).toBeInTheDocument();
    fireEvent.click(retryButton);

    await waitFor(() => expect(screen.getByText("Submitted")).toBeInTheDocument());
    expect(uploaded).toEqual(["good.pdf", "failed.pdf", "failed.pdf"]);
    expect(requests).toHaveLength(1);
    expect(JSON.parse(requests[0].body).submissionId).toBe(firstSubmission.submissionId);
  });

  it("rejects links without a shop slug", () => {
    window.history.pushState({}, "", "/");
    render(<App />);

    expect(screen.getByRole("heading", { name: "Invalid shop link" })).toBeInTheDocument();
  });
});
