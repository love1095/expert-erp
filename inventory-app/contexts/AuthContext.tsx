"use client";

import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from "react";
import { authStorage, AuthUser } from "@/lib/auth";
import api from "@/lib/apiInstance";

interface AuthContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  /** Phase I1: whether the user's role has this permission (admins have all) */
  can: (permission: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const stored = authStorage.getUser();
    const token  = authStorage.getToken();
    if (stored && token) {
      // Permissions are read fresh on every load: an admin may have changed the user's role
      api.get("/auth/me")
        .then(({ data }) => { const u = { ...stored, permissions: data.permissions ?? [] }; authStorage.setUser(u); setUser(u); })
        .finally(() => setIsLoading(false));
    } else {
      setIsLoading(false);
    }
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const { data } = await api.post("/auth/login", { username, password });
    authStorage.setToken(data.token);
    const u: AuthUser = {
      id: data.userId, username: data.username, email: data.email, role: data.role,
      tenantId: data.tenantId, tenantName: data.tenantName, tenantSlug: data.tenantSlug,
    };
    try { u.permissions = (await api.get("/auth/me")).data.permissions ?? []; } catch { u.permissions = []; }
    authStorage.setUser(u);
    setUser(u);
  }, []);

  const logout = useCallback(() => {
    authStorage.clear();
    setUser(null);
  }, []);

  const can = useCallback((permission: string) =>
    user?.role === "ADMIN" || user?.role === "SUPER_ADMIN" || (user?.permissions ?? []).includes(permission), [user]);

  return (
    <AuthContext.Provider value={{ user, isLoading, login, logout, can }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be inside AuthProvider");
  return ctx;
}
