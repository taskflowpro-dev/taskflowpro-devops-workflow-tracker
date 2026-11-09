export type ImplementationStep = {
  id: number;
  title: string;
  instructions: string;
  filePath: string;
  command: string;
  validation: string;
  troubleshooting: string;
  completed: boolean;
};
export type Recommendation = {
  id: number;
  signalKey: string;
  title: string;
  explanation: string;
  evidence: string;
  priority: string;
  complexity: string;
  observedStatus: string;
  analyzedAt: string;
  status: string;
  steps: ImplementationStep[];
};
export type Validation = {
  valid: boolean;
  errors: string[];
  warnings: string[];
  executed: boolean;
};
export type Configuration = {
  id: number;
  kind: string;
  filePath: string;
  content: string;
  createdAt: string;
  metadata: {
    notes: string[];
    requiredSecrets: string[];
    validation: Validation;
    analysisTimestamp?: string;
  };
};
export type Environment = {
  id: number;
  name: string;
  target: string;
  status: "PLANNED" | "REVIEWED";
  plan: {
    title: string;
    rationale: string;
    modules: { directory: string; framework: string }[];
    steps: { title: string; instructions: string; command: string }[];
    verified: boolean;
    limitations: string[];
    links: string[];
  };
};
export type Conversation = { id: number; title: string };
export type Message = {
  id: number;
  role: "user" | "assistant";
  content: string;
  source: string;
  createdAt: string;
};
export type Activity = {
  id: number;
  action: string;
  detail: string;
  actor: string;
  createdAt: string;
};
export type Preferences = { compactMode: boolean; reduceMotion: boolean };
export type IntegrationStatus = {
  ai: {
    provider: string;
    configured: boolean;
    model: string;
    executesTools: boolean;
  };
  github: { mode: string; writesEnabled: boolean };
  deployments: { executionEnabled: boolean };
};
