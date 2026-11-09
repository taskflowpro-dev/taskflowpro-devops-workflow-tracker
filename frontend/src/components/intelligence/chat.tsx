"use client";
import { useEffect, useState, type FormEvent } from "react";
import {
  ArrowUpRight,
  MessageSquare,
  Plus,
  Send,
  Terminal,
} from "lucide-react";
import { Badge } from "@/components/platform-ui";
import type { TrackedRepository } from "@/lib/repository";
import type {
  Conversation,
  IntegrationStatus,
  Message,
} from "@/lib/intelligence";
import { type Request, message } from "./shared";

export default function ChatView({
  repository,
  request,
}: {
  repository: TrackedRepository;
  request: Request;
}) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [provider, setProvider] = useState<IntegrationStatus | null>(null);
  const [content, setContent] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const base = `/api/intelligence/repositories/${repository.id}/conversations`;
  useEffect(() => {
    let live = true;
    Promise.all([
      request<Conversation[]>(base),
      request<IntegrationStatus>("/api/intelligence/integrations"),
    ])
      .then(([items, status]) => {
        if (live) {
          setConversations(items);
          setProvider(status);
          setSelected(items[0]?.id ?? null);
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [base, request]);
  useEffect(() => {
    if (!selected) {
      setMessages([]);
      return;
    }
    let live = true;
    setLoading(true);
    setMessages([]);
    request<Message[]>(`${base}/${selected}/messages`)
      .then((items) => {
        if (live) setMessages(items);
      })
      .catch((e) => {
        if (live) setError(e.message);
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [base, selected, request]);
  async function send(event: FormEvent) {
    event.preventDefault();
    if (!content.trim() || loading) return;
    setBusy(true);
    setError("");
    try {
      let id = selected;
      if (!id) {
        const conversation = await request<Conversation>(base, {
          method: "POST",
          body: JSON.stringify({ title: content.slice(0, 120) }),
        });
        id = conversation.id;
        setConversations((current) => [conversation, ...current]);
      }
      const next = await request<Message[]>(`${base}/${id}/messages`, {
        method: "POST",
        body: JSON.stringify({ content }),
      });
      setSelected(id);
      setMessages(next);
      setContent("");
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel chat-panel">
      <div className="panel-heading">
        <div>
          <span className="section-kicker">
            <MessageSquare size={14} /> Repository assistant
          </span>
          <h2>Ask about this repository</h2>
        </div>
        <button
          className="icon-button"
          aria-label="New conversation"
          disabled={busy || loading}
          onClick={() => {
            setSelected(null);
            setMessages([]);
            setError("");
          }}
        >
          <Plus size={18} />
        </button>
      </div>
      <div className="chat-context">
        <span className="live-dot" />
        <span>
          {provider?.ai.configured
            ? `Ollama · ${provider.ai.model}`
            : "Evidence guidance · rules-based"}
        </span>
        <Badge>Read only</Badge>
      </div>
      {conversations.length > 0 && (
        <select
          aria-label="Conversation history"
          value={selected ?? ""}
          disabled={busy}
          onChange={(e) => setSelected(Number(e.target.value) || null)}
        >
          <option value="">New conversation</option>
          {conversations.map((item) => (
            <option value={item.id} key={item.id}>
              {item.title}
            </option>
          ))}
        </select>
      )}
      <div className="chat-messages" aria-live="polite">
        {loading ? (
          <p role="status">Loading conversation…</p>
        ) : messages.length ? (
          messages.map((item) => (
            <article className={`chat-message ${item.role}`} key={item.id}>
              <span>
                {item.role === "user"
                  ? "You"
                  : item.source.startsWith("ollama:")
                    ? "AI DevOps Architect"
                    : "Repository guidance"}
              </span>
              <pre>{item.content}</pre>
            </article>
          ))
        ) : (
          <div className="chat-starter">
            <span className="assistant-symbol">
              <Terminal size={24} />
            </span>
            <h3>Let’s build with context.</h3>
            <p>
              Ask about the connected repository. Conversations are saved in
              this workspace.
            </p>
            {[
              "How should I deploy this project?",
              "What DevOps practices are missing?",
              "Why is my workflow failing?",
            ].map((prompt) => (
              <button
                key={prompt}
                disabled={busy}
                onClick={() => setContent(prompt)}
              >
                {prompt}
                <ArrowUpRight size={14} />
              </button>
            ))}
          </div>
        )}
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <form className="chat-composer" onSubmit={send}>
        <textarea
          aria-label="Ask the repository assistant"
          value={content}
          onChange={(e) => setContent(e.target.value)}
          maxLength={4000}
          placeholder="Ask about your repository…"
          rows={3}
        />
        <button
          className="primary-compact"
          disabled={busy || loading || !content.trim()}
          aria-label="Send message"
        >
          {busy ? "Thinking…" : <Send size={16} />}
        </button>
      </form>
      <p className="form-hint">
        Don’t share secret values. Answers are advisory; review generated
        commands.{" "}
        {provider &&
          !provider.ai.configured &&
          "Configure a server-side Ollama model in Integrations to enable AI responses."}
      </p>
    </section>
  );
}
