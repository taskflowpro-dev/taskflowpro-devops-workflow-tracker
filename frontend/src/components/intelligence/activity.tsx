"use client";
import { useEffect, useState } from "react";
import { Clock3 } from "lucide-react";
import { EmptyState } from "@/components/platform-ui";
import type { Activity } from "@/lib/intelligence";
import { type Request } from "./shared";

export default function ActivityView({ request }: { request: Request }) {
  const [items, setItems] = useState<Activity[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let live = true;
    request<Activity[]>("/api/intelligence/activity")
      .then((data) => {
        if (live) setItems(data);
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
  }, [request]);
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Workspace activity</h2>
          <p>
            Latest 100 intelligence actions. Existing task and release records
            remain in their dedicated views.
          </p>
        </div>
        <Clock3 size={20} />
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {loading ? (
        <p role="status">Loading activity…</p>
      ) : items.length ? (
        <div className="activity-timeline">
          {items.map((item) => (
            <article key={item.id}>
              <span className="timeline-dot" />
              <div>
                <strong>{item.action}</strong>
                <p>{item.detail}</p>
                <small>
                  {item.actor} · {new Date(item.createdAt).toLocaleString()}
                </small>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState title="Your engineering story starts here">
          Generated drafts, roadmap progress and conversations will appear as
          you work.
        </EmptyState>
      )}
    </section>
  );
}
