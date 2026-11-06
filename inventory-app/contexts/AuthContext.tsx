"use client";

import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from "react";
import { authStorage, AuthUser } from "@/lib/auth";
import api from "@/lib/apiInstance";

/** Phase I5: after a right password, a code from the authenticator app comes next (or setting the app up first). */
export interface TwoFactorPrompt { step: "CODE" | "SETUP"; challenge: string; secret?: string; otpauthUri?: string }

interface AuthContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  /** Signs in; returns the second step to do when two-step sign-in is on, else null. */
  login: (username: string, password: string) => Promise<TwoFactorPrompt | null>;
  /** Second step: the code (or a recovery code); after setting up, returns the recovery codes to keep. */
  completeTwoFactor: (prompt: TwoFactorPrompt, code: string) => Promise<string[] | null>;
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

  // The session from a login response: token, user and permissions
  const start = useCallback(async (data: {
    token: string; userId: number; username: string; email: string; role: AuthUser["role"]; tenantId: number; tenantName: string; tenantSlug: string;
  }) => {
    authStorage.setToken(data.token);
    const u: AuthUser = {
      id: data.userId, username: data.username, email: data.email, role: data.role,
      tenantId: data.tenantId, tenantName: data.tenantName, tenantSlug: data.tenantSlug,
    };
    try { u.permissions = (await api.get("/auth/me")).data.permissions ?? []; } catch { u.permissions = []; }
    authStorage.setUser(u);
    setUser(u);
  }, []);

  const login = useCallback(async (username: string, password: string): Promise<TwoFactorPrompt | null> => {
    const { data } = await api.post("/auth/login", { username, password });
    if (data.twoFactor) return { step: data.twoFactor, challenge: data.challenge, secret: data.secret, otpauthUri: data.otpauthUri };
    await start(data);
    return null;
  }, [start]);

  const completeTwoFactor = useCallback(async (prompt: TwoFactorPrompt, code: string): Promise<string[] | null> => {
    if (prompt.step === "SETUP") {
      const { data } = await api.post("/auth/login/two-factor/setup", { challenge: prompt.challenge, code });
      await start(data.session);
      return data.recoveryCodes;
    }
    const { data } = await api.post("/auth/login/two-factor", { challenge: prompt.challenge, code });
    await start(data);
    return null;
  }, [start]);

  const logout = useCallback(() => {
    authStorage.clear();
    setUser(null);
  }, []);

  const can = useCallback((permission: string) =>
    user?.role === "ADMIN" || user?.role === "SUPER_ADMIN" || (user?.permissions ?? []).includes(permission), [user]);

  return (
    <AuthContext.Provider value={{ user, isLoading, login, completeTwoFactor, logout, can }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be inside AuthProvider");
  return ctx;
}
