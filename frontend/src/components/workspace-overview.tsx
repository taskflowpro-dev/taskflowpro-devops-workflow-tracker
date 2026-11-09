"use client";
import {
  ArrowUpRight,
  Check,
  Circle,
  GitBranch,
  GitCommitHorizontal,
} from "lucide-react";
import { Badge, EmptyState } from "@/components/platform-ui";
import type { TrackedRepository } from "@/lib/repository";

export default function WorkspaceOverview({
  repositories,
  onOpen,
}: {
  repositories: TrackedRepository[];
  onOpen: (id: number) => void;
}) {
  const checks = ["ci", "tests", "lint", "container", "deploy", "security"].map(
    (id) => {
      const known = repositories.filter((repo) =>
        repo.analysis.checks?.some((check) => check.id === id),
      );
      return {
        id,
        title: (
          {
            ci: "Continuous integration",
            tests: "Automated tests",
            lint: "Code quality",
            container: "Containerization",
            deploy: "Deployment configuration",
            security: "Security configuration",
          } as Record<string, string>
        )[id],
        known: known.length,
        present: known.filter((repo) =>
          repo.analysis.checks.some(
            (check) => check.id === id && check.status === "PRESENT",
          ),
        ).length,
      };
    },
  );
  const commits = repositories
    .flatMap((repo) =>
      (repo.analysis.activity?.recentCommits ?? []).map((commit) => ({
        ...commit,
        repository: repo.fullName,
      })),
    )
    .sort((a, b) => b.date.localeCompare(a.date))
    .slice(0, 5);
  return (
    <div className="view-stack">
      <section className="overview-banner">
        <span className="banner-symbol">
          <GitBranch size={24} />
        </span>
        <div>
          <h2>Repository overview</h2>
          <p>Review what is configured today and what to add next.</p>
        </div>
        <a href="#architect" className="outline-button">
          View roadmap <ArrowUpRight size={15} />
        </a>
      </section>
      <div className="overview-grid">
        <section className="panel repository-overview">
          <div className="panel-heading">
            <div>
              <h2>Your repositories</h2>
              <p>Engineering context, connected to your workspace.</p>
            </div>
            <a href="#repository-analysis" className="text-button">
              View all <ArrowUpRight size={14} />
            </a>
          </div>
          {repositories.length ? (
            <div className="overview-repositories">
              {repositories.slice(0, 5).map((repo) => (
                <button key={repo.id} onClick={() => onOpen(repo.id)}>
                  <span className="repository-symbol">
                    <GitBranch size={20} />
                  </span>
                  <span>
                    <strong>{repo.fullName}</strong>
                    <small>
                      {repo.language} ·{" "}
                      {repo.projectName ?? "No linked project"}
                    </small>
                  </span>
                  <span className="repository-score">
                    {repo.analysis.score
                      ? `${repo.analysis.score.value}%`
                      : "Unknown"}
                    <small>signals</small>
                  </span>
                  <ArrowUpRight size={15} />
                </button>
              ))}
            </div>
          ) : (
            <EmptyState
              title="Connect your first repository"
              action={
                <a href="#repository-analysis" className="primary-compact">
                  Connect GitHub repository <ArrowUpRight size={14} />
                </a>
              }
            >
              Get an evidence-based view of your code, delivery workflow and
              next steps.
            </EmptyState>
          )}
        </section>
        <section className="panel signal-chart">
          <div className="panel-heading">
            <div>
              <h2>Engineering practices</h2>
              <p>Repositories with each signal detected.</p>
            </div>
            <Badge>Configuration evidence</Badge>
          </div>
          <div className="signal-bars">
            {checks.map((check) => (
              <div key={check.id}>
                <div>
                  <span>{check.title}</span>
                  <b>
                    {check.present}
                    <small> / {check.known}</small>
                  </b>
                </div>
                <progress
                  value={check.present}
                  max={Math.max(1, check.known)}
                  aria-label={`${check.title}: ${check.present} of ${check.known} reports`}
                />
              </div>
            ))}
          </div>
          <p className="chart-note">
            Presence is not proof of execution. Unknown signals are excluded.
          </p>
        </section>
      </div>
      <div className="overview-grid overview-bottom">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Recent repository activity</h2>
              <p>Latest commits from your saved reports.</p>
            </div>
            <GitCommitHorizontal size={20} className="muted" />
          </div>
          {commits.length ? (
            <div className="commit-feed">
              {commits.map((commit, i) => (
                <a
                  href={commit.url}
                  key={`${commit.sha}-${i}`}
                  target="_blank"
                  rel="noreferrer"
                >
                  <span className="commit-symbol">
                    <GitCommitHorizontal size={17} />
                  </span>
                  <span>
                    <strong>{commit.message}</strong>
                    <small>
                      {commit.repository} · {commit.author} ·{" "}
                      {new Date(commit.date).toLocaleDateString()}
                    </small>
                  </span>
                  <code>{commit.sha}</code>
                </a>
              ))}
            </div>
          ) : (
            <div className="quiet-empty">
              Recent commits will appear after repository analysis.
            </div>
          )}
        </section>
        <section className="panel next-steps-panel">
          <div className="panel-heading">
            <div>
              <span className="section-kicker">Delivery workflow</span>
              <h2>Recommended next steps</h2>
            </div>
          </div>
          {[
            {
              title: "Understand your codebase",
              text: "Connect a repository and review the evidence.",
              href: "#repository-analysis",
              done: repositories.length > 0,
            },
            {
              title: "Prioritize the right improvements",
              text: "Create a roadmap for your actual stack.",
              href: "#architect",
              done: false,
            },
            {
              title: "Build with confidence",
              text: "Generate, review and validate your next pipeline.",
              href: "#workflows",
              done: false,
            },
          ].map((step, i) => (
            <a className="next-step-link" href={step.href} key={step.title}>
              <span className={step.done ? "onboarding-done" : ""}>
                {step.done ? <Check size={14} /> : <Circle size={14} />}
              </span>
              <div>
                <strong>
                  {String(i + 1).padStart(2, "0")} · {step.title}
                </strong>
                <small>{step.text}</small>
              </div>
              <ArrowUpRight size={14} />
            </a>
          ))}
        </section>
      </div>
    </div>
  );
}
