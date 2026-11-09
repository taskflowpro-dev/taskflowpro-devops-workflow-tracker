"use client";
import { useEffect, useState } from "react";
import {
  CheckCheck,
  ChevronRight,
  CircleCheck,
  Info,
  Activity,
} from "lucide-react";
import { Badge, CodeBlock, EmptyState } from "@/components/platform-ui";
import type { TrackedRepository } from "@/lib/repository";
import type { Recommendation } from "@/lib/intelligence";
import { type Request, message } from "./shared";

export default function RoadmapView({
  repository,
  request,
}: {
  repository: TrackedRepository;
  request: Request;
}) {
  const [items, setItems] = useState<Recommendation[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const base = `/api/intelligence/repositories/${repository.id}/roadmap`;
  useEffect(() => {
    let live = true;
    request<Recommendation[]>(base)
      .then((data) => {
        if (live) setItems(data);
      })
      .catch((e) => {
        if (live) setError(e.message);
      })
      .finally(() => {
        if (live) setBusy(false);
      });
    return () => {
      live = false;
    };
  }, [base, request]);
  async function sync() {
    setBusy(true);
    setError("");
    try {
      setItems(await request<Recommendation[]>(base, { method: "POST" }));
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  async function complete(
    item: Recommendation,
    stepId: number,
    completed: boolean,
  ) {
    const previous = items;
    setItems((current) =>
      current.map((recommendation) =>
        recommendation.id === item.id
          ? {
              ...recommendation,
              steps: recommendation.steps.map((step) =>
                step.id === stepId ? { ...step, completed } : step,
              ),
            }
          : recommendation,
      ),
    );
    setBusy(true);
    setError("");
    try {
      await request(
        `/api/intelligence/recommendations/${item.id}/steps/${stepId}`,
        { method: "PATCH", body: JSON.stringify({ completed }) },
      );
      setItems(await request<Recommendation[]>(base));
    } catch (e) {
      setItems(previous);
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  const done = items
    .flatMap((item) => item.steps)
    .filter((step) => step.completed).length;
  const total = items.reduce((sum, item) => sum + item.steps.length, 0);
  return (
    <section className="panel roadmap-panel">
      <div className="panel-heading">
        <div>
          <span className="section-kicker">
            <Activity size={14} /> Engineering roadmap
          </span>
          <h2>From insight to implementation</h2>
          <p>
            {done} of {total} steps completed · self-reported progress
          </p>
        </div>
        <button
          className="outline-button"
          disabled={busy}
          onClick={() => void sync()}
        >
          {busy
            ? "Loading…"
            : items.length
              ? "Sync evidence"
              : "Generate roadmap"}
        </button>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {total > 0 && (
        <progress
          className="roadmap-progress"
          max={total}
          value={done}
          aria-label="Implementation progress"
        />
      )}
      {!items.length && !busy && (
        <EmptyState title="A roadmap, grounded in your repository">
          Generate prioritized recommendations from the saved engineering
          signals. Your checklist progress is preserved when evidence is
          synchronized.
        </EmptyState>
      )}
      <div className="recommendation-list">
        {items.map((item, index) => (
          <details className="recommendation" key={item.id}>
            <summary>
              <span className="recommendation-number">
                {item.status === "COMPLETE" ? (
                  <CheckCheck size={17} />
                ) : (
                  String(index + 1).padStart(2, "0")
                )}
              </span>
              <span className="recommendation-title">
                <strong>{item.title}</strong>
                <small>
                  {item.complexity.toLowerCase()} estimated complexity ·{" "}
                  {item.steps.filter((s) => s.completed).length}/
                  {item.steps.length} steps
                </small>
              </span>
              <Badge tone={item.priority === "HIGH" ? "amber" : "indigo"}>
                {item.priority.toLowerCase()}
              </Badge>
              <ChevronRight className="details-chevron" size={16} />
            </summary>
            <div className="recommendation-body">
              <p>{item.explanation}</p>
              <div className="evidence-callout">
                <Info size={16} />
                <div>
                  <strong>
                    Repository evidence · {item.observedStatus.toLowerCase()}
                  </strong>
                  <p>{item.evidence}</p>
                  <small>
                    Analyzed {new Date(item.analyzedAt).toLocaleString()}
                  </small>
                </div>
              </div>
              {item.steps.map((step) => (
                <article
                  className={`implementation-step ${step.completed ? "step-complete" : ""}`}
                  key={step.id}
                >
                  <label className="step-title">
                    <input
                      type="checkbox"
                      checked={step.completed}
                      disabled={busy}
                      onChange={(event) =>
                        void complete(item, step.id, event.target.checked)
                      }
                    />
                    <strong>{step.title}</strong>
                  </label>
                  <p>{step.instructions}</p>
                  {step.filePath && (
                    <span className="file-path">{step.filePath}</span>
                  )}
                  {step.command && <CodeBlock content={step.command} />}
                  <p className="step-validation">
                    <CircleCheck size={15} />
                    <span>{step.validation}</span>
                  </p>
                  <details className="troubleshooting">
                    <summary>Troubleshooting</summary>
                    <p>{step.troubleshooting}</p>
                  </details>
                </article>
              ))}
            </div>
          </details>
        ))}
      </div>
    </section>
  );
}
