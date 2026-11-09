"use client";
import { useEffect, useState, type FormEvent } from "react";
import { GitBranch, Info, ShieldCheck, Terminal, Workflow } from "lucide-react";
import { Badge, CodeBlock, EmptyState } from "@/components/platform-ui";
import type { TrackedRepository } from "@/lib/repository";
import type { Configuration, Validation } from "@/lib/intelligence";
import { type Request, message } from "./shared";

export default function BuilderView({
  repository,
  request,
  initialKind = "workflow",
}: {
  repository: TrackedRepository;
  request: Request;
  initialKind?: string;
}) {
  const [configs, setConfigs] = useState<Configuration[]>([]);
  const [selected, setSelected] = useState<Configuration | null>(null);
  const [kind, setKind] = useState(initialKind);
  const [profileIndex, setProfileIndex] = useState(0);
  const [options, setOptions] = useState({
    tests: true,
    lint: true,
    security: true,
    container: false,
    publish: false,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [validation, setValidation] = useState<Validation | null>(null);
  const base = `/api/intelligence/repositories/${repository.id}/configurations`;
  useEffect(() => {
    let live = true;
    request<Configuration[]>(base)
      .then((data) => {
        if (live) {
          setConfigs(data);
          setSelected(data.find((item) => item.kind === initialKind) ?? null);
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [base, request, initialKind]);
  async function generate(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setValidation(null);
    try {
      const config = await request<Configuration>(base, {
        method: "POST",
        body: JSON.stringify({
          kind,
          ...options,
          ...(kind === "docker" ? { profileIndex } : {}),
        }),
      });
      setConfigs((current) => [config, ...current]);
      setSelected(config);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  async function validate() {
    if (!selected) return;
    setBusy(true);
    setError("");
    try {
      setValidation(
        await request<Validation>(`${base}/${selected.id}/validate`, {
          method: "POST",
        }),
      );
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  const result = validation ?? selected?.metadata.validation;
  return (
    <div className="builder-grid">
      <section className="panel builder-options">
        <span className="section-kicker">
          <Workflow size={14} /> Configure draft
        </span>
        <h2>Workflow options</h2>
        <div className="stack-profiles">
          {repository.analysis.buildProfiles?.length ? (
            repository.analysis.buildProfiles.map((profile, i) => (
              <div key={`${profile.manifest}-${i}`}>
                <GitBranch size={15} />
                <span>
                  <strong>{profile.framework}</strong>
                  <code>{profile.manifest}</code>
                </span>
                <Badge>
                  {profile.runtime}
                  {profile.runtimeEstimated ? "*" : ""}
                </Badge>
              </div>
            ))
          ) : (
            <p>
              No inspected modules available. Refresh the repository report to
              detect supported build profiles.
            </p>
          )}
        </div>
        <form className="workspace-form" onSubmit={generate}>
          <label>
            Configuration type
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="workflow">GitHub Actions workflow</option>
              <option value="docker">Dockerfile</option>
              <option value="security">Dependabot updates</option>
              <option value="deployment">Production Compose draft</option>
              <option value="release">Gated VPS release workflow</option>
            </select>
          </label>
          {kind === "docker" && (
            <label>
              Runtime module
              <select
                value={profileIndex}
                onChange={(event) =>
                  setProfileIndex(Number(event.target.value))
                }
              >
                {repository.analysis.buildProfiles?.map((profile, index) => (
                  <option key={profile.manifest} value={index}>
                    {profile.framework} · {profile.directory}
                  </option>
                ))}
              </select>
            </label>
          )}
          {kind === "workflow" && (
            <fieldset className="workflow-options">
              <legend>Pipeline stages</legend>
              {Object.entries(options).map(([key, enabled]) => (
                <label key={key}>
                  <input
                    type="checkbox"
                    checked={enabled}
                    onChange={(e) =>
                      setOptions((current) => ({
                        ...current,
                        [key]: e.target.checked,
                      }))
                    }
                  />
                  <span>
                    {
                      (
                        {
                          tests: "Automated tests",
                          lint: "Code quality",
                          security: "Dependency audit",
                          container: "Container build",
                          publish: "Publish to GHCR (gated)",
                        } as Record<string, string>
                      )[key]
                    }
                  </span>
                </label>
              ))}
            </fieldset>
          )}
          <button className="primary-button" disabled={busy}>
            {busy ? "Working…" : "Generate configuration"}
            <Terminal size={15} />
          </button>
        </form>
        <p className="form-hint">
          No files are written to GitHub. Existing workflows are preserved.
          Runtime versions marked * are proposed defaults.
        </p>
        {(repository.analysis.workflows?.length ?? 0) > 0 && (
          <div className="evidence-callout">
            <Info size={16} />
            <div>
              <strong>Existing workflows detected</strong>
              <p>
                {repository.analysis.workflows.join(", ")}. Compare and merge
                changes manually.
              </p>
            </div>
          </div>
        )}
      </section>
      <section className="panel configuration-preview">
        <div className="panel-heading">
          <div>
            <h2>Configuration preview</h2>
            <p>Review the draft before applying it to your repository.</p>
          </div>
          <Badge tone="indigo">Draft only</Badge>
        </div>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        {configs.length > 0 && (
          <label className="draft-select">
            Saved drafts
            <select
              aria-label="Select saved configuration"
              value={selected?.id ?? ""}
              onChange={(event) => {
                setSelected(
                  configs.find(
                    (item) => item.id === Number(event.target.value),
                  ) ?? null,
                );
                setValidation(null);
              }}
            >
              <option value="" disabled>
                Select a draft
              </option>
              {configs.map((config) => (
                <option key={config.id} value={config.id}>
                  {config.filePath} · #{config.id}
                </option>
              ))}
            </select>
          </label>
        )}
        {selected ? (
          <>
            <CodeBlock
              content={selected.content}
              path={selected.filePath}
              downloadable
            />
            <div className="configuration-notes">
              <h3>Before you apply</h3>
              <ul>
                {selected.metadata.notes.map((note) => (
                  <li key={note}>{note}</li>
                ))}
              </ul>
              <h3>Required secret names</h3>
              {selected.metadata.requiredSecrets.length ? (
                <ul>
                  {selected.metadata.requiredSecrets.map((name) => (
                    <li key={name}>{name}</li>
                  ))}
                </ul>
              ) : (
                <p>No custom secret names are required by this draft.</p>
              )}
            </div>
            <button
              className="outline-button"
              disabled={busy}
              onClick={() => void validate()}
            >
              <ShieldCheck size={15} /> Validate configuration
            </button>
            {result && (
              <div
                className={`validation-result ${result.valid ? "valid" : "invalid"}`}
                role="status"
              >
                <strong>
                  {result.valid ? "Static checks passed" : "Changes required"}
                </strong>
                <ul>
                  {[...result.errors, ...result.warnings].map((item, i) => (
                    <li key={i}>{item}</li>
                  ))}
                </ul>
              </div>
            )}
          </>
        ) : (
          <EmptyState title="Your next pipeline starts here">
            Choose the stages your project needs. We’ll build a reviewable draft
            using the inspected module paths and supported tooling.
          </EmptyState>
        )}
      </section>
    </div>
  );
}
