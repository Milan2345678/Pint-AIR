import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { api, ApiError } from "./api";
import type { Operator } from "./types";
import Dashboard from "./Dashboard";

export default function App() {
  const [operator, setOperator] = useState<Operator | null>(null);
  const [checking, setChecking] = useState(true);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [error, setError] = useState("");

  async function loadSession() {
    setChecking(true);
    try {
      setOperator(await api<Operator>("/api/auth/me"));
      setSessionExpired(false);
      setError("");
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        setOperator(null);
      } else {
        setError("Could not connect. Check the connection and retry.");
      }
    } finally {
      setChecking(false);
    }
  }

  useEffect(() => {
    void loadSession();
  }, []);

  async function login(email: string, password: string) {
    try {
      await api<void>("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ email, password }),
      });
      await loadSession();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        setError("Email or password is incorrect.");
      } else {
        setError("Sign-in failed. Check your connection and try again.");
      }
    }
  }

  if (checking) return <main className="center-state" role="status">Checking your session…</main>;
  if (!operator) {
    return <Login onLogin={login} error={error} onRetry={loadSession} sessionExpired={sessionExpired} />;
  }
  return <Dashboard operator={operator} onSessionExpired={() => {
    setSessionExpired(true);
    setOperator(null);
  }} />;
}

function Login({ onLogin, error, onRetry, sessionExpired }: {
  onLogin: (email: string, password: string) => void;
  error: string;
  onRetry: () => void;
  sessionExpired: boolean;
}) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    try {
      await onLogin(email, password);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-shell">
      <section className="login-card">
        <div className="brand-mark" aria-hidden="true">PA</div>
        <p className="eyebrow">PRINT AIR · SHOP DESK</p>
        <h1>Sign in to your shop</h1>
        <p className="muted">{sessionExpired ? "Your session expired. Sign in again." : "Access documents submitted to your shop."}</p>
        <form onSubmit={submit}>
          <label>Email address<input type="email" autoComplete="username" required value={email}
            onChange={(event) => setEmail(event.target.value)} /></label>
          <label>Password<input type="password" autoComplete="current-password" required value={password}
            onChange={(event) => setPassword(event.target.value)} /></label>
          {error && <p className="error-message" role="alert">{error}</p>}
          <button className="primary-button wide" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
          {error.startsWith("Could not connect") && <button type="button" className="text-button" onClick={onRetry}>Retry connection</button>}
        </form>
      </section>
    </main>
  );
}
