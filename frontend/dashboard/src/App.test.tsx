import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import App from "./App";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("dashboard session and document states", () => {
  it("shows a loading state while checking the operator session", async () => {
    let resolveSession!: (value: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { resolveSession = resolve; })));

    render(<App />);
    expect(screen.getByRole("status")).toHaveTextContent("Checking your session");
    resolveSession(new Response(null, { status: 401 }));
    expect(await screen.findByRole("heading", { name: "Sign in to your shop" })).toBeInTheDocument();
  });

  it("shows a session-expired message after an unauthorized dashboard request", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ email: "operator@example.test", shopName: "Cafe",
        shopSlug: "cafe", submissionUrl: "http://localhost:5174/shop/cafe" }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("Your session expired. Sign in again.")).toBeInTheDocument();
  });

  it("renders the empty state when the shop has no documents", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ email: "operator@example.test", shopName: "Cafe",
        shopSlug: "cafe", submissionUrl: "http://localhost:5174/shop/cafe" }))
      .mockResolvedValueOnce(Response.json([]));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("No documents yet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Shop QR" })).toBeInTheDocument();
  });

  it("offers retry when document loading fails", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ email: "operator@example.test", shopName: "Cafe",
        shopSlug: "cafe", submissionUrl: "http://localhost:5174/shop/cafe" }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(Response.json([]));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Documents could not be loaded");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No documents yet")).toBeInTheDocument();
  });

  it("shows a login error for rejected credentials", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ token: "csrf-token", headerName: "X-XSRF-TOKEN" }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);
    fireEvent.change(await screen.findByLabelText("Email address"), { target: { value: "operator@example.test" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Email or password is incorrect");
  });
});
