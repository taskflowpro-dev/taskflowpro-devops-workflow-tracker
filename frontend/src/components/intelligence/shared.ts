import type { TrackedRepository } from "@/lib/repository";
export type Request = <T>(path: string, init?: RequestInit) => Promise<T>;
export type Props = {
  view: string;
  repository: TrackedRepository | null;
  request: Request;
};
export function message(error: unknown) {
  return error instanceof Error
    ? error.message
    : "The request could not be completed.";
}
