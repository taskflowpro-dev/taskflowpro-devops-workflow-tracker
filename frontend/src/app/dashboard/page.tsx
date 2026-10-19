"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "@/app/providers";
import { Brand, Icon } from "@/components/brand";
import { api, type User } from "@/lib/auth";

type Status = "TODO" | "IN_PROGRESS" | "DONE";
type Task = { id: number; title: string; description: string; project: string; status: Status; priority: "LOW" | "MEDIUM" | "HIGH"; assigneeId: number | null; assigneeName: string | null; dueDate: string | null; createdAt: string };
type Project = { id: number; name: string; description: string; taskCount: number; createdAt: string };
type Deployment = { id: number; service: string; environment: string; version: string; status: "DEPLOYED" | "IN_PROGRESS" | "FAILED"; deployedAt: string; deployedBy: string };
type RepoCheck = { id: string; title: string; status: "PRESENT" | "MISSING"; detail: string };
type RepoSuggestion = { title: string; description: string; priority: "LOW" | "MEDIUM" | "HIGH"; category: string };
type RepoIssue = { number: number; title: string; url: string; labels: string[] };
type RepoAnalysis = { fullName: string; url: string; description: string; language: string; defaultBranch: string; stars: number; forks: number; openIssues: number; updatedAt: string; topics: string[]; workflows: string[]; checks: RepoCheck[]; suggestions: RepoSuggestion[]; issues: RepoIssue[]; latestRun: null | { name: string; status: string; conclusion: string; branch: string; url: string; updatedAt: string }; summary: string };
type TrackedRepository = { id: number; url: string; fullName: string; description: string; language: string; analyzedAt: string; analysis: RepoAnalysis };
const columns: { status: Status; title: string }[] = [{ status: "TODO", title: "To do" }, { status: "IN_PROGRESS", title: "In progress" }, { status: "DONE", title: "Done" }];

export default function DashboardPage() {
  const { user, loading: authLoading, logout } = useAuth();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [members, setMembers] = useState<User[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [deployments, setDeployments] = useState<Deployment[]>([]);
  const [repositories, setRepositories] = useState<TrackedRepository[]>([]);
  const [selectedRepository, setSelectedRepository] = useState<TrackedRepository | null>(null);
  const [busy, setBusy] = useState(true);
  const [working, setWorking] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [projectFilter, setProjectFilter] = useState("All projects");
  const [dialog, setDialog] = useState<"task" | "project" | "deployment" | null>(null);

  const refresh = useCallback(async () => {
    if (!user) return;
    setError("");
    try {
      const [taskData, memberData, deploymentData, projectData, repositoryData] = await Promise.all([
        api<Task[]>("/api/tasks", {}, true), api<User[]>("/api/users", {}, true), api<Deployment[]>("/api/deployments", {}, true), api<Project[]>("/api/projects", {}, true), api<TrackedRepository[]>("/api/repositories", {}, true),
      ]);
      setTasks(taskData); setMembers(memberData); setDeployments(deploymentData); setProjects(projectData); setRepositories(repositoryData);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not load workspace data."); }
    finally { setBusy(false); }
  }, [user]);

  useEffect(() => { if (!authLoading && user) queueMicrotask(() => void refresh()); }, [authLoading, user, refresh]);

  const projectNames = useMemo(() => projects.map((project) => project.name), [projects]);
  const visibleTasks = useMemo(() => tasks.filter((task) =>
    (projectFilter === "All projects" || task.project === projectFilter) &&
    `${task.title} ${task.description} ${task.project} ${task.assigneeName ?? ""}`.toLowerCase().includes(query.toLowerCase())), [tasks, projectFilter, query]);
  const counts = { open: tasks.filter((task) => task.status !== "DONE").length, progress: tasks.filter((task) => task.status === "IN_PROGRESS").length, done: tasks.filter((task) => task.status === "DONE").length };

  async function mutate(path: string, method: string, body?: unknown): Promise<boolean> {
    setWorking(true); setError("");
    try { await api(path, { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }, true); await refresh(); return true; }
    catch (reason) { setError(reason instanceof Error ? reason.message : "That change could not be saved."); return false; }
    finally { setWorking(false); }
  }

  async function createTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    if (await mutate("/api/tasks", "POST", { title: form.get("title"), project: form.get("project"), description: form.get("description"), priority: form.get("priority"), status: "TODO", assigneeId: form.get("assignee") ? Number(form.get("assignee")) : null, dueDate: form.get("dueDate") || null })) setDialog(null);
  }

  async function createDeployment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    if (await mutate("/api/deployments", "POST", { service: form.get("service"), environment: form.get("environment"), version: form.get("version"), status: form.get("status") })) setDialog(null);
  }

  async function createProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    if (await mutate("/api/projects", "POST", { name: form.get("name"), description: form.get("description") })) setDialog(null);
  }

  async function analyzeRepository(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const url = new FormData(event.currentTarget).get("repositoryUrl");
    if (typeof url !== "string") return;
    setAnalyzing(true); setError("");
    try {
      const result = await api<TrackedRepository>("/api/repositories/analyze", { method: "POST", body: JSON.stringify({ url }) }, true);
      setSelectedRepository(result); await refresh();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not analyze that repository."); }
    finally { setAnalyzing(false); }
  }

  async function refreshRepository(repository: TrackedRepository) {
    setAnalyzing(true); setError("");
    try {
      const result = await api<TrackedRepository>(`/api/repositories/${repository.id}/refresh`, { method: "POST" }, true);
      setSelectedRepository(result); await refresh();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not refresh this repository."); }
    finally { setAnalyzing(false); }
  }

  async function addRepoWorkItem(repository: TrackedRepository, title: string, description: string, priority: "LOW" | "MEDIUM" | "HIGH") {
    const project = repository.analysis.fullName.slice(0, 100);
    await mutate("/api/tasks", "POST", { title: title.slice(0, 160), project, description, priority, status: "TODO" });
  }

  const initial = user?.name?.trim().charAt(0).toUpperCase() ?? "U";
  return <div className="dashboard-shell">
    <aside className="sidebar">
      <div className="sidebar-brand"><Brand /></div><div className="workspace-label">WORKSPACE</div>
      <nav className="side-nav" aria-label="Main navigation">
        <a className="side-link active" href="#overview"><Icon name="grid"/><span>Dashboard</span></a>
        <a className="side-link" href="#tasks"><Icon name="check"/><span>My Tasks</span></a>
        <a className="side-link" href="#projects"><Icon name="folder"/><span>Projects</span></a>
        <a className="side-link" href="#repository-analysis"><Icon name="spark"/><span>Repository</span></a>
        <a className="side-link" href="#deployments"><Icon name="spark"/><span>Deployments</span></a>
      </nav>
      <div className="sidebar-bottom"><div className="workspace-label">TEAM</div><div className="member-list">{members.slice(0, 5).map((member) => <div className="member-row" key={member.id}><span className="member-avatar">{member.name[0]}</span><span>{member.name}</span></div>)}{members.length > 5 && <small>+{members.length - 5} more</small>}</div>
        <div className="sidebar-profile"><div className="avatar">{initial}</div><div className="profile-copy"><strong>{authLoading ? "Loading…" : user?.name}</strong><span>{user?.email}</span></div><button className="logout-button" title="Log out" onClick={logout}><Icon name="logout"/></button></div>
      </div>
    </aside>
    <main className="dashboard-main">
      <header className="topbar"><div className="breadcrumb">Workspace <span>/</span> <b>Dashboard</b></div><div className="topbar-right"><span className="live-dot"/>{working ? "Saving changes…" : busy ? "Syncing workspace…" : "Workspace synced"}<div className="avatar small-avatar">{initial}</div></div></header>
      <div className="dashboard-content" id="overview">
        <div className="welcome-row"><div><div className="eyebrow">YOUR WORKSPACE</div><h1>Good day, {user?.name?.split(" ")[0] ?? "there"} <span className="wave">✦</span></h1><p>Your projects, tasks, and delivery status at a glance.</p></div><button className="primary-compact" onClick={() => setDialog("task")}>＋ New task</button></div>
        {error && <div className="workspace-error" role="alert">{error}<button onClick={() => void refresh()}>Retry</button></div>}
        <section className="stats-grid" aria-label="Task statistics">
          <article className="stat-card"><div className="stat-head"><span>Open tasks</span><span className="stat-icon violet"><Icon name="check"/></span></div><div className="stat-value">{busy ? "—" : counts.open}</div><div className="stat-note">Tasks that still need attention</div></article>
          <article className="stat-card"><div className="stat-head"><span>In progress</span><span className="stat-icon blue"><span className="ring-icon"/></span></div><div className="stat-value">{busy ? "—" : counts.progress}</div><div className="stat-note">Work moving forward</div></article>
          <article className="stat-card"><div className="stat-head"><span>Completed</span><span className="stat-icon green"><Icon name="check"/></span></div><div className="stat-value">{busy ? "—" : counts.done}</div><div className="stat-note">Finished tasks</div></article>
          <article className="stat-card"><div className="stat-head"><span>Projects</span><span className="stat-icon amber"><Icon name="folder"/></span></div><div className="stat-value">{busy ? "—" : projects.length}</div><div className="stat-note">Active project spaces</div></article>
        </section>

        <section className="panel board-panel" id="tasks">
          <div className="panel-heading board-heading"><div><h2>Task board</h2><p>Move work through delivery and keep owners clear.</p></div><div className="board-tools"><label className="search-field"><span aria-hidden="true">⌕</span><input aria-label="Search tasks" placeholder="Search tasks" value={query} onChange={(event) => setQuery(event.target.value)}/></label><select aria-label="Filter by project" value={projectFilter} onChange={(event) => setProjectFilter(event.target.value)}><option>All projects</option>{projectNames.map((project) => <option key={project}>{project}</option>)}</select></div></div>
          <div className="kanban-board">{columns.map((column) => {
            const items = visibleTasks.filter((task) => task.status === column.status);
            return <section className="kanban-column" key={column.status} aria-label={column.title}>
              <div className="column-heading"><span className={`column-marker ${column.status.toLowerCase()}`}/><h3>{column.title}</h3><span className="column-count">{items.length}</span>{column.status === "TODO" && <button className="icon-button" aria-label="Add task" onClick={() => setDialog("task")}>＋</button>}</div>
              <div className="task-list">{items.map((task) => <article className="task-card" key={task.id}>
                <div className="task-card-top"><span className={`priority-tag ${task.priority.toLowerCase()}`}>{task.priority}</span><button className="delete-task" aria-label={`Delete ${task.title}`} disabled={working} onClick={() => { if (window.confirm(`Delete “${task.title}”?`)) void mutate(`/api/tasks/${task.id}`, "DELETE"); }}>×</button></div>
                <h4>{task.title}</h4>{task.description && <p className="task-description">{task.description}</p>}<span className="project-chip">{task.project}</span>
                <div className="task-card-bottom"><label className="assignee-select"><span className="member-avatar tiny">{task.assigneeName?.[0] ?? "?"}</span><select aria-label={`Assignee for ${task.title}`} value={task.assigneeId ?? ""} onChange={(event) => void mutate(`/api/tasks/${task.id}`, "PATCH", { assigneeId: event.target.value ? Number(event.target.value) : null, setAssignee: true })}><option value="">Unassigned</option>{members.map((member) => <option value={member.id} key={member.id}>{member.name}</option>)}</select></label>
                  <select className="status-select" aria-label={`Status for ${task.title}`} value={task.status} onChange={(event) => void mutate(`/api/tasks/${task.id}`, "PATCH", { status: event.target.value })}>{columns.map((option) => <option key={option.status} value={option.status}>{option.title}</option>)}</select></div>
                {task.dueDate && <div className="task-due">Due {new Date(`${task.dueDate}T12:00:00`).toLocaleDateString()}</div>}
              </article>)}</div>
              {!items.length && <div className="column-empty">{busy ? "Loading tasks…" : "Nothing here yet"}</div>}
            </section>;
          })}</div>
        </section>

        <section className="panel projects-panel" id="projects"><div className="panel-heading"><div><h2>Projects</h2><p>Organize work into clear project spaces.</p></div><button className="outline-button" onClick={() => setDialog("project")}>＋ New project</button></div>
          {projects.length ? <div className="project-grid">{projects.map((project) => <article className="project-card" key={project.id}><div className="project-card-top"><span className="project-card-icon"><Icon name="folder"/></span><button className="delete-task" aria-label={`Delete ${project.name}`} disabled={working || project.taskCount > 0} title={project.taskCount ? "Move or delete its tasks first" : "Delete project"} onClick={() => { if (window.confirm(`Delete project “${project.name}”?`)) void mutate(`/api/projects/${project.id}`, "DELETE"); }}>×</button></div><h3>{project.name}</h3><p>{project.description || "No project description."}</p><span className="project-task-count">{project.taskCount} {project.taskCount === 1 ? "task" : "tasks"}</span></article>)}</div> : <div className="project-empty">No projects yet. Create a project or add your first task to get started.</div>}
        </section>

        <section className="panel repository-panel" id="repository-analysis"><div className="panel-heading"><div><h2>GitHub repository analysis</h2><p>Paste a public repository link to inspect its work backlog and delivery pipeline.</p></div><span className="repo-github-mark">GH</span></div>
          <form className="repo-analyze-form" onSubmit={analyzeRepository}><label className="repo-url-field"><span>Repository URL</span><input name="repositoryUrl" type="url" required placeholder="https://github.com/owner/repository"/></label><button className="primary-compact" disabled={analyzing}>{analyzing ? "Analyzing…" : "Analyze repository"}</button></form>
          {repositories.length > 0 && <div className="tracked-repos"><span className="repo-section-label">SAVED REPOSITORIES</span>{repositories.map((repository) => <button key={repository.id} className={`tracked-repo ${selectedRepository?.id === repository.id ? "selected" : ""}`} onClick={() => setSelectedRepository(repository)}><span className="tracked-repo-icon">⌘</span><span><strong>{repository.fullName}</strong><small>{repository.language} · analyzed {new Date(repository.analyzedAt).toLocaleDateString()}</small></span><span className="repo-chevron">›</span></button>)}</div>}
          {selectedRepository ? <div className="repo-analysis-result"><div className="repo-result-heading"><div><div className="repo-kicker">REPOSITORY REPORT</div><h3>{selectedRepository.analysis.fullName}</h3><p>{selectedRepository.analysis.description || "No repository description provided."}</p><a href={selectedRepository.url} target="_blank" rel="noreferrer">View on GitHub ↗</a></div><div className="repo-result-actions"><button className="outline-button" disabled={analyzing} onClick={() => void refreshRepository(selectedRepository)}>↻ Refresh</button><button className="repo-remove" disabled={working} onClick={async () => { if (window.confirm(`Remove ${selectedRepository.analysis.fullName} from this workspace?`) && await mutate(`/api/repositories/${selectedRepository.id}`, "DELETE")) setSelectedRepository(null); }}>Remove</button></div></div>
            <div className="repo-facts"><div><strong>{selectedRepository.analysis.language}</strong><span>Primary language</span></div><div><strong>{selectedRepository.analysis.defaultBranch}</strong><span>Default branch</span></div><div><strong>{selectedRepository.analysis.stars.toLocaleString()}</strong><span>Stars</span></div><div><strong>{selectedRepository.analysis.forks.toLocaleString()}</strong><span>Forks</span></div><div><strong>{selectedRepository.analysis.openIssues}</strong><span>Open issues</span></div><div><strong>{selectedRepository.analysis.updatedAt ? new Date(selectedRepository.analysis.updatedAt).toLocaleDateString() : "—"}</strong><span>Last push</span></div></div>
            <p className="repo-summary">{selectedRepository.analysis.summary}</p>
            <div className="repo-analysis-grid"><section className="repo-subpanel"><div className="repo-subheading"><div><h4>CI/CD pipeline</h4><p>Signals found in GitHub Actions workflows</p></div><span className="workflow-count">{selectedRepository.analysis.workflows.length} workflows</span></div>
              {selectedRepository.analysis.latestRun && <a className="latest-run" href={selectedRepository.analysis.latestRun.url} target="_blank" rel="noreferrer"><span className={`run-indicator ${selectedRepository.analysis.latestRun.conclusion || selectedRepository.analysis.latestRun.status}`}/><span><strong>{selectedRepository.analysis.latestRun.name}</strong><small>{selectedRepository.analysis.latestRun.conclusion || selectedRepository.analysis.latestRun.status} · {selectedRepository.analysis.latestRun.branch}</small></span><span className="repo-chevron">↗</span></a>}
              <div className="pipeline-checks">{selectedRepository.analysis.checks.map((check) => <div className="pipeline-check" key={check.id}><span className={`check-indicator ${check.status.toLowerCase()}`}>{check.status === "PRESENT" ? "✓" : "!"}</span><span><strong>{check.title}</strong><small>{check.detail}</small></span></div>)}</div>
              {selectedRepository.analysis.workflows.length > 0 && <div className="workflow-files">{selectedRepository.analysis.workflows.map((name) => <span key={name}>{name}</span>)}</div>}
            </section>
            <section className="repo-subpanel"><div className="repo-subheading"><div><h4>Suggested backlog</h4><p>Jira-style work items based on missing pipeline capabilities</p></div><span className="workflow-count">{selectedRepository.analysis.suggestions.length} ideas</span></div>
              {selectedRepository.analysis.suggestions.length ? <div className="repo-work-items">{selectedRepository.analysis.suggestions.map((item) => <article className="repo-work-item" key={item.title}><div><span className={`priority-tag ${item.priority.toLowerCase()}`}>{item.priority}</span><span className="repo-work-category">{item.category}</span><h5>{item.title}</h5><p>{item.description}</p></div><button className="add-work-item" disabled={working} onClick={() => void addRepoWorkItem(selectedRepository, item.title, item.description, item.priority)} title="Add this recommendation to the task board">＋ Add task</button></article>)}</div> : <div className="repo-all-clear">All checked CI/CD capabilities were detected in the repository workflows.</div>}
            </section></div>
            <section className="repo-issues"><div className="repo-subheading"><div><h4>Open GitHub issues</h4><p>Turn an existing issue into a workspace task when useful.</p></div><a href={`${selectedRepository.url}/issues`} target="_blank" rel="noreferrer">View all ↗</a></div>
              {selectedRepository.analysis.issues.length ? <div className="repo-issue-list">{selectedRepository.analysis.issues.map((issue) => <article className="repo-issue" key={issue.number}><span className="issue-number">#{issue.number}</span><div><a href={issue.url} target="_blank" rel="noreferrer">{issue.title}</a><div className="issue-labels">{issue.labels.map((label) => <span key={label}>{label}</span>)}</div></div><button className="add-work-item" disabled={working} onClick={() => void addRepoWorkItem(selectedRepository, issue.title, `Imported from GitHub issue #${issue.number}: ${issue.url}`, "MEDIUM")}>＋ Add task</button></article>)}</div> : <div className="repo-all-clear">No open issues were found for this repository.</div>}
            </section>
            {selectedRepository.analysis.topics.length > 0 && <div className="repo-topics"><span>TOPICS</span>{selectedRepository.analysis.topics.map((topic) => <span className="repo-topic" key={topic}>{topic}</span>)}</div>}
          </div> : repositories.length === 0 && <div className="repo-empty"><span className="repo-empty-icon">⌘</span><strong>No repositories connected yet</strong><p>Start with a public GitHub repository. Its report will be saved to this workspace.</p></div>}
        </section>

        <section className="panel deployment-panel" id="deployments"><div className="panel-heading"><div><h2>Recent deployments</h2><p>Keep release activity visible to the whole team.</p></div><button className="outline-button" onClick={() => setDialog("deployment")}>＋ Record deployment</button></div>
          {deployments.length ? <div className="table-scroll"><table className="deployment-table"><thead><tr><th>Service</th><th>Environment</th><th>Version</th><th>Status</th><th>Deployed by</th><th>When</th></tr></thead><tbody>{deployments.map((deployment) => <tr key={deployment.id}><td><strong>{deployment.service}</strong></td><td>{deployment.environment}</td><td><code>{deployment.version}</code></td><td><select className={`deployment-status ${deployment.status.toLowerCase()}`} aria-label={`Status for ${deployment.service} ${deployment.version}`} value={deployment.status} onChange={(event) => void mutate(`/api/deployments/${deployment.id}`, "PATCH", { status: event.target.value })}><option value="DEPLOYED">Deployed</option><option value="IN_PROGRESS">In progress</option><option value="FAILED">Failed</option></select></td><td>{deployment.deployedBy}</td><td>{new Date(deployment.deployedAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <div className="deployment-empty"><span className="deploy-icon">↗</span><div><strong>No deployments recorded yet</strong><p>Record a release to start tracking delivery history here.</p></div><button className="text-button" onClick={() => setDialog("deployment")}>Add first deployment <Icon name="arrow"/></button></div>}
        </section>
        <div className="dashboard-note"><span className="note-dot"/><span>Workspace data is shared with your team and saved to the database.</span><button onClick={logout}>Sign out <span>→</span></button></div>
      </div>
    </main>

    {dialog && <div className="dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setDialog(null); }}><section className="workspace-dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><div className="dialog-heading"><div><span className="eyebrow">WORKSPACE</span><h2 id="dialog-title">{dialog === "task" ? "Create a task" : "Record a deployment"}</h2></div><button className="icon-button" aria-label="Close" onClick={() => setDialog(null)}>×</button></div>
      {dialog === "task" ? <form className="workspace-form" onSubmit={createTask}><label>Task name<input name="title" required maxLength={160} placeholder="e.g. Add staging health checks"/></label><label>Project<input name="project" required maxLength={100} list="project-options" placeholder="e.g. Platform reliability"/><datalist id="project-options">{projectNames.map((project) => <option key={project} value={project}/>)}</datalist></label><label>Description<textarea name="description" rows={3} placeholder="What needs to be done?"/></label><div className="form-row"><label>Priority<select name="priority" defaultValue="MEDIUM"><option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option></select></label><label>Assignee<select name="assignee" defaultValue=""><option value="">Unassigned</option>{members.map((member) => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label></div><label>Due date<input name="dueDate" type="date"/></label><button className="primary-button" disabled={working}>Create task</button></form> : dialog === "project" ? <form className="workspace-form" onSubmit={createProject}><label>Project name<input name="name" required maxLength={100} placeholder="e.g. Platform reliability"/></label><label>Description<textarea name="description" rows={3} placeholder="What is this project working toward?"/></label><button className="primary-button" disabled={working}>Create project</button></form> : <form className="workspace-form" onSubmit={createDeployment}><label>Service<input name="service" required maxLength={100} placeholder="e.g. Web application"/></label><div className="form-row"><label>Environment<input name="environment" required maxLength={40} placeholder="Production"/></label><label>Version<input name="version" required maxLength={80} placeholder="v1.4.2"/></label></div><label>Status<select name="status" defaultValue="DEPLOYED"><option value="DEPLOYED">Deployed</option><option value="IN_PROGRESS">In progress</option><option value="FAILED">Failed</option></select></label><button className="primary-button" disabled={working}>Save deployment</button></form>}</section></div>}
  </div>;
}
