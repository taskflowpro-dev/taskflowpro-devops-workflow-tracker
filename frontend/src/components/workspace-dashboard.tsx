"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/app/providers";
import { Icon } from "@/components/brand";
import RepositoryAnalysisPanel from "@/components/repository-analysis-panel";
import WorkspaceSidebar from "@/components/workspace-sidebar";
import { api, type Team, type User } from "@/lib/auth";
import type { ProjectSummary, RepositoryIssue, RepositorySuggestion, TrackedRepository } from "@/lib/repository";

type Status = "TODO" | "IN_PROGRESS" | "DONE";
type Task = { id: number; title: string; description: string; project: string; status: Status; priority: "LOW" | "MEDIUM" | "HIGH"; assigneeId: number | null; assigneeName: string | null; dueDate: string | null; createdAt: string };
type Deployment = { id: number; service: string; environment: string; version: string; status: "DEPLOYED" | "IN_PROGRESS" | "FAILED"; deployedAt: string; deployedBy: string };
const columns: { status: Status; title: string }[] = [{ status: "TODO", title: "To do" }, { status: "IN_PROGRESS", title: "In progress" }, { status: "DONE", title: "Done" }];

export default function WorkspaceDashboard({ team }: { team?: Team }) {
  const { user, loading: authLoading, logout } = useAuth();
  const router = useRouter();
  const teamId = team?.id;
  const workspaceApi = useCallback(<T,>(path: string, init: RequestInit = {}) =>
    api<T>(teamId === undefined ? path : `${path}?teamId=${teamId}`, init, true), [teamId]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [members, setMembers] = useState<User[]>([]);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
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
  const [taskProject, setTaskProject] = useState("");
  const [inviteFeedback, setInviteFeedback] = useState("");

  const refresh = useCallback(async () => {
    if (!user) return;
    setError("");
    try {
      const [taskData, memberData, deploymentData, projectData, repositoryData] = await Promise.all([
        workspaceApi<Task[]>("/api/tasks"), workspaceApi<User[]>("/api/users"), workspaceApi<Deployment[]>("/api/deployments"),
        workspaceApi<ProjectSummary[]>("/api/projects"), workspaceApi<TrackedRepository[]>("/api/repositories"),
      ]);
      setTasks(taskData); setMembers(memberData); setDeployments(deploymentData); setProjects(projectData); setRepositories(repositoryData);
      setSelectedRepository((current) => current ? repositoryData.find((repo) => repo.id === current.id) ?? repositoryData[0] ?? null : repositoryData[0] ?? null);
    } catch (reason) {
      setTasks([]); setMembers([]); setProjects([]); setDeployments([]); setRepositories([]); setSelectedRepository(null); setDialog(null);
      setError(reason instanceof Error ? reason.message : "Could not load workspace data.");
    } finally { setBusy(false); }
  }, [user, workspaceApi]);

  useEffect(() => { if (!authLoading && user) queueMicrotask(() => void refresh()); }, [authLoading, user, refresh]);

  const projectNames = useMemo(() => projects.map((project) => project.name), [projects]);
  const visibleTasks = useMemo(() => tasks.filter((task) =>
    (projectFilter === "All projects" || task.project === projectFilter) &&
    `${task.title} ${task.description} ${task.project} ${task.assigneeName ?? ""}`.toLowerCase().includes(query.toLowerCase())), [tasks, projectFilter, query]);
  const openTasks = tasks.filter((task) => task.status !== "DONE").length;
  const averageSignalScore = repositories.length
    ? Math.round(repositories.reduce((total, repo) => total + (repo.analysis.score?.value ?? 0), 0) / repositories.length)
    : null;

  async function mutate(path: string, method: string, body?: unknown): Promise<boolean> {
    setWorking(true); setError("");
    try { await workspaceApi(path, { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }); await refresh(); return true; }
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
  async function analyzeRepository(url: string) {
    setAnalyzing(true); setError("");
    try {
      const result = await workspaceApi<TrackedRepository>("/api/repositories/analyze", { method: "POST", body: JSON.stringify({ url }) });
      setSelectedRepository(result); await refresh(); setSelectedRepository(result);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not analyze that repository."); }
    finally { setAnalyzing(false); }
  }
  async function refreshRepository(repository: TrackedRepository) {
    setAnalyzing(true); setError("");
    try {
      const result = await workspaceApi<TrackedRepository>(`/api/repositories/${repository.id}/refresh`, { method: "POST" });
      setSelectedRepository(result); await refresh(); setSelectedRepository(result);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not refresh this repository."); }
    finally { setAnalyzing(false); }
  }
  async function linkRepository(repositoryId: number, projectId: number) {
    const result = await mutate(`/api/repositories/${repositoryId}/project`, "PUT", { projectId });
    if (result) {
      const repo = repositories.find((item) => item.id === repositoryId);
      const project = projects.find((item) => item.id === projectId);
      if (repo && project) setSelectedRepository({ ...repo, projectId, projectName: project.name });
    }
  }
  async function addRepositoryTask(repository: TrackedRepository, title: string, description: string, priority: "LOW" | "MEDIUM" | "HIGH") {
    if (!repository.projectName) { setError("Link this repository to a project before adding follow-up tasks."); return; }
    await mutate("/api/tasks", "POST", { title: title.slice(0, 160), project: repository.projectName, description, priority, status: "TODO" });
  }
  async function addSuggestion(repository: TrackedRepository, suggestion: RepositorySuggestion) {
    await addRepositoryTask(repository, suggestion.title, suggestion.description, suggestion.priority);
  }
  async function addIssue(repository: TrackedRepository, issue: RepositoryIssue) {
    await addRepositoryTask(repository, issue.title, `Imported from GitHub issue #${issue.number}: ${issue.url}`, "MEDIUM");
  }
  async function inviteMember(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!team) return;
    const form = event.currentTarget; const email = new FormData(form).get("email");
    setWorking(true); setInviteFeedback(""); setError("");
    try {
      await api(`/api/teams/${team.id}/invitations`, { method: "POST", body: JSON.stringify({ email }) }, true);
      form.reset(); setInviteFeedback("Invitation sent. They can accept it from their Team page.");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not invite that account."); }
    finally { setWorking(false); }
  }
  async function removeMember(member: User) {
    if (!team || !window.confirm(member.id === user?.id ? "Leave this team? You will lose access to its shared workspace." : `Remove ${member.name} from this team?`)) return;
    setWorking(true); setError("");
    try {
      await api(`/api/teams/${team.id}/members/${member.id}`, { method: "DELETE" }, true);
      if (member.id === user?.id) router.replace("/teams"); else await refresh();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not update team membership."); }
    finally { setWorking(false); }
  }

  const owner = team?.ownerId === user?.id;
  const initial = user?.name?.trim().charAt(0).toUpperCase() ?? "U";
  function openRepository(repositoryId: number) {
    const repository = repositories.find((item) => item.id === repositoryId);
    if (repository) setSelectedRepository(repository);
    document.getElementById("repository-analysis")?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return <div className="dashboard-shell">
    <WorkspaceSidebar team={team} members={members} />
    <main className="dashboard-main">
      <header className="topbar"><div className="breadcrumb">{team ? "Team workspace" : "Private workspace"} <span>/</span> <b>GitHub portfolio</b></div><div className="topbar-right"><span className="live-dot"/>{working ? "Saving changes…" : busy ? "Syncing portfolio…" : "Portfolio synced"}<div className="avatar small-avatar">{initial}</div></div></header>
      <div className="dashboard-content" id="overview">
        <RepositoryAnalysisPanel repositories={repositories} projects={projects} selected={selectedRepository} analyzing={analyzing} working={working}
          onSelect={setSelectedRepository} onAnalyze={analyzeRepository} onRefresh={(repository) => void refreshRepository(repository)}
          onRemove={(repository) => { if (window.confirm(`Remove ${repository.fullName} from this workspace?`)) void mutate(`/api/repositories/${repository.id}`, "DELETE").then((ok) => { if (ok) setSelectedRepository(null); }); }}
          onLinkProject={(repositoryId, projectId) => void linkRepository(repositoryId, projectId)}
          onAddSuggestion={(repository, suggestion) => void addSuggestion(repository, suggestion)} onAddIssue={(repository, issue) => void addIssue(repository, issue)} />
        <div className="welcome-row"><div><div className="eyebrow">{team ? "SHARED REPOSITORY INTELLIGENCE" : "YOUR GITHUB PROJECTS"}</div><h1>{team ? team.name : <>Your GitHub portfolio <span className="wave">✦</span></>}</h1><p>Understand repository health, delivery practices, and the work your projects need next.</p></div><button className="primary-compact" onClick={() => document.getElementById("repository-url")?.focus()}>＋ Analyze repository</button></div>
        {error && <div className="workspace-error" role="alert">{error}<button onClick={() => void refresh()}>Retry</button></div>}
        <section className="stats-grid portfolio-stats" aria-label="Portfolio summary">
          <article className="stat-card"><div className="stat-head"><span>Repositories</span><span className="stat-icon violet"><Icon name="spark"/></span></div><div className="stat-value">{busy ? "—" : repositories.length}</div><div className="stat-note">Connected GitHub projects</div></article>
          <article className="stat-card"><div className="stat-head"><span>Projects</span><span className="stat-icon blue"><Icon name="folder"/></span></div><div className="stat-value">{busy ? "—" : projects.length}</div><div className="stat-note">Workspace project spaces</div></article>
          <article className="stat-card"><div className="stat-head"><span>Signal coverage</span><span className="stat-icon green"><Icon name="check"/></span></div><div className="stat-value">{busy ? "—" : averageSignalScore === null ? "—" : `${averageSignalScore}%`}</div><div className="stat-note">Average across saved reports</div></article>
          <article className="stat-card"><div className="stat-head"><span>Open follow-up tasks</span><span className="stat-icon amber"><Icon name="check"/></span></div><div className="stat-value">{busy ? "—" : openTasks}</div><div className="stat-note">Work tracked across projects</div></article>
        </section>

        {team && <section className="panel team-panel" id="team-members" aria-labelledby="team-members-title">
          <div className="panel-heading"><div><h2 id="team-members-title">Team members</h2><p>Only accepted members can access this workspace.</p></div><button className="text-button" disabled={working || busy} onClick={() => void refresh()}>Refresh members</button></div>
          <ul className="team-members">{members.map((member) => <li key={member.id}><span className="member-avatar">{member.name[0]}</span><div><strong>{member.name}{member.id === team.ownerId && <span className="team-role">Owner</span>}</strong><small>{member.email}</small></div>{member.id !== team.ownerId && (owner || member.id === user?.id) && <button className="text-button" disabled={working} onClick={() => void removeMember(member)}>{member.id === user?.id ? "Leave team" : "Remove"}</button>}</li>)}</ul>
          {owner && <form className="team-invite-form workspace-form" onSubmit={inviteMember}><label>Invite a registered user<input name="email" type="email" required maxLength={254} placeholder="teammate@example.com"/></label><button className="primary-compact" disabled={working}>Send invitation</button></form>}
          {inviteFeedback && <p className="team-feedback" role="status">{inviteFeedback}</p>}
        </section>}

        <section className="panel projects-panel portfolio-projects" id="projects"><div className="panel-heading"><div><div className="eyebrow">PROJECT MANAGEMENT</div><h2>Projects</h2><p>Connect repositories to project spaces and keep their follow-up work together.</p></div><button className="outline-button" onClick={() => setDialog("project")}>＋ New project</button></div>
          {projects.length ? <div className="project-grid">{projects.map((project) => <article className="project-card portfolio-project-card" key={project.id}>
            <div className="project-card-top"><span className="project-card-icon"><Icon name="folder"/></span><button className="delete-task" aria-label={`Delete ${project.name}`} disabled={working || project.taskCount > 0} title={project.taskCount ? "Delete its tasks first" : "Delete project"} onClick={() => { if (window.confirm(`Delete project “${project.name}”?`)) void mutate(`/api/projects/${project.id}`, "DELETE"); }}>×</button></div>
            <h3>{project.name}</h3><p>{project.description || "No project description."}</p>
            <div className="portfolio-project-meta"><span>{project.taskCount} {project.taskCount === 1 ? "task" : "tasks"}</span><span>{project.repositoryCount ?? 0} {(project.repositoryCount ?? 0) === 1 ? "repository" : "repositories"}</span></div>
            {repositories.filter((repository) => repository.projectId === project.id).map((repository) => <button className="project-repository-link" key={repository.id} onClick={() => openRepository(repository.id)}><span>⌘</span><span>{repository.fullName}</span><b>View report →</b></button>)}
            <button className="primary-compact project-add-task" onClick={() => { setTaskProject(project.name); setDialog("task"); }}>＋ Add project task</button>
          </article>)}</div> : <div className="project-empty">No projects yet. Analyze a repository to create a linked project automatically, or create one here.</div>}
        </section>

        <section className="panel board-panel secondary-work-panel" id="tasks">
          <div className="panel-heading board-heading"><div><div className="eyebrow">PROJECT FOLLOW-UP</div><h2>Tasks</h2><p>Track the work that follows from repository findings.</p></div><div className="board-tools"><label className="search-field"><span aria-hidden="true">⌕</span><input aria-label="Search tasks" placeholder="Search tasks" value={query} onChange={(event) => setQuery(event.target.value)}/></label><select aria-label="Filter by project" value={projectFilter} onChange={(event) => setProjectFilter(event.target.value)}><option>All projects</option>{projectNames.map((project) => <option key={project}>{project}</option>)}</select><button className="primary-compact" onClick={() => setDialog("task")}>＋ New task</button></div></div>
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
              {!items.length && <div className="column-empty">{busy ? "Loading tasks…" : "No tasks in this column"}</div>}
            </section>;
          })}</div>
        </section>

        <section className="panel deployment-panel secondary-work-panel" id="deployments"><div className="panel-heading"><div><div className="eyebrow">RELEASE TRACKING</div><h2>Deployment records</h2><p>Keep a workspace history of releases and their status.</p></div><button className="outline-button" onClick={() => setDialog("deployment")}>＋ Record deployment</button></div>
          {deployments.length ? <div className="table-scroll"><table className="deployment-table"><thead><tr><th>Service</th><th>Environment</th><th>Version</th><th>Status</th><th>Recorded by</th><th>When</th></tr></thead><tbody>{deployments.map((deployment) => <tr key={deployment.id}><td><strong>{deployment.service}</strong></td><td>{deployment.environment}</td><td><code>{deployment.version}</code></td><td><select className={`deployment-status ${deployment.status.toLowerCase()}`} aria-label={`Status for ${deployment.service} ${deployment.version}`} value={deployment.status} onChange={(event) => void mutate(`/api/deployments/${deployment.id}`, "PATCH", { status: event.target.value })}><option value="DEPLOYED">Deployed</option><option value="IN_PROGRESS">In progress</option><option value="FAILED">Failed</option></select></td><td>{deployment.deployedBy}</td><td>{new Date(deployment.deployedAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <div className="deployment-empty"><span className="deploy-icon">↗</span><div><strong>No deployment records yet</strong><p>Add a release record when you want to track one here.</p></div></div>}
        </section>
        <div className="dashboard-note"><span className="note-dot"/><span>{team ? "This portfolio is shared only with your team members." : "Your portfolio is private. Only you can access this data."}</span><button onClick={logout}>Sign out <span>→</span></button></div>
      </div>
    </main>

    {dialog && <div className="dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setDialog(null); }}><section className="workspace-dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><div className="dialog-heading"><div><span className="eyebrow">PROJECT WORK</span><h2 id="dialog-title">{dialog === "task" ? "Add a project task" : dialog === "project" ? "Create a project" : "Record a deployment"}</h2></div><button className="icon-button" aria-label="Close" onClick={() => setDialog(null)}>×</button></div>
      {dialog === "task" ? <form className="workspace-form" onSubmit={createTask}><label>Task name<input name="title" required maxLength={160} placeholder="e.g. Add staging health checks"/></label><label>Project<select name="project" required defaultValue={taskProject}><option value="" disabled>Select a project</option>{projectNames.map((project) => <option key={project}>{project}</option>)}</select></label><label>Description<textarea name="description" rows={3} placeholder="What needs to be done?"/></label><div className="form-row"><label>Priority<select name="priority" defaultValue="MEDIUM"><option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option></select></label><label>Assignee<select name="assignee" defaultValue=""><option value="">Unassigned</option>{members.map((member) => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label></div><label>Due date<input name="dueDate" type="date"/></label><button className="primary-button" disabled={working || !projects.length}>Create task</button>{!projects.length && <p className="form-hint">Create a project or analyze a repository before adding tasks.</p>}</form> : dialog === "project" ? <form className="workspace-form" onSubmit={createProject}><label>Project name<input name="name" required maxLength={100} placeholder="e.g. Platform reliability"/></label><label>Description<textarea name="description" rows={3} placeholder="What is this project working toward?"/></label><button className="primary-button" disabled={working}>Create project</button></form> : <form className="workspace-form" onSubmit={createDeployment}><label>Service<input name="service" required maxLength={100} placeholder="e.g. Web application"/></label><div className="form-row"><label>Environment<input name="environment" required maxLength={40} placeholder="Production"/></label><label>Version<input name="version" required maxLength={80} placeholder="v1.4.2"/></label></div><label>Status<select name="status" defaultValue="DEPLOYED"><option value="DEPLOYED">Deployed</option><option value="IN_PROGRESS">In progress</option><option value="FAILED">Failed</option></select></label><button className="primary-button" disabled={working}>Save deployment</button></form>}</section></div>}
  </div>;
}
