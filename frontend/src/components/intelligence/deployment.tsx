"use client";
import { useEffect, useState, type FormEvent } from "react";
import { ArrowUpRight, ChevronRight, Info, Rocket } from "lucide-react";
import { Badge, CodeBlock } from "@/components/platform-ui";
import type { TrackedRepository } from "@/lib/repository";
import type { Environment } from "@/lib/intelligence";
import { type Request, message } from "./shared";
import BuilderView from "./builder";

export default function DeploymentView({
  repository,
  request,
}: {
  repository: TrackedRepository;
  request: Request;
}) {
  const [plans, setPlans] = useState<Environment[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const base = `/api/intelligence/repositories/${repository.id}/environments`;
  useEffect(() => {
    let live = true;
    request<Environment[]>(base)
      .then((data) => {
        if (live) setPlans(data);
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [base, request]);
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    try {
      const plan = await request<Environment>(base, {
        method: "POST",
        body: JSON.stringify({
          name: form.get("name"),
          target: form.get("target"),
        }),
      });
      setPlans((current) => [plan, ...current]);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  async function review(plan: Environment) {
    setBusy(true);
    setError("");
    try {
      await request(`${base}/${plan.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          status: plan.status === "REVIEWED" ? "PLANNED" : "REVIEWED",
        }),
      });
      setPlans(await request<Environment[]>(base));
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="view-stack">
      <section className="panel deployment-advisor">
        <div>
          <span className="section-kicker">
            <Rocket size={14} /> Deployment plan
          </span>
          <h2>Plan a deployment</h2>
          <p>
            Choose a target and generate a repository-specific implementation
            plan. Plans and review status are saved; no deployment is executed.
          </p>
        </div>
        <form className="deployment-plan-form workspace-form" onSubmit={create}>
          <label>
            Environment name
            <input
              name="name"
              required
              maxLength={80}
              placeholder="e.g. Production"
            />
          </label>
          <label>
            Deployment target
            <select name="target">
              <option value="ubuntu-vps">Ubuntu VPS + Docker</option>
              <option value="managed-cloud">Managed cloud platform</option>
            </select>
          </label>
          <button className="primary-compact" disabled={busy}>
            Create delivery plan
          </button>
        </form>
      </section>
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      {plans.map((plan) => (
        <details
          className="panel saved-plan"
          key={plan.id}
          open={plans.length === 1 || undefined}
        >
          <summary>
            <span className="stat-icon blue">
              <Rocket size={18} />
            </span>
            <span>
              <strong>{plan.name}</strong>
              <small>{plan.plan.title}</small>
            </span>
            <Badge tone={plan.status === "REVIEWED" ? "green" : "amber"}>
              {plan.status.toLowerCase()}
            </Badge>
            <ChevronRight size={16} className="details-chevron" />
          </summary>
          <div className="plan-body">
            <p>{plan.plan.rationale}</p>
            <div className="plan-module-list">
              {plan.plan.modules.map((module, i) => (
                <Badge key={i}>
                  {module.framework} · {module.directory}
                </Badge>
              ))}
            </div>
            {plan.plan.steps.map((step, i) => (
              <article className="plan-step" key={step.title}>
                <span>{i + 1}</span>
                <div>
                  <h3>{step.title}</h3>
                  <p>{step.instructions}</p>
                  <CodeBlock content={step.command} />
                </div>
              </article>
            ))}
            <div className="evidence-callout">
              <Info size={17} />
              <div>
                {plan.plan.limitations.map((item) => (
                  <p key={item}>{item}</p>
                ))}
              </div>
            </div>
            <div className="reference-links">
              {plan.plan.links.map((url) => (
                <a href={url} target="_blank" rel="noreferrer" key={url}>
                  Read official guide <ArrowUpRight size={14} />
                </a>
              ))}
            </div>
            <button
              className="outline-button"
              disabled={busy}
              onClick={() => void review(plan)}
            >
              {plan.status === "REVIEWED"
                ? "Reopen review"
                : "Mark plan reviewed"}
            </button>
            <small className="muted">
              Review status is self-reported. Deployment remains unverified.
            </small>
          </div>
        </details>
      ))}
      <BuilderView
        repository={repository}
        request={request}
        initialKind="deployment"
      />
    </div>
  );
}
