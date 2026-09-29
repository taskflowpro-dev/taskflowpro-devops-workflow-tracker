"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { api, saveToken, type AuthResponse } from "@/lib/auth";
import { Brand } from "@/components/brand";
import { useAuth } from "@/app/providers";

export default function LoginPage() {
  const router = useRouter();
  const { refresh } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setBusy(true);
    try { const result = await api<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify({ email, password }) }); saveToken(result.token); await refresh(); router.replace("/dashboard"); router.refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : "Unable to sign in."); }
    finally { setBusy(false); }
  }
  return <div className="auth-card">
    <div className="auth-brand"><Brand /></div>
    <div className="auth-heading"><div className="eyebrow">WELCOME BACK</div><h1>Sign in to your workspace</h1><p>Pick up where your team left off.</p></div>
    <form className="auth-form" onSubmit={submit}>
      {error && <div className="form-error" role="alert">{error}</div>}
      <label>Email address<input type="email" autoComplete="email" placeholder="you@company.com" value={email} onChange={e => setEmail(e.target.value)} required maxLength={254} /></label>
      <label>Password<input type="password" autoComplete="current-password" placeholder="Enter your password" value={password} onChange={e => setPassword(e.target.value)} required maxLength={72} /></label>
      <button className="primary-button" disabled={busy}>{busy ? <><span className="spinner"/> Signing in…</> : "Sign in"}</button>
    </form>
    <div className="auth-switch">New to TaskFlow Pro? <Link href="/signup">Create an account <span>→</span></Link></div>
    <div className="auth-foot"><span>© 2026 TaskFlow Pro</span><span>Built for teams that get things done</span></div>
  </div>;
}
