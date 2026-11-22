import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { api, ApiError } from "./api";
import type { Operator } from "./types";
import Dashboard from "./Dashboard";

type AuthMode = "login" | "register";
type VerificationState = "checking" | "verified" | "failed";

export default function App() {
  const [operator, setOperator] = useState<Operator | null>(null);
  const [checking, setChecking] = useState(true);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [error, setError] = useState("");
  const [verification, setVerification] = useState<VerificationState>("checking");
  const verificationStarted = useRef(false);
  const isVerificationRoute = window.location.pathname.replace(/\/+$/, "") === "/verify-email";
  const verificationToken = new URLSearchParams(window.location.hash.slice(1)).get("token");

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
    if (!isVerificationRoute) {
      void loadSession();
      return;
    }
    if (verificationStarted.current) return;
    verificationStarted.current = true;
    window.history.replaceState(null, "", "/verify-email");
    if (!verificationToken) {
      setVerification("failed");
      return;
    }
    void api<void>("/api/auth/verify-email", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: verificationToken }),
    }).then(() => setVerification("verified"))
      .catch(() => setVerification("failed"));
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

  if (isVerificationRoute) return <VerificationPage state={verification} />;
  if (checking) return <main className="center-state" role="status">Checking your session…</main>;
  if (!operator) {
    return <AuthPanel onLogin={login} error={error} onRetry={loadSession} sessionExpired={sessionExpired} />;
  }
  return <Dashboard operator={operator} onSessionExpired={() => {
    setSessionExpired(true);
    setOperator(null);
  }} />;
}

function AuthPanel({ onLogin, error, onRetry, sessionExpired }: {
  onLogin: (email: string, password: string) => Promise<void>;
  error: string;
  onRetry: () => void;
  sessionExpired: boolean;
}) {
  const [mode, setMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [registrationSent, setRegistrationSent] = useState(false);
  const [formError, setFormError] = useState("");

  async function submitLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setFormError("");
    try {
      await onLogin(email, password);
    } finally {
      setBusy(false);
    }
  }

  async function submitRegistration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError("");
    setBusy(true);
    try {
      const form = new FormData(event.currentTarget);
      const shopName = String(form.get("shopName") ?? "").trim();
      const confirmPassword = String(form.get("confirmPassword") ?? "");
      if (password !== confirmPassword) {
        setFormError("The passwords do not match.");
        return;
      }
      await api<void>("/api/auth/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ shopName, email, password }),
      });
      setRegistrationSent(true);
    } catch (failure) {
      setFormError(failure instanceof ApiError && failure.status === 503
        ? "Email delivery is unavailable. Try again later."
        : "Registration failed. Check your details and try again.");
    } finally {
      setBusy(false);
    }
  }

  async function resendVerification() {
    setBusy(true);
    setFormError("");
    try {
      await api<void>("/api/auth/resend-verification", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
      setFormError("If an unverified cafe account exists, a new link will arrive shortly.");
    } catch {
      setFormError("Email delivery is unavailable. Try again later.");
    } finally {
      setBusy(false);
    }
  }

  const loginError = error || formError;
  return (
    <main className="auth-shell">
      <a className="skip-link" href="#auth-form">Skip to sign in</a>
      <section className="auth-card" aria-labelledby="auth-title">
        <a className="brand-lockup" href="/" aria-label="Print AIR home">
          <span>Print AIR</span>
        </a>
        {mode === "login" ? (
          <>
            <h1 id="auth-title">Sign in to your shop</h1>
            <p className="auth-intro">
              Access documents submitted to {sessionExpired ? "your shop after signing in again." : "your shop."}
            </p>
            <form id="auth-form" className="auth-form" onSubmit={submitLogin}>
              <label className="field" htmlFor="login-email">
                Email address
                <input id="login-email" name="email" type="email" autoComplete="username" required
                  value={email} onChange={(event) => setEmail(event.target.value)} />
              </label>
              <label className="field" htmlFor="login-password">
                Password
                <span className="password-field">
                  <input id="login-password" name="password" type={showPassword ? "text" : "password"}
                    autoComplete="current-password" required value={password}
                    onChange={(event) => setPassword(event.target.value)} />
                  <button type="button" className="password-toggle" aria-pressed={showPassword}
                    onClick={() => setShowPassword((shown) => !shown)}>
                    {showPassword ? "Hide" : "Show"}
                  </button>
                </span>
              </label>
              <p className="field-note">Password reset is not available yet.</p>
              {sessionExpired && <p className="form-message" role="status">Your session expired. Sign in again.</p>}
              {loginError && <p className="form-message error-message" role="alert">{loginError}</p>}
              {error.startsWith("Could not connect")
                && <button type="button" className="quiet-button" onClick={onRetry}>Retry connection</button>}
              <button className="primary-button wide" type="submit" disabled={busy}>
                {busy ? "Signing in…" : "Sign in"}
              </button>
            </form>
            <p className="auth-switch">
              New to Print AIR?{" "}
              <button type="button" className="text-button" onClick={() => {
                setMode("register");
                setFormError("");
              }}>Create a cafe account</button>
            </p>
          </>
        ) : registrationSent ? (
          <div className="registration-result" role="status">
            <h1 id="auth-title">Check your inbox</h1>
            <p>If this email can be verified, a link will arrive shortly. The link expires after 24 hours.</p>
            {formError && <p className="form-message">{formError}</p>}
            <button type="button" className="primary-button wide" disabled={busy} onClick={() => void resendVerification()}>
              {busy ? "Sending…" : "Resend verification link"}
            </button>
            <button type="button" className="text-button" onClick={() => {
              setMode("login");
              setRegistrationSent(false);
              setFormError("");
            }}>Back to sign in</button>
          </div>
        ) : (
          <>
            <h1 id="auth-title">Create your cafe account</h1>
            <p className="auth-intro">Register your shop to manage incoming print documents.</p>
            <form id="auth-form" className="auth-form" onSubmit={submitRegistration}>
              <label className="field" htmlFor="shop-name">
                Cafe or shop name
                <input id="shop-name" name="shopName" type="text" autoComplete="organization"
                  maxLength={120} required />
              </label>
              <label className="field" htmlFor="register-email">
                Email address
                <input id="register-email" name="email" type="email" autoComplete="email" required
                  value={email} onChange={(event) => setEmail(event.target.value)} />
              </label>
              <label className="field" htmlFor="register-password">
                Password
                <span className="password-field">
                  <input id="register-password" name="password" type={showPassword ? "text" : "password"}
                    autoComplete="new-password" minLength={12} maxLength={72} required value={password}
                    onChange={(event) => setPassword(event.target.value)} />
                  <button type="button" className="password-toggle" aria-pressed={showPassword}
                    onClick={() => setShowPassword((shown) => !shown)}>
                    {showPassword ? "Hide" : "Show"}
                  </button>
                </span>
              </label>
              <label className="field" htmlFor="confirm-password">
                Confirm password
                <input id="confirm-password" name="confirmPassword" type={showPassword ? "text" : "password"}
                  autoComplete="new-password" minLength={12} maxLength={72} required />
              </label>
              <p className="field-note">Use at least 12 characters. Email verification is required to activate the account.</p>
              {formError && <p className="form-message error-message" role="alert">{formError}</p>}
              <button className="primary-button wide" type="submit" disabled={busy}>
                {busy ? "Creating account…" : "Create cafe account"}
              </button>
            </form>
            <p className="auth-switch">
              Already registered?{" "}
              <button type="button" className="text-button" onClick={() => {
                setMode("login");
                setFormError("");
              }}>Sign in</button>
            </p>
          </>
        )}
      </section>
    </main>
  );
}

function VerificationPage({ state }: { state: VerificationState }) {
  return (
    <main className="auth-shell">
      <section className="auth-card verification-card" aria-labelledby="verification-title">
        <a className="brand-lockup" href="/" aria-label="Print AIR home">
          <span>Print AIR</span>
        </a>
        {state === "checking" ? (
          <p role="status">Verifying your email…</p>
        ) : state === "verified" ? (
          <>
            <h1 id="verification-title">Email verified</h1>
            <p>Your cafe account is ready. Sign in to open the Shop Desk.</p>
            <a className="primary-button wide button-link" href="/">Sign in</a>
          </>
        ) : (
          <>
            <h1 id="verification-title">Verification link unavailable</h1>
            <p>This link may be invalid, expired, or already used. Return to sign in to request another.</p>
            <a className="primary-button wide button-link" href="/">Back to sign in</a>
          </>
        )}
      </section>
    </main>
  );
}
