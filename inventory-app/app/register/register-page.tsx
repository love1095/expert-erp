"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/contexts/AuthContext";
import api from "@/lib/apiInstance";

export default function RegisterPage() {
  const { login } = useAuth();
  const router    = useRouter();

  const [form, setForm] = useState({
    organizationName: "", username: "", email: "", password: "", confirm: "",
  });
  const [error,   setError]   = useState("");
  const [loading, setLoading] = useState(false);

  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (form.password !== form.confirm) { setError("Passwords do not match"); return; }
    setError(""); setLoading(true);
    try {
      await api.post("/auth/register", {
        organizationName: form.organizationName,
        username: form.username,
        email: form.email,
        password: form.password,
      });
      // Auto-login after registration
      await login(form.username, form.password);
      router.push("/");
    } catch (err: any) {
      setError(err.response?.data?.error || "Registration failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-indigo-600">Expert ERP</h1>
          <p className="text-gray-400 text-sm mt-1">Create a new organization</p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-gray-200 shadow-sm p-8 space-y-4">
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          <div>
            <label htmlFor="register-organization-name" className="block text-xs font-medium text-gray-600 mb-1">Organization name *</label>
            <input id="register-organization-name" value={form.organizationName} onChange={e => set("organizationName", e.target.value)}
              required placeholder="Acme Safety Ltd"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="register-username" className="block text-xs font-medium text-gray-600 mb-1">Username *</label>
              <input id="register-username" value={form.username} onChange={e => set("username", e.target.value)}
                required placeholder="admin"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
            </div>
            <div>
              <label htmlFor="register-email" className="block text-xs font-medium text-gray-600 mb-1">Email</label>
              <input id="register-email" type="email" value={form.email} onChange={e => set("email", e.target.value)}
                placeholder="you@company.com"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
            </div>
          </div>

          <div>
            <label htmlFor="register-password" className="block text-xs font-medium text-gray-600 mb-1">Password *</label>
            <input id="register-password" type="password" value={form.password} onChange={e => set("password", e.target.value)}
              required placeholder="••••••••" minLength={6}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>

          <div>
            <label htmlFor="register-confirm-password" className="block text-xs font-medium text-gray-600 mb-1">Confirm password *</label>
            <input id="register-confirm-password" type="password" value={form.confirm} onChange={e => set("confirm", e.target.value)}
              required placeholder="••••••••"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>

          <button type="submit" disabled={loading}
            className="w-full bg-indigo-600 text-white py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
            {loading ? "Creating..." : "Create organization"}
          </button>
        </form>

        <p className="text-center text-xs text-gray-400 mt-4">
          Already have an account?{" "}
          <a href="/login" className="text-indigo-500 hover:underline">Sign in</a>
        </p>
      </div>
    </div>
  );
}
