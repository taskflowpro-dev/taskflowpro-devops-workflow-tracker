export type RepositorySuggestion = {
  title: string;
  description: string;
  priority: "LOW" | "MEDIUM" | "HIGH";
  category: string;
  source?: string;
};

export type RepositoryIssue = {
  number: number;
  title: string;
  url: string;
  state?: string;
  createdAt?: string;
  updatedAt?: string;
  labels: string[];
};

export type RepositoryCheck = {
  id: string;
  title: string;
  status: "PRESENT" | "MISSING";
  detail: string;
  evidence?: string;
  priority?: "LOW" | "MEDIUM" | "HIGH";
  recommendation?: string;
};

export type RepositoryCommit = {
  sha: string;
  message: string;
  author: string;
  date: string;
  url: string;
};
export type RepositoryContributor = {
  login: string;
  contributions: number;
  url: string;
};
export type RepositoryRun = {
  name: string;
  status: string;
  conclusion: string;
  branch: string;
  url: string;
  updatedAt: string;
};

export type RepositoryAnalysis = {
  schemaVersion?: number;
  fullName: string;
  url: string;
  description: string;
  language: string;
  defaultBranch: string;
  stars: number;
  forks: number;
  watchers?: number;
  openIssues: number;
  updatedAt: string;
  createdAt?: string;
  sizeKb?: number;
  license?: string;
  archived?: boolean;
  fork?: boolean;
  topics: string[];
  readme?: {
    found: boolean;
    title: string;
    excerpt: string;
    sections: string[];
  };
  languages?: { name: string; bytes: number; percentage: number }[];
  techStack?: string[];
  buildProfiles?: {
    directory: string;
    manifest: string;
    ecosystem: string;
    framework: string;
    runtime: string;
    runtimeEstimated?: boolean;
    scripts: string[];
    inspected: boolean;
  }[];
  configurationEvidence?: {
    path: string;
    kind: string;
    summary: string;
    inspected: boolean;
  }[];
  structure?: {
    rootFiles: string[];
    topDirectories: string[];
    directoryCount: number;
    fileCount: number;
    extensions: { extension: string; count: number }[];
    treeTruncated: boolean;
  };
  sourceScan?: {
    available: boolean;
    filesScanned: number;
    sourceBytesScanned: number;
    sourceLines: number;
    testFiles: number;
    todoMarkers: number;
    fixmeMarkers: number;
    hackMarkers: number;
    manifestFiles: string[];
    dependencySamples: string[];
    truncated?: boolean;
    message?: string;
  };
  activity?: {
    recentCommits: RepositoryCommit[];
    contributors: RepositoryContributor[];
    latestRelease:
      | { name: string; tag: string; publishedAt: string; url: string }
      | Record<string, never>;
    workflowRuns: RepositoryRun[];
    latestRun?: RepositoryRun | Record<string, never>;
    commitCount: number;
    contributorCount: number;
    lastCommitAt: string;
  };
  workflows: string[];
  checks: RepositoryCheck[];
  suggestions: RepositorySuggestion[];
  issues: RepositoryIssue[];
  pullRequests?: {
    number: number;
    title: string;
    url: string;
    draft: boolean;
    createdAt: string;
    updatedAt: string;
    user: string;
  }[];
  latestRun?: RepositoryRun | null;
  score?: {
    value: number;
    label: string;
    present: number;
    total: number;
    explanation: string;
  };
  coverage?: {
    files: number;
    directories: number;
    treeTruncated: boolean;
    inspectedWorkflowFiles: string[];
    readmeFound: boolean;
    sourceArchiveScanned?: boolean;
    note: string;
  };
  summary: string;
  analyzedAt?: string;
};

export type TrackedRepository = {
  id: number;
  url: string;
  fullName: string;
  description: string;
  language: string;
  analyzedAt: string;
  projectId: number | null;
  projectName: string | null;
  analysis: RepositoryAnalysis;
};

export type ProjectSummary = {
  id: number;
  name: string;
  description: string;
  taskCount: number;
  createdAt: string;
  repositoryCount?: number;
  repositoryName?: string | null;
  repositoryUrl?: string | null;
};
