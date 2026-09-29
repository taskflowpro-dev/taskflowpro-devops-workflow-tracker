"use client";

import { useAuth } from "@/app/providers";
import { Brand, Icon } from "@/components/brand";

export default function DashboardPage() {
  const { user, loading, logout } = useAuth();
  const initial = user?.name?.trim().charAt(0).toUpperCase() ?? "U";
  return <div className="dashboard-shell">
    <aside className="sidebar">
      <div className="sidebar-brand"><Brand /></div>
      <div className="workspace-label">WORKSPACE</div>
      <nav className="side-nav" aria-label="Main navigation">
        <a className="side-link active" href="/dashboard"><Icon name="grid"/><span>Dashboard</span></a>
        <a className="side-link disabled-link" href="#my-tasks"><Icon name="check"/><span>My Tasks</span><span className="soon">Soon</span></a>
        <a className="side-link disabled-link" href="#projects"><Icon name="folder"/><span>Projects</span></a>
        <a className="side-link disabled-link" href="#teams"><Icon name="users"/><span>Teams</span></a>
      </nav>
      <div className="sidebar-bottom"><div className="workspace-label">PREFERENCES</div><a className="side-link disabled-link" href="#settings"><Icon name="settings"/><span>Settings</span></a>
        <div className="sidebar-profile"><div className="avatar">{initial}</div><div className="profile-copy"><strong>{loading ? "Loading…" : user?.name}</strong><span>{user?.email}</span></div><button className="logout-button" title="Log out" onClick={logout}><Icon name="logout"/></button></div>
      </div>
    </aside>
    <main className="dashboard-main">
      <header className="topbar"><div className="breadcrumb">Workspace <span>/</span> <b>Dashboard</b></div><div className="topbar-right"><span className="live-dot"/> All changes saved <div className="avatar small-avatar">{initial}</div></div></header>
      <div className="dashboard-content">
        <div className="welcome-row"><div><div className="eyebrow">MONDAY, SEPTEMBER 29, 2026</div><h1>Good morning, {user?.name?.split(" ")[0] ?? "there"} <span className="wave">✦</span></h1><p>Here’s what’s happening across your workspace today.</p></div><button className="outline-button" disabled title="Task management is coming soon">＋ <span>New task</span></button></div>
        <section className="stats-grid" aria-label="Task statistics">
          <article className="stat-card"><div className="stat-head"><span>Open tasks</span><span className="stat-icon violet"><Icon name="check"/></span></div><div className="stat-value">—</div><div className="stat-note">Task data will appear here</div></article>
          <article className="stat-card"><div className="stat-head"><span>In progress</span><span className="stat-icon blue"><span className="ring-icon"/></span></div><div className="stat-value">—</div><div className="stat-note">Connected when tasks are enabled</div></article>
          <article className="stat-card"><div className="stat-head"><span>Completed</span><span className="stat-icon green"><Icon name="check"/></span></div><div className="stat-value">—</div><div className="stat-note">Your progress will show here</div></article>
          <article className="stat-card"><div className="stat-head"><span>Projects</span><span className="stat-icon amber"><Icon name="folder"/></span></div><div className="stat-value">—</div><div className="stat-note">Project tracking coming soon</div></article>
        </section>
        <section className="dashboard-lower">
          <article className="panel recent-panel"><div className="panel-heading"><div><h2>Recent tasks</h2><p>Your latest work, all in one place</p></div><button className="text-button" disabled>View all <Icon name="arrow"/></button></div>
            <div className="empty-state"><div className="empty-illustration"><div className="empty-sheet"><i/><i/><i/></div><span className="empty-spark">✳</span></div><h3>Your tasks will live here</h3><p>Once task management is connected, you’ll see your latest work and what needs attention.</p></div>
          </article>
          <article className="panel status-panel"><div className="panel-heading"><div><h2>Task overview</h2><p>Status across your workspace</p></div></div><div className="status-placeholder"><div className="status-ring"><span>—</span></div><div className="status-legend"><div><i className="legend-dot purple-dot"/>To do <span>—</span></div><div><i className="legend-dot blue-dot"/>In progress <span>—</span></div><div><i className="legend-dot green-dot"/>Completed <span>—</span></div></div></div><div className="placeholder-caption"><Icon name="spark"/> Task insights will appear as your team gets to work.</div></article>
        </section>
        <div className="dashboard-note"><span className="note-dot"/><span>This workspace is ready for your tasks, projects, and team.</span><button onClick={logout}>Sign out <span>→</span></button></div>
      </div>
    </main>
  </div>;
}
