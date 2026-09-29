"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { api, saveToken, type AuthResponse } from "@/lib/auth";
import { Brand } from "@/components/brand";
import { useAuth } from "@/app/providers";

export default function SignupPage() {
  const router = useRouter();
  const { refresh } = useAuth();
  const [name, setName] = useState(""); const [email, setEmail] = useState("");
  const [password, setPassword] = useState(""); const [confirm, setConfirm] = useState("");
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError("");
    if (password.length < 8) { setError("Use at least 8 characters for your password."); return; }
    if (password !== confirm) { setError("Your passwords do not match."); return; }
    setBusy(true);
    try { await api("/api/auth/register", { method: "POST", body: JSON.stringify({ name, email, password }) }); const result = await api<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify({ email, password }) }); saveToken(result.token); await refresh(); router.replace("/dashboard"); router.refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : "Unable to create your account."); }
    finally { setBusy(false); }
  }
  return <div className="auth-card signup-card">
    <div className="auth-brand"><Brand /></div>
    <div className="auth-heading"><div className="eyebrow">GET STARTED</div><h1>Create your account</h1><p>A calmer, clearer way to move work forward.</p></div>
    <form className="auth-form" onSubmit={submit}>
      {error && <div className="form-error" role="alert">{error}</div>}
      <label>Your name<input autoComplete="name" placeholder="Alex Morgan" value={name} onChange={e => setName(e.target.value)} required maxLength={100} /></label>
      <label>Work email<input type="email" autoComplete="email" placeholder="you@company.com" value={email} onChange={e => setEmail(e.target.value)} required maxLength={254} /></label>
      <div className="form-row"><label>Password<input type="password" autoComplete="new-password" placeholder="At least 8 characters" value={password} onChange={e => setPassword(e.target.value)} required minLength={8} maxLength={72} /></label><label>Confirm password<input type="password" autoComplete="new-password" placeholder="Repeat password" value={confirm} onChange={e => setConfirm(e.target.value)} required maxLength={72} /></label></div>
      <button className="primary-button" disabled={busy}>{busy ? <><span className="spinner"/> Creating account…</> : "Create account"}</button>
    </form>
    <div className="auth-switch">Already have an account? <Link href="/login">Sign in <span>→</span></Link></div>
    <div className="auth-foot"><span>© 2026 TaskFlow Pro</span><span>Built for teams that get things done</span></div>
  </div>;
}
