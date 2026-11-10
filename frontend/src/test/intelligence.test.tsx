import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import IntelligencePanel, {
  RoadmapView,
} from "@/components/intelligence-panel";
import { CodeBlock } from "@/components/platform-ui";
import { viewFromHash } from "@/lib/navigation";
import type { TrackedRepository } from "@/lib/repository";
vi.mock("@/app/providers", () => ({
  useAuth: () => ({
    user: { id: 1, name: "Alice", email: "alice@example.test" },
    refresh: vi.fn(),
  }),
}));
const repository = {
  id: 7,
  fullName: "example/web",
  url: "https://github.com/example/web",
  analyzedAt: "2026-11-01T10:00:00Z",
  analysis: {
    fullName: "example/web",
    workflows: ["existing.yml"],
    buildProfiles: [
      {
        directory: "frontend",
        manifest: "frontend/package.json",
        ecosystem: "node",
        framework: "Next.js",
        runtime: "22",
        inspected: true,
        scripts: ["build"],
      },
    ],
  },
} as TrackedRepository;
const step = {
  id: 31,
  title: "Review the existing pipeline",
  instructions: "Inspect configuration before applying a draft.",
  filePath: ".github/workflows/taskflow-ci.yml",
  command: "actionlint",
  validation: "Inspect real run results.",
  troubleshooting: "Check permissions.",
  completed: false,
};
const recommendation = {
  id: 12,
  signalKey: "ci",
  title: "Add continuous integration",
  explanation: "Missing CI evidence",
  evidence: "No matching workflow",
  priority: "HIGH",
  complexity: "MODERATE",
  observedStatus: "MISSING",
  analyzedAt: "2026-11-01T10:00:00Z",
  status: "TODO",
  steps: [step],
};
type Request = <T>(path: string, init?: RequestInit) => Promise<T>;
describe("Engineering interactions", () => {
  it("does not invent a selected view from an unknown hash", () => {
    expect(viewFromHash("#workflows")).toBe("workflows");
    expect(viewFromHash("#unexpected")).toBe("overview");
  });
  it("copies configuration content and confirms success", async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator.clipboard, "writeText", {
      value: writeText,
      configurable: true,
    });
    render(<CodeBlock path="pipeline.yml" content="permissions: read-all" />);
    await user.click(screen.getByRole("button", { name: "Copy pipeline.yml" }));
    expect(writeText).toHaveBeenCalledWith("permissions: read-all");
    expect(screen.getByText("Copied")).toBeInTheDocument();
  });
  it("reports clipboard failure instead of displaying a false success", async () => {
    const user = userEvent.setup();
    Object.defineProperty(navigator.clipboard, "writeText", {
      value: vi.fn().mockRejectedValue(new Error()),
      configurable: true,
    });
    render(<CodeBlock content="docker compose config" />);
    await user.click(screen.getByRole("button", { name: "Copy command" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Clipboard unavailable",
    );
  });
  it("loads saved progress and persists a checklist update through the API", async () => {
    const user = userEvent.setup();
    let completed = false;
    const request = vi.fn(async (_path: string, init?: RequestInit) => {
      if (init?.method === "PATCH") {
        completed = true;
        return {};
      }
      return [{ ...recommendation, steps: [{ ...step, completed }] }];
    });
    render(
      <RoadmapView repository={repository} request={request as Request} />,
    );
    await screen.findByText("Add continuous integration");
    await user.click(screen.getByText("Add continuous integration"));
    await user.click(screen.getByRole("checkbox", { name: step.title }));
    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: step.title })).toBeChecked(),
    );
    expect(request).toHaveBeenCalledWith(
      "/api/intelligence/recommendations/12/steps/31",
      { method: "PATCH", body: '{"completed":true}' },
    );
  });
  it("keeps a failed checklist change incomplete and exposes the error", async () => {
    const user = userEvent.setup();
    const request = vi.fn(async (_path: string, init?: RequestInit) => {
      if (init) throw new Error("Access was revoked.");
      return [recommendation];
    });
    render(
      <RoadmapView repository={repository} request={request as Request} />,
    );
    await user.click(await screen.findByText(recommendation.title));
    await user.click(screen.getByRole("checkbox", { name: step.title }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Access was revoked",
    );
    expect(
      screen.getByRole("checkbox", { name: step.title }),
    ).not.toBeChecked();
  });
  it("generates a real saved draft while retaining the existing-workflow warning", async () => {
    const user = userEvent.setup();
    const configuration = {
      id: 9,
      kind: "workflow",
      filePath: ".github/workflows/taskflow-ci.yml",
      content: "name: Reviewed draft",
      metadata: {
        notes: ["Review runtime versions."],
        requiredSecrets: [],
        validation: {
          valid: true,
          errors: [],
          warnings: ["Static only"],
          executed: false,
        },
      },
    };
    const request = vi.fn(async (_path: string, init?: RequestInit) =>
      init?.method === "POST" ? configuration : [],
    );
    render(
      <IntelligencePanel
        view="workflows"
        repository={repository}
        request={request as Request}
      />,
    );
    expect(screen.getByText(/existing.yml/)).toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: /Generate configuration/ }),
    );
    expect(await screen.findByText(configuration.content)).toBeInTheDocument();
    expect(screen.getByText("Static checks passed")).toBeInTheDocument();
    const call = request.mock.calls.find((call) => call[1]?.method === "POST");
    expect(JSON.parse(call?.[1]?.body as string)).toEqual({
      kind: "workflow",
      tests: true,
      lint: true,
      security: true,
      container: false,
      publish: false,
    });
  });
  it("shows an actionable repository prerequisite instead of fake analytics", () => {
    render(
      <IntelligencePanel
        view="code-health"
        repository={null}
        request={vi.fn() as Request}
      />,
    );
    expect(
      screen.getByRole("link", { name: /Connect a repository/ }),
    ).toHaveAttribute("href", "#repository-analysis");
    expect(screen.queryByText("100%")).not.toBeInTheDocument();
  });
  it("applies confirmed preference values to the document", async () => {
    const user = userEvent.setup();
    const request = vi.fn(async (path: string, init?: RequestInit) => {
      if (path.endsWith("/integrations")) return {};
      if (init?.method === "PUT") return JSON.parse(init.body as string);
      return { compactMode: false, reduceMotion: false };
    });
    render(
      <IntelligencePanel
        view="preferences"
        repository={null}
        request={request as Request}
      />,
    );
    await user.click(
      await screen.findByRole("switch", { name: /Compact spacing/ }),
    );
    await screen.findByText("Preferences saved to your account.");
    expect(document.documentElement).toHaveAttribute("data-compact", "true");
    expect(document.documentElement).toHaveAttribute(
      "data-reduce-motion",
      "false",
    );
    document.documentElement.removeAttribute("data-compact");
    document.documentElement.removeAttribute("data-reduce-motion");
  });
});
