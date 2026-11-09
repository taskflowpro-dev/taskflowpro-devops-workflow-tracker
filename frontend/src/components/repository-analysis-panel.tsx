"use client";

import { FormEvent, useState, type CSSProperties } from "react";
import type { ProjectSummary, RepositoryAnalysis, RepositoryIssue, RepositorySuggestion, TrackedRepository } from "@/lib/repository";

type Props = {
  repositories: TrackedRepository[];
  projects: ProjectSummary[];
  selected: TrackedRepository | null;
  analyzing: boolean;
  working: boolean;
  onSelect: (repository: TrackedRepository) => void;
  onAnalyze: (url: string) => Promise<void>;
  onRefresh: (repository: TrackedRepository) => void;
  onRemove: (repository: TrackedRepository) => void;
  onLinkProject: (repositoryId: number, projectId: number) => void;
  onAddSuggestion: (repository: TrackedRepository, suggestion: RepositorySuggestion) => void;
  onAddIssue: (repository: TrackedRepository, issue: RepositoryIssue) => void;
};

export default function RepositoryAnalysisPanel(props: Props) {
  const [url, setUrl] = useState("");
  const selected = props.selected;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!url.trim()) return;
    await props.onAnalyze(url.trim());
    setUrl("");
  }

  return <section className="panel repository-panel repo-portfolio" id="repository-analysis" aria-labelledby="repository-analysis-title">
    <div className="repo-portfolio-header">
      <div><div className="eyebrow">GITHUB PROJECT INTELLIGENCE</div><h2 id="repository-analysis-title">Repository portfolio</h2><p>Connect a GitHub repository for a structured review of its codebase, delivery signals, activity, and open work.</p></div>
      <span className="repo-github-mark" aria-hidden="true">GH</span>
    </div>
    <form className="repo-analyze-form" onSubmit={submit}>
      <label className="repo-url-field"><span>GitHub repository URL</span><input id="repository-url" name="repositoryUrl" type="url" required value={url} onChange={(event) => setUrl(event.target.value)} placeholder="https://github.com/owner/repository" /></label>
      <button className="primary-compact" disabled={props.analyzing}>{props.analyzing ? <><span className="spinner"/> Inspecting repository…</> : "Analyze repository"}</button>
    </form>
    {props.repositories.length > 0 && <div className="repo-library" aria-label="Connected repositories">
      <div className="repo-library-heading"><strong>Connected repositories</strong><span>{props.repositories.length} {props.repositories.length === 1 ? "repository" : "repositories"}</span></div>
      <div className="repo-library-list">{props.repositories.map((repository) => <button type="button" key={repository.id} className={`repo-library-item${selected?.id === repository.id ? " selected" : ""}`} onClick={() => props.onSelect(repository)}>
        <span className="repo-library-mark">⌘</span><span className="repo-library-copy"><strong>{repository.fullName}</strong><small>{repository.projectName ?? "No project linked"} · analyzed {date(repository.analyzedAt)}</small></span><span className="repo-library-arrow">›</span>
      </button>)}</div>
    </div>}
    {!selected ? <div className="repo-empty repo-portfolio-empty"><span className="repo-empty-icon">⌘</span><strong>No repository analysis yet</strong><p>Paste a GitHub repository link above. Public repositories work without setup; private access needs a configured token.</p></div>
      : <RepositoryReport key={selected.id} {...props} repository={selected} analysis={selected.analysis} />}
  </section>;
}

function RepositoryReport({ repository, analysis, projects, analyzing, working, onRefresh, onRemove, onLinkProject, onAddSuggestion, onAddIssue }: Props & { repository: TrackedRepository; analysis: RepositoryAnalysis }) {
  const score = analysis.score?.value ?? 0;
  const languages = analysis.languages ?? [];
  const structure = analysis.structure;
  const activity = analysis.activity;
  const oldReport = (analysis.schemaVersion ?? 1) < 2;
  const runs = activity?.workflowRuns ?? (analysis.latestRun?.url ? [analysis.latestRun as NonNullable<RepositoryAnalysis["latestRun"]>] : []);

  return <div className="repo-report">
    <div className="repo-report-toolbar">
      <label className="repo-project-link"><span>Project</span><select value={repository.projectId ?? ""} disabled={working || !projects.length} onChange={(event) => event.target.value && onLinkProject(repository.id, Number(event.target.value))} aria-label="Project for this repository">
        <option value="" disabled>Choose project</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
      </select></label>
      <div className="repo-result-actions"><button className="outline-button" disabled={analyzing} onClick={() => onRefresh(repository)}>{analyzing ? "Refreshing…" : "Refresh analysis"}</button><button className="repo-remove" disabled={working} onClick={() => onRemove(repository)}>Remove repository</button></div>
    </div>
    {oldReport && <div className="repo-upgrade-note">This saved report uses the earlier analysis format. Refresh it to generate the expanded repository review.</div>}
    <header className="repo-report-hero">
      <div className="repo-report-title"><div className="repo-kicker">{analysis.archived ? "ARCHIVED REPOSITORY" : analysis.fork ? "FORKED REPOSITORY" : "REPOSITORY REPORT"}</div><h3>{analysis.fullName}</h3>
        <p>{analysis.description || "No repository description provided."}</p><div className="repo-report-links"><a href={repository.url} target="_blank" rel="noreferrer">Open on GitHub ↗</a><span>·</span><span>Default branch <strong>{analysis.defaultBranch}</strong></span><span>·</span><span>{analysis.license || "License not detected"}</span></div>
      </div>
      <div className="repo-signal-score" style={{ "--score": `${score}%` } as CSSProperties}><div><strong>{score}<small>%</small></strong><span>signal<br/>coverage</span></div></div>
    </header>
    <p className="repo-summary">{analysis.summary}</p>
    {analysis.score?.explanation && <p className="repo-score-note">{analysis.score.explanation}</p>}

    <div className="repo-metric-grid">
      <Metric label="Stars" value={formatNumber(analysis.stars)} hint="GitHub interest" />
      <Metric label="Forks" value={formatNumber(analysis.forks)} hint="Community copies" />
      <Metric label="Issues shown" value={formatNumber(analysis.issues?.length ?? 0)} hint="Up to 15 listed below" />
      <Metric label="Pull requests shown" value={formatNumber(analysis.pullRequests?.length ?? 0)} hint="Up to 15 listed below" />
      <Metric label="Files scanned" value={formatNumber(structure?.fileCount ?? analysis.coverage?.files ?? 0)} hint={structure?.treeTruncated ? "Partial file tree" : "Git tree inventory"} />
      <Metric label="Contributors listed" value={formatNumber(activity?.contributorCount ?? 0)} hint="Top accounts returned" />
    </div>

    {analysis.sourceScan?.available && <section className="repo-source-scan repo-subpanel"><div className="repo-subheading"><div><h4>Source inspection</h4><p>Bounded scan of text source files; file contents are not saved</p></div><span className="workflow-count">{analysis.sourceScan.truncated ? "Partial scan" : "Scan complete"}</span></div>
      <div className="repo-source-metrics"><Metric label="Source files" value={formatNumber(analysis.sourceScan.filesScanned)} hint={`${formatBytes(analysis.sourceScan.sourceBytesScanned)} inspected`} /><Metric label="Estimated lines" value={formatNumber(analysis.sourceScan.sourceLines)} hint="Text files only" /><Metric label="Test files" value={formatNumber(analysis.sourceScan.testFiles)} hint="Name based detection" /><Metric label="TODO / FIXME / HACK" value={`${analysis.sourceScan.todoMarkers} / ${analysis.sourceScan.fixmeMarkers} / ${analysis.sourceScan.hackMarkers}`} hint="Markers found in text" /></div>
      {analysis.sourceScan.dependencySamples.length > 0 && <div className="repo-dependency-list"><strong>Dependency samples</strong>{analysis.sourceScan.dependencySamples.slice(0, 14).map((dependency) => <code key={dependency}>{dependency}</code>)}</div>}
    </section>}

    <div className="repo-overview-grid">
      <section className="repo-subpanel repo-language-panel"><div className="repo-subheading"><div><h4>Languages and stack</h4><p>Language share and detected project tooling</p></div></div>
        {languages.length ? <div className="repo-language-list">{languages.slice(0, 6).map((language) => <div className="repo-language-row" key={language.name}><div><strong>{language.name}</strong><span>{language.percentage}%</span></div><div className="repo-language-track"><i style={{ width: `${Math.max(2, language.percentage)}%` }} /></div></div>)}</div> : <p className="repo-muted">GitHub did not report language totals.</p>}
        <div className="repo-tech-list">{(analysis.techStack ?? [analysis.language]).map((tech) => <span key={tech}>{tech}</span>)}</div>
        {analysis.topics?.length > 0 && <div className="repo-topic-list">{analysis.topics.map((topic) => <span key={topic}>{topic}</span>)}</div>}
      </section>
      <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Repository structure</h4><p>Top level folders and file types</p></div><span className="workflow-count">{structure?.directoryCount ?? 0} folders</span></div>
        {structure ? <><div className="repo-structure-dirs">{structure.topDirectories.slice(0, 16).map((directory) => <span key={directory}><b>▰</b> {directory}/</span>)}</div><div className="repo-root-files"><strong>Root files</strong><div>{structure.rootFiles.slice(0, 16).map((file) => <code key={file}>{file}</code>)}</div></div></> : <p className="repo-muted">Refresh this saved report to include the file tree.</p>}
      </section>
    </div>

    {analysis.coverage?.note && <div className="repo-coverage-note">ⓘ {analysis.coverage.note}</div>}

    <div className="repo-overview-grid">
      <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Engineering signals</h4><p>Evidence found in repository files and workflows</p></div><span className="workflow-count">{analysis.checks.filter((check) => check.status === "PRESENT").length}/{analysis.checks.length} detected</span></div>
        <div className="repo-signal-list">{analysis.checks.map((check) => <article className="repo-signal-row" key={check.id}><span className={`check-indicator ${check.status.toLowerCase()}`}>{check.status === "PRESENT" ? "✓" : "!"}</span><div><strong>{check.title}</strong><small>{check.status === "PRESENT" ? check.evidence ?? check.detail : check.recommendation ?? check.detail}</small></div><span className={`repo-signal-state ${check.status.toLowerCase()}`}>{check.status === "PRESENT" ? "Found" : "Review"}</span></article>)}</div>
        {analysis.workflows?.length > 0 && <div className="workflow-files">{analysis.workflows.map((workflow) => <span key={workflow}>{workflow}</span>)}</div>}
      </section>
      <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Recommended next steps</h4><p>Add any finding to this project as a task</p></div><span className="workflow-count">{analysis.suggestions.length} ideas</span></div>
        {analysis.suggestions.length ? <div className="repo-work-items">{analysis.suggestions.map((suggestion, index) => <article className="repo-work-item" key={`${suggestion.title}-${index}`}><div><span className={`priority-tag ${suggestion.priority.toLowerCase()}`}>{suggestion.priority}</span><span className="repo-work-category">{suggestion.category}</span><h5>{suggestion.title}</h5><p>{suggestion.description}</p></div><button className="add-work-item" disabled={working || !repository.projectId} onClick={() => onAddSuggestion(repository, suggestion)} title={repository.projectId ? "Create a task in the linked project" : "Link this repository to a project first"}>＋ Add task</button></article>)}</div> : <div className="repo-all-clear">No follow-up recommendations were generated from the available evidence.</div>}
      </section>
    </div>

    <div className="repo-overview-grid">
      <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Recent activity</h4><p>Latest commits and contributors returned by GitHub</p></div></div>
        {activity?.recentCommits?.length ? <div className="repo-activity-list">{activity.recentCommits.slice(0, 6).map((commit) => <a className="repo-activity-row" href={commit.url} target="_blank" rel="noreferrer" key={commit.sha}><span className="repo-activity-dot"/><span><strong>{commit.message || "Commit"}</strong><small>{commit.author} · {date(commit.date)}</small></span><code>{commit.sha}</code></a>)}</div> : <p className="repo-muted">No recent commits were returned.</p>}
        {activity?.contributors?.length ? <div className="repo-contributor-list"><strong>Top contributors</strong>{activity.contributors.slice(0, 6).map((contributor) => <a href={contributor.url} target="_blank" rel="noreferrer" key={contributor.login}><span>{contributor.login.slice(0, 1).toUpperCase()}</span>{contributor.login}<small>{formatNumber(contributor.contributions)} commits</small></a>)}</div> : null}
      </section>
      <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Delivery and releases</h4><p>Latest Actions runs and tagged release</p></div></div>
        {runs.length ? <div className="repo-run-list">{runs.map((run, index) => <a className="latest-run" href={run.url} target="_blank" rel="noreferrer" key={`${run.url}-${index}`}><span className={`run-indicator ${(run.conclusion || run.status).toLowerCase()}`}/><span><strong>{run.name}</strong><small>{run.conclusion || run.status} · {run.branch} · {date(run.updatedAt)}</small></span><span className="repo-chevron">↗</span></a>)}</div> : <div className="repo-all-clear">No GitHub Actions runs were returned.</div>}
        {activity?.latestRelease && "tag" in activity.latestRelease && activity.latestRelease.tag && <a className="repo-release-card" href={activity.latestRelease.url} target="_blank" rel="noreferrer"><span>Latest release</span><strong>{activity.latestRelease.name}</strong><small>{activity.latestRelease.tag} · {date(activity.latestRelease.publishedAt)}</small></a>}
      </section>
    </div>

    <div className="repo-overview-grid">
      <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Open issues</h4><p>Convert an issue into a project task</p></div><a href={`${repository.url}/issues`} target="_blank" rel="noreferrer">View all ↗</a></div>
        {analysis.issues.length ? <div className="repo-issue-list">{analysis.issues.slice(0, 10).map((issue) => <article className="repo-issue" key={issue.number}><span className="issue-number">#{issue.number}</span><div><a href={issue.url} target="_blank" rel="noreferrer">{issue.title}</a><div className="issue-labels">{issue.labels.map((label) => <span key={label}>{label}</span>)}</div></div><button className="add-work-item" disabled={working || !repository.projectId} onClick={() => onAddIssue(repository, issue)}>＋ Task</button></article>)}</div> : <div className="repo-all-clear">No open issues were returned.</div>}
      </section>
      <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Open pull requests</h4><p>Current review queue</p></div><span className="workflow-count">{analysis.pullRequests?.length ?? 0} shown</span></div>
        {analysis.pullRequests?.length ? <div className="repo-issue-list">{analysis.pullRequests.slice(0, 10).map((pr) => <article className="repo-issue" key={pr.number}><span className="issue-number">#{pr.number}</span><div><a href={pr.url} target="_blank" rel="noreferrer">{pr.title}</a><div className="issue-labels"><span>{pr.draft ? "Draft" : "Ready for review"}</span><span>by {pr.user}</span></div></div></article>)}</div> : <div className="repo-all-clear">No open pull requests were returned.</div>}
      </section>
    </div>

    {analysis.readme?.found && <section className="repo-subpanel repo-readme"><div className="repo-subheading"><div><h4>{analysis.readme.title || "README overview"}</h4><p>Project documentation extracted from the repository README</p></div><a href={`${repository.url}#readme`} target="_blank" rel="noreferrer">Read on GitHub ↗</a></div><p>{analysis.readme.excerpt}</p>{analysis.readme.sections.length > 0 && <div className="repo-readme-sections">{analysis.readme.sections.map((section) => <span key={section}>{section}</span>)}</div>}</section>}
    <footer className="repo-report-footer"><span>Analyzed {date(analysis.analyzedAt ?? repository.analyzedAt)}</span><span>GitHub API data · findings are evidence-based heuristics</span></footer>
  </div>;
}

function Metric({ label, value, hint }: { label: string; value: string; hint: string }) {
  return <article className="repo-metric"><span>{label}</span><strong>{value}</strong><small>{hint}</small></article>;
}

function formatNumber(value: number) { return new Intl.NumberFormat().format(value); }
function formatBytes(value: number) { return value < 1024 * 1024 ? `${Math.round(value / 1024)} KB` : `${(value / (1024 * 1024)).toFixed(1)} MB`; }
function date(value?: string) { return value ? new Date(value).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "—"; }
