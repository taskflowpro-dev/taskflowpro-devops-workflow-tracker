"use client";
import {
  ArrowUpRight,
  GitBranch,
  Rocket,
  Terminal,
  Workflow,
} from "lucide-react";

export default function DocumentationView() {
  return (
    <div className="documentation-grid">
      {[
        {
          title: "Understand your repository",
          text: "Connect a GitHub URL in Repository Analyzer. Review the saved evidence, inspected modules, analysis time and scan limitations before acting.",
          href: "#repository-analysis",
          action: "Open analyzer",
          icon: <GitBranch size={24} />,
        },
        {
          title: "Build your engineering roadmap",
          text: "Generate recommendations in AI DevOps Architect. Work through the implementation guides; checklist completion is saved separately from detected evidence.",
          href: "#architect",
          action: "Open architect",
          icon: <Terminal size={24} />,
        },
        {
          title: "Review and apply configurations",
          text: "Generate a new workflow draft, inspect the YAML and validation warnings, then download it. Compare with existing workflows and apply changes on a reviewed branch.",
          href: "#workflows",
          action: "Open builder",
          icon: <Workflow size={24} />,
        },
        {
          title: "Prepare for production",
          text: "Create a deployment plan, map the inspected modules to services, review the Compose draft, configure server-side secrets, and verify release health in your target environment.",
          href: "#deployments",
          action: "Plan deployment",
          icon: <Rocket size={24} />,
        },
      ].map((item) => (
        <article className="panel documentation-card" key={item.title}>
          <span className="stat-icon violet">{item.icon}</span>
          <h2>{item.title}</h2>
          <p>{item.text}</p>
          <a href={item.href}>
            {item.action}
            <ArrowUpRight size={15} />
          </a>
        </article>
      ))}
      <article className="panel documentation-limits">
        <h2>What the platform can verify</h2>
        <p>
          Reports describe bounded, saved GitHub evidence. Test coverage,
          vulnerabilities, branch protection and production availability remain
          unknown unless those checks run externally. Generated drafts and model
          answers require human review. No GitHub files are overwritten and no
          deployments are executed.
        </p>
      </article>
    </div>
  );
}
