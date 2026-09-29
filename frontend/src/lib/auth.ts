export type User = { id: number; name: string; email: string };
export type AuthResponse = { token: string; tokenType: string; expiresIn: number; user: User };

const TOKEN_KEY = "taskflow.access-token";
const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

export function getToken() {
  return typeof window === "undefined" ? null : window.localStorage.getItem(TOKEN_KEY);
}

export function saveToken(token: string) {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  window.localStorage.removeItem(TOKEN_KEY);
}

export async function api<T>(path: string, init: RequestInit = {}, authenticated = false): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (authenticated) {
    const token = getToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_URL}${path}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && authenticated) clearToken();
    throw new Error(body.message ?? "Something went wrong. Please try again.");
  }
  return body as T;
}
