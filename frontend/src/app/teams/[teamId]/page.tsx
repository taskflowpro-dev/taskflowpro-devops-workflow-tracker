"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useAuth } from "@/app/providers";
import WorkspaceDashboard from "@/components/workspace-dashboard";
import { api, type Team } from "@/lib/auth";

const loading = <div className="dashboard-shell"><main className="dashboard-content" role="status">Loading team workspace…</main></div>;

export default function TeamWorkspacePage() {
  return <Suspense fallback={loading}><TeamRoute /></Suspense>;
}

function TeamRoute() {
  const params = useParams<{ teamId: string }>();
  const { user, loading: authLoading } = useAuth();
  if (authLoading || !user) return loading;
  const teamId = Number(params.teamId);
  if (!/^[1-9]\d*$/.test(params.teamId) || !Number.isSafeInteger(teamId)) return <TeamError message="Team workspace was not found." />;
  return <TeamLoader key={`${user.id}:${teamId}`} teamId={teamId} />;
}

function TeamLoader({ teamId }: { teamId: number }) {
  const [team, setTeam] = useState<Team | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api<Team>(`/api/teams/${teamId}`, {}, true).then((value) => {
      if (active) setTeam(value);
    }).catch((reason) => {
      if (active) setError(reason instanceof Error ? reason.message : "Could not load this team.");
    });
    return () => { active = false; };
  }, [teamId]);
  if (error) return <TeamError message={error} />;
  if (!team) return loading;
  return <WorkspaceDashboard team={team} />;
}

function TeamError({ message }: { message: string }) {
  return <div className="dashboard-shell"><main className="dashboard-content"><p className="form-error" role="alert">{message}</p><Link href="/teams">Back to your teams</Link> · <Link href="/dashboard">Private workspace</Link></main></div>;
}
