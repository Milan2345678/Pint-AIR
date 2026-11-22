import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import App from "./App";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.pushState({}, "", "/");
});

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

  it("registers a cafe and asks the operator to verify the email address", async () => {
    const requests: Array<{ url: string; body: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/auth/me") return new Response(null, { status: 401 });
      if (url === "/api/auth/csrf") return Response.json({ token: "csrf-token", headerName: "X-XSRF-TOKEN" });
      if (url === "/api/auth/register") {
        requests.push({ url, body: String(init?.body) });
        return new Response(null, { status: 204 });
      }
      throw new Error(`Unexpected request: ${url}`);
    }));

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Create a cafe account" }));
    fireEvent.change(screen.getByLabelText("Cafe or shop name"), { target: { value: "Paper Corner" } });
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "owner@example.test" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-very-long-password" } });
    fireEvent.change(screen.getByLabelText("Confirm password"), { target: { value: "a-very-long-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Create cafe account" }));

    expect(await screen.findByRole("heading", { name: "Check your inbox" })).toBeInTheDocument();
    expect(JSON.parse(requests[0].body)).toEqual({
      shopName: "Paper Corner",
      email: "owner@example.test",
      password: "a-very-long-password",
    });
  });

  it("verifies the one-time token from the email link", async () => {
    const requests: Array<{ url: string; body: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/auth/csrf") return Response.json({ token: "csrf-token", headerName: "X-XSRF-TOKEN" });
      if (url === "/api/auth/verify-email") {
        requests.push({ url, body: String(init?.body) });
        return new Response(null, { status: 204 });
      }
      throw new Error(`Unexpected request: ${url}`);
    }));
    window.history.pushState({}, "", "/verify-email#token=one-time-token");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Email verified" })).toBeInTheDocument();
    expect(JSON.parse(requests[0].body)).toEqual({ token: "one-time-token" });
  });
});
