let csrfToken = "";
let csrfHeader = "X-XSRF-TOKEN";

export class ApiError extends Error {
  constructor(readonly status: number) {
    super(`Request failed (${status})`);
  }
}

async function getCsrf() {
  const response = await fetch("/api/auth/csrf", { credentials: "include" });
  if (!response.ok) throw new ApiError(response.status);
  const value = (await response.json()) as { token: string; headerName: string };
  csrfToken = value.token;
  csrfHeader = value.headerName;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    if (!csrfToken) await getCsrf();
    headers.set(csrfHeader, csrfToken);
  }
  const response = await fetch(path, { ...init, headers, credentials: "include" });
  if (!response.ok) throw new ApiError(response.status);
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
