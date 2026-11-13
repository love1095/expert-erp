"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth, TwoFactorPrompt } from "@/contexts/AuthContext";

export default function LoginPage() {
  const { login, completeTwoFactor, user } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError]       = useState("");
  const [loading, setLoading]   = useState(false);
  // Phase I5: the second step, and the recovery codes to keep after setting the app up
  const [prompt, setPrompt]     = useState<TwoFactorPrompt | null>(null);
  const [code, setCode]         = useState("");
  const [recovery, setRecovery] = useState<string[] | null>(null);

  // Already signed in: home (not while a second step or the recovery codes are on screen)
  useEffect(() => {
    if (user && !prompt && !recovery) router.push("/");
  }, [user, router, prompt, recovery]);

  const errorOf = (err: unknown) => (err as { response?: { data?: { error?: string } } })?.response?.data?.error;
  const submitCode = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const codes = await completeTwoFactor(prompt!, code);
      if (codes) setRecovery(codes); else router.push("/");
    } catch (err) {
      const msg = errorOf(err) || "That code did not work";
      setError(msg);
      setCode("");
      if (msg.startsWith("Sign in again")) setPrompt(null);
    } finally {
      setLoading(false);
    }
  };

  if (recovery) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="w-full max-w-sm bg-white rounded-xl border border-gray-200 shadow-sm p-8 space-y-4" data-testid="recovery-codes">
          <h1 className="text-lg font-bold">Two-step sign-in is on</h1>
          <p className="text-sm text-gray-600">Keep these recovery codes somewhere safe. Each works once, if you lose your phone. They are not shown again.</p>
          <ul className="grid grid-cols-2 gap-2 font-mono text-sm bg-gray-50 rounded p-3">{recovery.map(c => <li key={c}>{c}</li>)}</ul>
          <button type="button" onClick={() => router.push("/")}
            className="w-full bg-brand-600 text-white py-2 rounded-md text-sm font-medium hover:bg-brand-700">I have kept them</button>
        </div>
      </div>
    );
  }

  if (prompt) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <form data-confirm="none" onSubmit={submitCode} className="w-full max-w-sm bg-white rounded-xl border border-gray-200 shadow-sm p-8 space-y-4" data-testid="two-factor">
          <h1 className="text-lg font-bold">{prompt.step === "SETUP" ? "Set up two-step sign-in" : "Two-step sign-in"}</h1>
          {prompt.step === "SETUP" ? (
            <div className="text-sm text-gray-600 space-y-2">
              <p>Your organisation asks everyone to sign in with a code from an authenticator app (Google or Microsoft Authenticator, 1Password…).</p>
              <p>In the app choose <b>Enter a setup key</b> and type this key, or on your phone <a href={prompt.otpauthUri} className="text-brand-600 underline">open it in the app</a>:</p>
              <p className="font-mono text-xs bg-gray-50 rounded p-2 break-all select-all" data-testid="setup-key">{prompt.secret}</p>
              <p>Then type the 6-digit code it shows.</p>
            </div>
          ) : <p className="text-sm text-gray-600">Type the 6-digit code from your authenticator app, or one of your recovery codes.</p>}
          {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}
          <div>
            <label htmlFor="login-code" className="block text-xs font-medium text-gray-600 mb-1">Code</label>
            <input id="login-code" value={code} onChange={e => setCode(e.target.value)} required autoFocus autoComplete="one-time-code"
              inputMode={prompt.step === "SETUP" ? "numeric" : "text"} maxLength={9}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-lg tracking-widest font-mono focus:outline-none focus:ring-2 focus:ring-brand-400" />
          </div>
          <button type="submit" disabled={loading} className="w-full bg-brand-600 text-white py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {loading ? "Checking…" : "Continue"}
          </button>
          <button type="button" onClick={() => { setPrompt(null); setCode(""); setError(""); }} className="w-full text-sm text-gray-500 hover:underline">Back</button>
        </form>
      </div>
    );
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const next = await login(username, password);
      if (next) { setPrompt(next); setPassword(""); } else router.push("/");
    } catch (err) {
      setError(errorOf(err) || "Login failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-brand-600">Expert ERP</h1>
          <p className="text-gray-400 text-sm mt-1">Sign in to continue</p>
        </div>

        <form data-confirm="none" onSubmit={handleSubmit} className="bg-white rounded-xl border border-gray-200 shadow-sm p-8 space-y-5">
          {error && (
            <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>
          )}

          <div>
            <label htmlFor="login-username" className="block text-xs font-medium text-gray-600 mb-1">Username</label>
            <input id="login-username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              required
              autoFocus
              placeholder="admin"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400"
            />
          </div>

          <div>
            <label htmlFor="login-password" className="block text-xs font-medium text-gray-600 mb-1">Password</label>
            <input id="login-password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
              placeholder="••••••••"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-brand-600 text-white py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50"
          >
            {loading ? "Signing in..." : "Sign in"}
          </button>
        </form>

        <div className="text-center mt-4 space-y-1">
          <p className="text-xs text-gray-400">Default: expertsafety / admin123</p>
          <p className="text-xs text-gray-400">
            New org?{" "}
            <a href="/register" className="text-brand-500 hover:underline">Create organization</a>
          </p>
        </div>
      </div>
    </div>
  );
}
