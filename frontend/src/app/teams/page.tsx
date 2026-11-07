"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/app/providers";
import { Icon } from "@/components/brand";
import WorkspaceSidebar from "@/components/workspace-sidebar";
import { api, type Team, type TeamInvitation } from "@/lib/auth";

export default function TeamsPage() {
  const { user, loading } = useAuth();
  if (loading || !user) return <div className="dashboard-shell"><main className="dashboard-content" role="status">Loading your teams…</main></div>;
  return <TeamsHub key={user.id} />;
}

function TeamsHub() {
  const { user } = useAuth();
  const router = useRouter();
  const [teams, setTeams] = useState<Team[]>([]);
  const [invitations, setInvitations] = useState<TeamInvitation[]>([]);
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const refresh = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [teamData, invitationData] = await Promise.all([
        api<Team[]>("/api/teams", {}, true), api<TeamInvitation[]>("/api/teams/invitations", {}, true),
      ]);
      setTeams(teamData); setInvitations(invitationData);
    } catch (reason) {
      setTeams([]); setInvitations([]);
      setError(reason instanceof Error ? reason.message : "Could not load your teams.");
    } finally { setLoading(false); }
  }, []);
  useEffect(() => { queueMicrotask(() => void refresh()); }, [refresh]);
  useEffect(() => {
    if (!loading && teams.length > 0 && !window.location.hash) router.replace(`/teams/${teams[0].id}`);
  }, [loading, teams, router]);

  async function createTeam(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = new FormData(event.currentTarget).get("name");
    setWorking(true); setError(""); setFeedback("");
    try {
      const team = await api<Team>("/api/teams", { method: "POST", body: JSON.stringify({ name }) }, true);
      router.push(`/teams/${team.id}`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not create your team."); }
    finally { setWorking(false); }
  }

  async function respond(invitation: TeamInvitation, accept: boolean) {
    setWorking(true); setError(""); setFeedback("");
    try {
      if (accept) {
        const team = await api<Team>(`/api/teams/invitations/${invitation.id}/accept`, { method: "POST" }, true);
        router.push(`/teams/${team.id}`);
      } else {
        await api(`/api/teams/invitations/${invitation.id}`, { method: "DELETE" }, true);
        await refresh(); setFeedback("Invitation declined.");
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not respond to the invitation."); }
    finally { setWorking(false); }
  }

  return <div className="dashboard-shell">
    <WorkspaceSidebar teamsPage teams={teams} contentReady={!loading} />
    <main className="dashboard-main">
      <header className="topbar"><div className="breadcrumb">Workspaces <span>/</span> <b>Team</b></div><button className="text-button" disabled={working || loading} onClick={() => void refresh()}>Refresh teams</button></header>
      <div className="dashboard-content">
        <div className="welcome-row"><div><div className="eyebrow">WORK TOGETHER</div><h1>Your teams</h1><p>Choose a shared workspace or create a team. Your personal work stays private.</p></div></div>
        {error && <div className="workspace-error" role="alert">{error}</div>}
        {feedback && <p className="team-feedback" role="status">{feedback}</p>}
        {loading ? <p role="status">Loading teams and invitations…</p> : <>
          <section className="panel team-panel" id="all-teams" aria-labelledby="your-teams-title">
            <div className="panel-heading"><div><h2 id="your-teams-title">Shared workspaces</h2><p>Only teams you have joined appear here.</p></div></div>
            {teams.length ? <div className="team-grid">{teams.map((team) => <Link className="team-card" href={`/teams/${team.id}`} key={team.id}><span className="stat-icon blue"><Icon name="users"/></span><h3>{team.name}</h3><p>{team.memberCount} {team.memberCount === 1 ? "member" : "members"}<span className="team-role">{team.ownerId === user?.id ? "Owner" : "Member"}</span></p><span className="team-card-link">Open workspace <Icon name="arrow"/></span></Link>)}</div> : <p className="project-empty">You have not joined any teams yet. Create one below or accept an invitation.</p>}
          </section>
          <section className="panel team-panel" id="invitations" aria-labelledby="invitations-title">
            <div className="panel-heading"><div><h2 id="invitations-title">Invitations</h2><p>Accept an invitation to access that team’s shared workspace.</p></div></div>
            {invitations.length ? <ul className="team-invitations">{invitations.map((invitation) => <li key={invitation.id}><div><strong>{invitation.teamName}</strong><p>Invited by {invitation.invitedByName}</p></div><div className="team-actions"><button className="outline-button" disabled={working} onClick={() => void respond(invitation, false)}>Decline</button><button className="primary-compact" disabled={working} onClick={() => void respond(invitation, true)}>Accept invitation</button></div></li>)}</ul> : <p className="project-empty">No pending invitations.</p>}
          </section>
        </>}
        <section className="panel team-panel" id="create-team" aria-labelledby="create-team-title">
          <div className="panel-heading"><div><h2 id="create-team-title">Create a team</h2><p>You will be the owner and can invite registered users from the workspace.</p></div></div>
          <form className="team-invite-form workspace-form" onSubmit={createTeam}><label>Team name<input name="name" required maxLength={100} placeholder="e.g. Platform team"/></label><button className="primary-compact" disabled={working || loading}>Create team</button></form>
        </section>
      </div>
    </main>
  </div>;
}
