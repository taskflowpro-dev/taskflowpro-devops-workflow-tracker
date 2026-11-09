export const navigation = [
  {
    label: "Workspace",
    items: [
      { id: "overview", label: "Overview", icon: "overview" },
      { id: "projects", label: "Projects", icon: "folder" },
      { id: "teams", label: "Teams", icon: "users" },
      { id: "tasks", label: "Tasks", icon: "check" },
    ],
  },
  {
    label: "DevOps intelligence",
    items: [
      {
        id: "repository-analysis",
        label: "Repository Analyzer",
        icon: "repository",
      },
      { id: "architect", label: "AI DevOps Architect", icon: "spark" },
      { id: "workflows", label: "Workflow Builder", icon: "workflow" },
      { id: "pipelines", label: "CI/CD Pipelines", icon: "pipeline" },
      { id: "deployments", label: "Deployment Center", icon: "rocket" },
    ],
  },
  {
    label: "Engineering",
    items: [
      { id: "code-health", label: "Code Health", icon: "health" },
      { id: "security", label: "Security Center", icon: "shield" },
      { id: "dependencies", label: "Dependencies", icon: "package" },
      { id: "pull-requests", label: "Pull Requests", icon: "pull" },
    ],
  },
  {
    label: "Resources",
    items: [
      { id: "guides", label: "Implementation Guides", icon: "book" },
      { id: "documentation", label: "Documentation", icon: "file" },
      { id: "activity", label: "Activity History", icon: "activity" },
    ],
  },
  {
    label: "Settings",
    items: [
      { id: "account", label: "Account", icon: "user" },
      { id: "integrations", label: "Integrations", icon: "plug" },
      { id: "preferences", label: "Preferences", icon: "settings" },
    ],
  },
] as const;
export const viewIds = navigation.flatMap((group) =>
  group.items.map((item) => item.id),
) as string[];
export function viewFromHash(hash: string) {
  const id = hash.replace(/^#/, "");
  return viewIds.includes(id) || id === "team-members" ? id : "overview";
}
export function viewLabel(id: string) {
  return (
    navigation
      .flatMap((group) => [...group.items])
      .find((item) => item.id === id)?.label ?? "Team members"
  );
}
