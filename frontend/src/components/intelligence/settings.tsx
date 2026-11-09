"use client";
import { useEffect, useState, type FormEvent } from "react";
import { ArrowUpRight, GitBranch, Rocket, Terminal } from "lucide-react";
import { Badge } from "@/components/platform-ui";
import { useAuth } from "@/app/providers";
import type { IntegrationStatus, Preferences } from "@/lib/intelligence";
import { type Request, message } from "./shared";

export default function SettingsView({
  view,
  request,
}: {
  view: string;
  request: Request;
}) {
  const { user, refresh } = useAuth();
  const [preferences, setPreferences] = useState<Preferences | null>(null);
  const [integrations, setIntegrations] = useState<IntegrationStatus | null>(
    null,
  );
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let live = true;
    Promise.all([
      request<Preferences>("/api/intelligence/preferences"),
      request<IntegrationStatus>("/api/intelligence/integrations"),
    ])
      .then(([p, i]) => {
        if (live) {
          setPreferences(p);
          setIntegrations(i);
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [request]);
  async function savePrefs(next: Preferences) {
    const previous = preferences;
    setPreferences(next);
    setBusy(true);
    setError("");
    try {
      const saved = await request<Preferences>(
        "/api/intelligence/preferences",
        { method: "PUT", body: JSON.stringify(next) },
      );
      setPreferences(saved);
      document.documentElement.dataset.compact = String(saved.compactMode);
      document.documentElement.dataset.reduceMotion = String(
        saved.reduceMotion,
      );
      setFeedback("Preferences saved to your account.");
    } catch (e) {
      setPreferences(previous);
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  async function saveName(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = new FormData(event.currentTarget).get("name");
    setBusy(true);
    setError("");
    try {
      await request("/api/intelligence/account", {
        method: "PATCH",
        body: JSON.stringify({ name }),
      });
      await refresh();
      setFeedback("Profile updated.");
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel settings-panel">
      <div className="panel-heading">
        <div>
          <h2>
            {view === "account"
              ? "Your account"
              : view === "preferences"
                ? "Make room for your workflow"
                : "Connected capabilities"}
          </h2>
          <p>
            {view === "account"
              ? "Manage the profile used across your workspaces."
              : view === "preferences"
                ? "Preferences follow your account across devices."
                : "Integration credentials remain on the server."}
          </p>
        </div>
      </div>
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      {feedback && (
        <p role="status" className="team-feedback">
          {feedback}
        </p>
      )}
      {view === "account" ? (
        <form className="workspace-form profile-form" onSubmit={saveName}>
          <div className="profile-identity">
            <div className="avatar">{user?.name[0]}</div>
            <div>
              <strong>{user?.name}</strong>
              <p>{user?.email}</p>
            </div>
          </div>
          <label>
            Display name
            <input
              name="name"
              defaultValue={user?.name}
              required
              maxLength={100}
            />
          </label>
          <label>
            Email address
            <input value={user?.email ?? ""} disabled readOnly />
          </label>
          <p className="form-hint">
            Your email is the sign-in identity. Email changes are not available
            here.
          </p>
          <button className="primary-compact" disabled={busy}>
            Save profile
          </button>
        </form>
      ) : view === "preferences" ? (
        preferences ? (
          <div className="preference-list">
            {(
              [
                [
                  "compactMode",
                  "Compact spacing",
                  "Fit more engineering context into each view.",
                ],
                [
                  "reduceMotion",
                  "Reduce motion",
                  "Keep transitions and animations to a minimum.",
                ],
              ] as const
            ).map(([key, title, description]) => (
              <label key={key}>
                <span>
                  <strong>{title}</strong>
                  <small>{description}</small>
                </span>
                <input
                  type="checkbox"
                  role="switch"
                  checked={preferences[key]}
                  disabled={busy}
                  onChange={(e) =>
                    void savePrefs({ ...preferences, [key]: e.target.checked })
                  }
                />
              </label>
            ))}
          </div>
        ) : (
          <p role="status">Loading preferences…</p>
        )
      ) : integrations ? (
        <div className="integration-list">
          <article>
            <GitBranch size={24} />
            <div>
              <h3>GitHub repository intelligence</h3>
              <p>
                Public repositories use GitHub’s API. A server-configured read
                credential can increase limits. Write operations are disabled.
              </p>
              <Badge tone="green">Read only</Badge>
            </div>
          </article>
          <article>
            <Terminal size={24} />
            <div>
              <h3>AI DevOps Architect</h3>
              <p>
                {integrations.ai.configured
                  ? `Configured model: ${integrations.ai.model}. Provider availability is checked when a message is sent.`
                  : "Evidence guidance is available. To enable AI, configure OLLAMA_MODEL and OLLAMA_BASE_URL on the backend and restart it. The model must already be installed in Ollama."}
              </p>
              <Badge tone={integrations.ai.configured ? "green" : "amber"}>
                {integrations.ai.configured
                  ? "Model configured"
                  : "No AI model configured"}
              </Badge>
              <a
                href="https://docs.ollama.com/api/chat"
                target="_blank"
                rel="noreferrer"
              >
                Ollama documentation <ArrowUpRight size={13} />
              </a>
            </div>
          </article>
          <article>
            <Rocket size={24} />
            <div>
              <h3>Deployment advisory</h3>
              <p>
                Generate and review plans and configuration drafts. Apply them
                in your own approved environment.
              </p>
              <Badge>Execution disabled</Badge>
            </div>
          </article>
        </div>
      ) : (
        <p role="status">Loading integration status…</p>
      )}
    </section>
  );
}
