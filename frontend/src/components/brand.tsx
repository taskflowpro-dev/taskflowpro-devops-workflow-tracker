import {
  Activity,
  ArrowUpRight,
  BookOpen,
  Boxes,
  CheckCheck,
  ChevronRight,
  CircleUserRound,
  FileCode2,
  FolderKanban,
  GitBranch,
  GitPullRequest,
  LayoutDashboard,
  LogOut,
  Network,
  Package,
  Plug,
  Rocket,
  Settings2,
  ShieldCheck,
  Terminal,
  Users,
  Workflow,
} from "lucide-react";

export function Brand({ light = false }: { light?: boolean }) {
  return (
    <div className={`brand ${light ? "brand-light" : ""}`}>
      <span className="brand-mark">
        <Workflow size={18} strokeWidth={2.25} />
      </span>
      <span className="brand-name">
        TaskFlow<span className="brand-pro">Pro</span>
      </span>
    </div>
  );
}
const icons = {
  grid: LayoutDashboard,
  overview: LayoutDashboard,
  check: CheckCheck,
  folder: FolderKanban,
  users: Users,
  settings: Settings2,
  logout: LogOut,
  arrow: ArrowUpRight,
  spark: Terminal,
  repository: GitBranch,
  workflow: Workflow,
  pipeline: Network,
  rocket: Rocket,
  health: Activity,
  shield: ShieldCheck,
  package: Package,
  pull: GitPullRequest,
  book: BookOpen,
  file: FileCode2,
  activity: Activity,
  user: CircleUserRound,
  plug: Plug,
  boxes: Boxes,
  chevron: ChevronRight,
};
export type IconName = keyof typeof icons;
export function Icon({ name }: { name: IconName }) {
  const Component = icons[name];
  return (
    <Component
      className="icon"
      size={18}
      strokeWidth={1.7}
      aria-hidden="true"
    />
  );
}
