"use client";
import {
  ArrowUpRight,
  GitBranch,
  GitPullRequest,
  ShieldCheck,
  Workflow,
} from "lucide-react";
import { Badge, EmptyState } from "@/components/platform-ui";
import type { TrackedRepository } from "@/lib/repository";
import { type Props } from "./shared";
import BuilderView from "./builder";

export default function EngineeringView({
  view,
  repository,
  request,
}: Props & { repository: TrackedRepository }) {
  const a = repository.analysis;
  if (view === "pipelines")
    return (
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>GitHub Actions execution history</h2>
            <p>
              Latest returned runs · saved{" "}
              {new Date(repository.analyzedAt).toLocaleString()}
            </p>
          </div>
          <a
            href={`${repository.url}/actions`}
            target="_blank"
            rel="noreferrer"
            className="outline-button"
          >
            Open Actions <ArrowUpRight size={14} />
          </a>
        </div>
        {a.activity?.workflowRuns?.length ? (
          <div className="engineering-list">
            {a.activity.workflowRuns.map((run, i) => (
              <a href={run.url} key={i} target="_blank" rel="noreferrer">
                <span
                  className={`run-state ${run.conclusion === "success" ? "success" : ""}`}
                >
                  <Workflow size={20} />
                </span>
                <span>
                  <strong>{run.name}</strong>
                  <small>
                    {run.branch} · {new Date(run.updatedAt).toLocaleString()}
                  </small>
                </span>
                <Badge
                  tone={
                    run.conclusion === "success"
                      ? "green"
                      : run.conclusion === "failure"
                        ? "rose"
                        : "amber"
                  }
                >
                  {run.conclusion || run.status}
                </Badge>
                <ArrowUpRight size={15} />
              </a>
            ))}
          </div>
        ) : (
          <EmptyState title="No workflow runs were returned">
            A missing execution history is not proof that CI is disabled.
            Refresh the report or check Actions permissions.
          </EmptyState>
        )}
      </section>
    );
  if (view === "pull-requests")
    return (
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Pull request review queue</h2>
            <p>Up to 15 open pull requests returned by GitHub.</p>
          </div>
          <Badge>{a.pullRequests?.length ?? 0} returned</Badge>
        </div>
        {a.pullRequests?.length ? (
          <div className="engineering-list">
            {a.pullRequests.map((pr) => (
              <a href={pr.url} key={pr.number} target="_blank" rel="noreferrer">
                <GitPullRequest className="text-indigo" size={22} />
                <span>
                  <strong>{pr.title}</strong>
                  <small>
                    #{pr.number} · {pr.user} ·{" "}
                    {new Date(pr.updatedAt).toLocaleDateString()}
                  </small>
                </span>
                <Badge tone={pr.draft ? "neutral" : "green"}>
                  {pr.draft ? "Draft" : "Review requested"}
                </Badge>
                <ArrowUpRight size={15} />
              </a>
            ))}
          </div>
        ) : (
          <EmptyState title="No open pull requests returned">
            The review queue is empty in this saved report. Refresh the
            repository for current information.
          </EmptyState>
        )}
      </section>
    );
  if (view === "dependencies")
    return (
      <div className="view-stack">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Dependency inventory</h2>
              <p>
                Samples from inspected manifests. This is not a complete SBOM or
                vulnerability scan.
              </p>
            </div>
            <Badge tone="amber">Vulnerabilities unknown</Badge>
          </div>
          {a.sourceScan?.dependencySamples?.length ? (
            <div className="dependency-grid">
              {a.sourceScan.dependencySamples.map((dependency, i) => (
                <div key={i}>
                  <span className="dependency-icon">◈</span>
                  <code>{dependency}</code>
                  <Badge>Inspected</Badge>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="No dependency samples available">
              Refresh repository analysis. Unsupported manifests and bounded
              scans may limit the dependency sample.
            </EmptyState>
          )}
        </section>
        <BuilderView
          repository={repository}
          request={request}
          initialKind="security"
        />
      </div>
    );
  const checks = (a.checks ?? []).filter(
    (check) => view !== "security" || check.id === "security",
  );
  return (
    <div className="view-stack">
      <div className="analytics-metrics">
        <article className="stat-card">
          <span>Test coverage</span>
          <strong>Unknown</strong>
          <small>No coverage report has been executed or parsed.</small>
        </article>
        <article className="stat-card">
          <span>
            {view === "security" ? "Vulnerability findings" : "Test-like files"}
          </span>
          <strong>
            {view === "security"
              ? "Unknown"
              : a.sourceScan?.available
                ? a.sourceScan.testFiles
                : "Unknown"}
          </strong>
          <small>
            {view === "security"
              ? "No vulnerability scanner has run here."
              : "Name-based detection, not test execution."}
          </small>
        </article>
        <article className="stat-card">
          <span>Technical debt markers</span>
          <strong>
            {a.sourceScan?.available
              ? a.sourceScan.todoMarkers +
                a.sourceScan.fixmeMarkers +
                a.sourceScan.hackMarkers
              : "Unknown"}
          </strong>
          <small>TODO, FIXME and HACK text matches.</small>
        </article>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>
              {view === "security"
                ? "Security configuration evidence"
                : "Engineering practice signals"}
            </h2>
            <p>Detected facts and recommendations are presented separately.</p>
          </div>
          <Badge tone="indigo">Evidence-based</Badge>
        </div>
        <div className="engineering-signals">
          {checks.map((check) => (
            <article key={check.id}>
              <span
                className={`signal-symbol ${check.status === "PRESENT" ? "signal-present" : ""}`}
              >
                <ShieldCheck size={20} />
              </span>
              <div>
                <h3>{check.title}</h3>
                <p>{check.evidence || check.detail}</p>
                {check.status !== "PRESENT" && (
                  <p className="signal-recommendation">
                    Recommended: {check.recommendation}
                  </p>
                )}
              </div>
              <Badge tone={check.status === "PRESENT" ? "green" : "amber"}>
                {check.status === "PRESENT" ? "Detected" : "Not detected"}
              </Badge>
            </article>
          ))}
        </div>
        <p className="form-hint">
          File presence does not establish correctness, security or deployment
          readiness. Branch protection and cloud configuration are not
          inspected.
        </p>
      </section>
      {view === "security" ? (
        <BuilderView
          repository={repository}
          request={request}
          initialKind="security"
        />
      ) : (
        <section className="panel">
          <h2>Inspected configuration files</h2>
          {a.configurationEvidence?.length ? (
            <div className="engineering-list">
              {a.configurationEvidence.map((item) => (
                <div key={item.path}>
                  <GitBranch size={18} />
                  <span>
                    <strong>{item.path}</strong>
                    <small>{item.summary}</small>
                  </span>
                  <Badge>{item.kind}</Badge>
                </div>
              ))}
            </div>
          ) : (
            <p className="muted">
              Refresh analysis to include inspected configuration evidence.
            </p>
          )}
        </section>
      )}
    </div>
  );
}
