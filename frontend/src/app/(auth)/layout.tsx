import { Activity, GitBranch, Rocket, ShieldCheck } from "lucide-react";
import { Brand } from "@/components/brand";
export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <main className="auth-shell">
      <aside className="auth-story">
        <Brand />
        <div className="auth-story-copy">
          
          <h2>Repository analysis and delivery workflow planning.</h2>
          <p>
            Connect a repository, review how it is built and tested, and get a
            concrete plan for CI, deployment and documentation.
          </p>
          <div className="auth-proof">
            <div>
              <GitBranch size={17} /> Repository analysis based on your actual code
            </div>
            <div>
              <Activity size={17} /> Engineering health and roadmap for your stack
            </div>
            <div>
              <ShieldCheck size={17} /> Private workspaces with team access control
            </div>
            <div>
              <Rocket size={17} /> CI and deployment configuration you can review
            </div>
          </div>
        </div>
      </aside>
      {children}
    </main>
  );
}
