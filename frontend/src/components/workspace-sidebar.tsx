"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/app/providers";
import { Brand, Icon } from "@/components/brand";
import type { Team, User } from "@/lib/auth";

type NavItem = { id: string; label: string; icon: React.ComponentProps<typeof Icon>["name"]; href?: string };

const privateItems: NavItem[] = [
  { id: "overview", label: "Dashboard", icon: "grid" },
  { id: "tasks", label: "My Tasks", icon: "check" },
  { id: "projects", label: "Projects", icon: "folder" },
  { id: "repository-analysis", label: "Repository", icon: "spark" },
  { id: "deployments", label: "Deployments", icon: "arrow" },
];
const teamItems: NavItem[] = [
  { id: "overview", label: "Dashboard", icon: "grid" },
  { id: "tasks", label: "Tasks", icon: "check" },
  { id: "projects", label: "Projects", icon: "folder" },
  { id: "repository-analysis", label: "Repository", icon: "spark" },
  { id: "deployments", label: "Deployments", icon: "arrow" },
  { id: "team-members", label: "Members", icon: "users" },
  { id: "all-teams", label: "All teams", icon: "folder", href: "/teams#all-teams" },
  { id: "invitations", label: "Invitations", icon: "users", href: "/teams#invitations" },
  { id: "create-team", label: "Create a team", icon: "folder", href: "/teams#create-team" },
];

export default function WorkspaceSidebar({ team, teams = [], teamsPage = false, members = [], contentReady = true }: { team?: Team; teams?: Team[]; teamsPage?: boolean; members?: User[]; contentReady?: boolean }) {
  const { user, logout } = useAuth();
  const isTeam = teamsPage || Boolean(team);
  const items = isTeam ? teamItems : privateItems;
  const defaultActiveId = teamsPage ? "all-teams" : "overview";
  const targetTeam = team ?? teams[0];
  const [expanded, setExpanded] = useState(true);
  const [activeId, setActiveId] = useState(defaultActiveId);

  useEffect(() => {
    if (!contentReady) return;
    const syncHash = () => {
      const id = window.location.hash.slice(1);
      setActiveId(items.some((item) => item.id === id) ? id : defaultActiveId);
      if (items.some((item) => item.id === id)) document.getElementById(id)?.scrollIntoView({ block: "start" });
    };
    queueMicrotask(syncHash);
    window.addEventListener("hashchange", syncHash);
    window.addEventListener("popstate", syncHash);
    return () => {
      window.removeEventListener("hashchange", syncHash);
      window.removeEventListener("popstate", syncHash);
    };
  }, [items, contentReady, defaultActiveId]);

  return <aside className="sidebar workspace-sidebar">
    <div className="sidebar-brand"><Brand /></div>
    <div className="workspace-label">WORKSPACES</div>
    <nav className="workspace-tree" aria-label="Workspace navigation">
      {([false, true] as const).map((teamRoot) => {
        const selected = teamRoot === isTeam;
        const open = selected && expanded;
        const label = teamRoot ? "Team" : "Private workspace";
        const panelId = teamRoot ? "team-navigation" : "private-navigation";
        return <div className="workspace-branch t-acc" data-open={open} data-selected={selected} key={panelId}>
          <Link className="workspace-root t-acc-head" href={teamRoot ? "/teams" : "/dashboard"}
            aria-label={label} aria-expanded={open} aria-controls={selected ? panelId : undefined}
            onClick={(event) => {
              if (selected && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
                event.preventDefault(); setExpanded((value) => !value);
              }
            }}>
            <span className="workspace-root-node"><Icon name={teamRoot ? "users" : "grid"}/></span>
            <span className="workspace-nav-label">{label}</span>
            <span className="t-acc-chevron" aria-hidden="true"><svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M4 6.5L8 10.5L12 6.5"/></svg></span>
          </Link>
          {selected && <div className="t-acc-panel" id={panelId} inert={!open}>
            <div className="t-acc-panel-inner">
              {isTeam && <div className="workspace-team-name" title={targetTeam?.name ?? "Create or join a team to use the shared workspace"}>{targetTeam?.name ?? "Create or join a team to start"}</div>}
              <ul className="workspace-leaves">
                <svg className="workspace-paths" viewBox={`0 0 34 ${items.length * 44}`} preserveAspectRatio="none" aria-hidden="true">
                  {items.map((item, index) => {
                    const y = index * 44 + 22;
                    return <path key={item.id} className="workspace-path" d={`M 8 0 C 8 ${y - 8} 12 ${y} 30 ${y}`} />;
                  })}
                  {items.map((item, index) => item.id === activeId && <path key={`active-${item.id}`} className="workspace-path-active" pathLength="1" d={`M 8 0 C 8 ${index * 44 + 14} 12 ${index * 44 + 22} 30 ${index * 44 + 22}`} />)}
                </svg>
                {items.map((item) => {
                  const href = item.href ? (teamsPage ? `#${item.id}` : item.href)
                    : teamsPage ? (targetTeam ? `/teams/${targetTeam.id}#${item.id}` : undefined) : `#${item.id}`;
                  const content = <>
                    <span className="workspace-leaf-node" aria-hidden="true"/>
                    <Icon name={item.icon}/><span className="workspace-nav-label">{item.label}</span>
                  </>;
                  return <li key={item.id}>{href ? <Link className={`workspace-leaf${item.id === activeId ? " is-active" : ""}`} href={href}
                    aria-label={item.label} aria-current={item.id === activeId ? "location" : undefined}
                    title={item.label} onClick={(event) => {
                      if (href.startsWith("#") && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) setActiveId(item.id);
                    }}>{content}</Link> : <span className="workspace-leaf" role="link" aria-disabled="true" aria-label={item.label} title={`Create or join a team to open ${item.label}`}>{content}</span>}</li>;
                })}
              </ul>
            </div>
          </div>}
        </div>;
      })}
    </nav>
    <div className="sidebar-bottom">
      {members.length > 0 && <><div className="workspace-label">{team ? "TEAM MEMBERS" : "WORKSPACE OWNER"}</div><div className="member-list">{members.slice(0, 5).map((member) => <div className="member-row" key={member.id}><span className="member-avatar">{member.name[0]}</span><span>{member.name}</span></div>)}{members.length > 5 && <small>+{members.length - 5} more</small>}</div></>}
      <div className="sidebar-profile"><div className="avatar">{user?.name?.trim().charAt(0).toUpperCase() ?? "U"}</div><div className="profile-copy"><strong>{user?.name}</strong><span>{user?.email}</span></div><button className="logout-button" aria-label="Log out" title="Log out" onClick={logout}><Icon name="logout"/></button></div>
    </div>
  </aside>;
}
