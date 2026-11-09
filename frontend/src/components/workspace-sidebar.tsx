"use client";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ChevronsUpDown, Menu, X } from "lucide-react";
import { useAuth } from "@/app/providers";
import { Brand, Icon } from "@/components/brand";
import { api, type Team, type User } from "@/lib/auth";
import { navigation, viewFromHash } from "@/lib/navigation";

export default function WorkspaceSidebar({
  team,
  teams: suppliedTeams,
  teamsPage = false,
  members = [],
}: {
  team?: Team;
  teams?: Team[];
  teamsPage?: boolean;
  members?: User[];
  contentReady?: boolean;
}) {
  const { user, logout } = useAuth();
  const router = useRouter();
  const [teams, setTeams] = useState<Team[]>(suppliedTeams ?? []);
  const [active, setActive] = useState("overview");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(false);
  useEffect(() => {
    const media = window.matchMedia("(max-width: 650px)");
    const sync = () => setIsMobile(media.matches);
    sync();
    media.addEventListener("change", sync);
    return () => media.removeEventListener("change", sync);
  }, []);
  useEffect(() => {
    if (suppliedTeams) return;
    let live = true;
    api<Team[]>("/api/teams", {}, true)
      .then((value) => {
        if (live) setTeams(value);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [suppliedTeams]);
  useEffect(() => {
    const sync = () => {
      setActive(teamsPage ? "teams" : viewFromHash(window.location.hash));
      setMobileOpen(false);
    };
    sync();
    window.addEventListener("hashchange", sync);
    return () => window.removeEventListener("hashchange", sync);
  }, [teamsPage]);
  const base = team ? `/teams/${team.id}` : "/dashboard";
  return (
    <>
      <button
        className="mobile-nav-toggle icon-button"
        aria-label={mobileOpen ? "Close navigation" : "Open navigation"}
        aria-expanded={mobileOpen}
        onClick={() => setMobileOpen(!mobileOpen)}
      >
        {mobileOpen ? <X size={20} /> : <Menu size={20} />}
      </button>
      {mobileOpen && (
        <button
          className="nav-overlay"
          aria-label="Close navigation"
          onClick={() => setMobileOpen(false)}
        />
      )}
      <aside
        inert={isMobile && !mobileOpen}
        className={`sidebar workspace-sidebar ${mobileOpen ? "mobile-open" : ""}`}
      >
        <a
          href="/dashboard"
          className="sidebar-brand"
          aria-label="TaskFlow Pro home"
        >
          <Brand />
        </a>
        <label className="workspace-switcher">
          <span className="workspace-switcher-icon">
            <Icon name={team ? "users" : "boxes"} />
          </span>
          <span className="workspace-switcher-copy">
            <small>WORKSPACE</small>
            <select
              aria-label="Switch workspace"
              value={team?.id ?? "personal"}
              onChange={(event) =>
                router.push(
                  event.target.value === "personal"
                    ? "/dashboard"
                    : `/teams/${event.target.value}`,
                )
              }
            >
              <option value="personal">Personal workspace</option>
              {(suppliedTeams ?? teams).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
          </span>
          <ChevronsUpDown size={14} aria-hidden="true" />
        </label>
        <nav className="platform-nav" aria-label="Main navigation">
          {navigation.map((group) => (
            <div className="nav-group" key={group.label}>
              <div className="workspace-label">{group.label}</div>
              {group.items.map((item) => {
                const selected = active === item.id;
                const href =
                  item.id === "teams"
                    ? "/teams#all-teams"
                    : `${teamsPage ? "/dashboard" : base}#${item.id}`;
                return (
                  <a
                    key={item.id}
                    href={href}
                    onClick={() => {
                      setActive(item.id);
                      setMobileOpen(false);
                    }}
                    className={`nav-link ${selected ? "is-active" : ""}`}
                    aria-current={selected ? "page" : undefined}
                  >
                    <Icon name={item.icon} />
                    <span>{item.label}</span>
                    {item.id === "architect" && (
                      <span className="nav-badge">AI</span>
                    )}
                  </a>
                );
              })}
            </div>
          ))}
          {team && (
            <a
              className={`nav-link ${active === "team-members" ? "is-active" : ""}`}
              href={`${base}#team-members`}
            >
              <Icon name="users" />
              <span>Team members ({members.length})</span>
            </a>
          )}
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-profile">
            <div className="avatar">
              {user?.name?.[0]?.toUpperCase() ?? "U"}
            </div>
            <a href={`${base}#account`} className="profile-copy">
              <strong>{user?.name}</strong>
              <span>{team ? "Team workspace" : "Personal account"}</span>
            </a>
            <button
              className="icon-button"
              aria-label="Sign out"
              onClick={logout}
            >
              <Icon name="logout" />
            </button>
          </div>
        </div>
      </aside>
    </>
  );
}
