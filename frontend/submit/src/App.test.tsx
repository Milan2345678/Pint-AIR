import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import App from "./App";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("customer submission", () => {
  it("asks only for a name and supported documents", () => {
    window.history.pushState({}, "", "/shop/cafe");
    render(<App />);

    expect(screen.getByLabelText("Your name")).toBeInTheDocument();
    expect(screen.getByLabelText(/Documents/).getAttribute("accept")).toContain(".pdf");
    expect(screen.queryByLabelText(/phone/i)).not.toBeInTheDocument();
  });

  it("rejects unsupported file types before sending a request", () => {
    window.history.pushState({}, "", "/shop/cafe");
    vi.stubGlobal("fetch", vi.fn());
    render(<App />);
    const file = new File(["image"], "photo.png", { type: "image/png" });

    fireEvent.change(screen.getByLabelText(/Documents/), { target: { files: [file] } });

    expect(screen.getByRole("alert")).toHaveTextContent("PDF or DOCX");
    expect(fetch).not.toHaveBeenCalled();
  });

  it("rejects links without a shop slug", () => {
    window.history.pushState({}, "", "/");
    render(<App />);

    expect(screen.getByRole("heading", { name: "Invalid shop link" })).toBeInTheDocument();
  });
});
