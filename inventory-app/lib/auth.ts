const TOKEN_KEY = "erp_token";
const USER_KEY  = "erp_user";

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  role: "SUPER_ADMIN" | "ADMIN" | "MANAGER" | "STAFF";
  tenantId: number;
  tenantName: string;
  tenantSlug: string;
  // Phase I1: what the user\'s role allows (from /auth/me); missing on old stored sessions until reloaded
  permissions?: string[];
}

export const authStorage = {
  getToken: (): string | null =>
    typeof window !== "undefined" ? localStorage.getItem(TOKEN_KEY) : null,

  setToken: (token: string) => localStorage.setItem(TOKEN_KEY, token),

  getUser: (): AuthUser | null => {
    if (typeof window === "undefined") return null;
    const raw = localStorage.getItem(USER_KEY);
    try { return raw ? JSON.parse(raw) : null; } catch { return null; }
  },

  setUser: (user: AuthUser) => localStorage.setItem(USER_KEY, JSON.stringify(user)),

  clear: () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  },
};
