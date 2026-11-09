"use client";
import { useState } from "react";
import { Check, Copy, Download, GitBranch } from "lucide-react";

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <div className="platform-empty">
      <span className="empty-symbol">
        <GitBranch size={25} />
      </span>
      <h3>{title}</h3>
      <p>{children}</p>
      {action}
    </div>
  );
}
export function Badge({
  children,
  tone = "neutral",
}: {
  children: React.ReactNode;
  tone?: string;
}) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}
export function CodeBlock({
  content,
  path,
  downloadable = false,
}: {
  content: string;
  path?: string;
  downloadable?: boolean;
}) {
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState("");
  async function copy() {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setError("");
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setError("Clipboard unavailable. Select and copy the text below.");
    }
  }
  function download() {
    const url = URL.createObjectURL(
      new Blob([content], { type: "text/plain;charset=utf-8" }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = path?.split("/").at(-1) ?? "configuration.txt";
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <div className="code-block">
      <div className="code-toolbar">
        <code>{path ?? "Terminal"}</code>
        <div>
          <button
            className="code-action"
            onClick={() => void copy()}
            aria-label={`Copy ${path ?? "command"}`}
          >
            {copied ? <Check size={14} /> : <Copy size={14} />}{" "}
            {copied ? "Copied" : "Copy"}
          </button>
          {downloadable && (
            <button className="code-action" onClick={download}>
              <Download size={14} /> Download
            </button>
          )}
        </div>
      </div>
      {error && (
        <p className="copy-error" role="alert">
          {error}
        </p>
      )}
      <pre tabIndex={0}>
        <code>{content}</code>
      </pre>
    </div>
  );
}
