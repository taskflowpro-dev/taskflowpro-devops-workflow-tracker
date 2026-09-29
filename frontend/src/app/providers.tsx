"use client";

import { createContext, useContext, useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { api, clearToken, getToken, type User } from "@/lib/auth";

type AuthContextValue = { user: User | null; loading: boolean; logout: () => void; refresh: () => Promise<void> };
const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();
  const pathname = usePathname();
  const publicRoute = pathname === "/login" || pathname === "/signup";

  async function refresh() {
    const token = getToken();
    if (!token) { setUser(null); return; }
    try { setUser(await api<User>("/api/users/me", {}, true)); }
    catch { clearToken(); setUser(null); }
  }

  useEffect(() => {
    refresh().finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (loading) return;
    if (!user && !publicRoute) router.replace("/login");
    if (user && publicRoute) router.replace("/dashboard");
  }, [loading, user, publicRoute, router]);

  function logout() { clearToken(); setUser(null); router.replace("/login"); }

  return <AuthContext.Provider value={{ user, loading, logout, refresh }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
