import axios from "axios";
import { authStorage } from "@/lib/auth";

const api = axios.create({
  baseURL: "/api",
  headers: { "Content-Type": "application/json" },
});

api.interceptors.request.use(config => {
  const token = authStorage.getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  res => res,
  err => {
    // A 401 from the login call means wrong credentials, not an expired session: let the form show the error
    const isLoginCall = err.config?.url === "/auth/login";
    if (err.response?.status === 401 && !isLoginCall && typeof window !== "undefined") {
      authStorage.clear();
      window.location.href = "/login";
    }
    if (err.response?.status === 402 && typeof window !== "undefined") {
      const data = err.response.data;
      const msg  = data?.error ?? "Plan limit reached. Upgrade your plan to continue.";
      window.dispatchEvent(new CustomEvent("plan-limit-exceeded", { detail: { message: msg, ...data } }));
    }
    return Promise.reject(err);
  }
);

export default api;
