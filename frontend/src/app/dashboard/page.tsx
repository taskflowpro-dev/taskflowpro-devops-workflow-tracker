"use client";

import { useAuth } from "@/app/providers";
import WorkspaceDashboard from "@/components/workspace-dashboard";

export default function DashboardPage() {
  const { user, loading } = useAuth();
  if (loading || !user) return <div className="dashboard-shell"><main className="dashboard-content" role="status">Loading your private workspace…</main></div>;
  return <WorkspaceDashboard key={user.id} />;
}
