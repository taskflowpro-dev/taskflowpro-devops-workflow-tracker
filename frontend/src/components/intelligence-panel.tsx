"use client";
import { ArrowUpRight } from "lucide-react";
import { EmptyState } from "@/components/platform-ui";
import type { Props } from "./intelligence/shared";
import RoadmapView from "./intelligence/roadmap";
import BuilderView from "./intelligence/builder";
import DeploymentView from "./intelligence/deployment";
import EngineeringView from "./intelligence/engineering";
import ChatView from "./intelligence/chat";
import ActivityView from "./intelligence/activity";
import SettingsView from "./intelligence/settings";
import DocumentationView from "./intelligence/documentation";
export { default as RoadmapView } from "./intelligence/roadmap";

const descriptions: Record<string, string> = {
  architect: "Turn repository evidence into a practical engineering roadmap.",
  workflows: "Build a pipeline around the tools your repository actually uses.",
  deployments:
    "Design your delivery architecture, review configurations, and prepare for release.",
  pipelines: "Inspect the execution history returned by GitHub Actions.",
  "code-health":
    "Understand the engineering practices detected in your codebase.",
  security:
    "Review security configuration and the checks you still need to run.",
  dependencies: "Explore dependency samples from inspected manifests.",
  "pull-requests": "Keep your review queue moving with repository context.",
  guides:
    "Work through implementation steps and keep your progress with your workspace.",
  activity: "A persistent record of intelligence actions in this workspace.",
};
export function ViewDescription({ view }: { view: string }) {
  return <p className="view-description">{descriptions[view]}</p>;
}

export default function IntelligencePanel({
  view,
  repository,
  request,
}: Props) {
  if (view === "activity") return <ActivityView request={request} />;
  if (["account", "integrations", "preferences"].includes(view))
    return <SettingsView view={view} request={request} />;
  if (view === "documentation") return <DocumentationView />;
  if (!repository)
    return (
      <EmptyState
        title="Your repository is the starting point"
        action={
          <a className="primary-compact" href="#repository-analysis">
            Connect a repository <ArrowUpRight size={15} />
          </a>
        }
      >
        Connect a GitHub repository to unlock contextual roadmaps, workflow
        generation, deployment plans and engineering insights.
      </EmptyState>
    );
  if (view === "architect" || view === "guides")
    return (
      <div className={view === "architect" ? "architect-grid" : ""}>
        <RoadmapView repository={repository} request={request} />
        {view === "architect" && (
          <ChatView repository={repository} request={request} />
        )}
      </div>
    );
  if (view === "workflows")
    return <BuilderView repository={repository} request={request} />;
  if (view === "deployments")
    return <DeploymentView repository={repository} request={request} />;
  return (
    <EngineeringView view={view} repository={repository} request={request} />
  );
}
