"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Bell, ChevronDown, RefreshCw } from "lucide-react";
import { api, type TeamInvitation } from "@/lib/auth";
import { useAuth } from "@/app/providers";

export default function WorkspaceTopbar({
  workspace,
  view,
  busy,
  onRefresh,
}: {
  workspace: string;
  view: string;
  busy: boolean;
  onRefresh: () => void;
}) {
  const { user, logout } = useAuth();
  const [invitations, setInvitations] = useState<TeamInvitation[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    api<TeamInvitation[]>("/api/teams/invitations", {}, true)
      .then((items) => {
        if (live) setInvitations(items);
      })
      .catch(() => {
        if (live) setError("Invitations could not be loaded.");
      });
    return () => {
      live = false;
    };
  }, []);
  return (
    <header className="topbar">
      <div className="breadcrumb">
        {workspace}
        <span>/</span>
        <b>{view}</b>
      </div>
      <div className="topbar-right">
        <button
          className="icon-button"
          aria-label="Refresh workspace"
          disabled={busy}
          onClick={onRefresh}
        >
          <RefreshCw size={16} className={busy ? "spin" : ""} />
        </button>
        <details className="topbar-menu">
          <summary
            aria-label={`Notifications: ${invitations.length} team invitations`}
          >
            <Bell size={18} />
            {invitations.length > 0 && <span className="notification-dot" />}
          </summary>
          <div className="menu-popover">
            <strong>Notifications</strong>
            {error ? (
              <p role="alert">{error}</p>
            ) : invitations.length ? (
              invitations.map((item) => (
                <Link href="/teams#invitations" key={item.id}>
                  <b>{item.teamName}</b>
                  <small>{item.invitedByName} invited you to join</small>
                </Link>
              ))
            ) : (
              <p>You’re all caught up. No pending invitations.</p>
            )}
          </div>
        </details>
        <span className="topbar-divider" />
        <details className="topbar-menu">
          <summary aria-label="Profile menu">
            <span className="avatar small-avatar">{user?.name[0]}</span>
            <ChevronDown size={13} />
          </summary>
          <div className="menu-popover profile-popover">
            <strong>{user?.name}</strong>
            <p>{user?.email}</p>
            <a href="#account">Account settings</a>
            <button className="text-button" onClick={logout}>
              Sign out
            </button>
          </div>
        </details>
      </div>
    </header>
  );
}
