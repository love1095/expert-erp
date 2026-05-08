"use client";

import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from "react";
import { authStorage, AuthUser } from "@/lib/auth";
import api from "@/lib/apiInstance";

interface AuthContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const stored = authStorage.getUser();
    const token  = authStorage.getToken();
    if (stored && token) {
      api.get("/auth/me")
        .then(() => setUser(stored))
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
    authStorage.setUser(u);
    setUser(u);
  }, []);

  const logout = useCallback(() => {
    authStorage.clear();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, isLoading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be inside AuthProvider");
  return ctx;
}
