"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { confirmTwoFactor, disableTwoFactor, getTwoFactorStatus, newRecoveryCodes, startTwoFactor } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";

interface Status { enabled: boolean; required: boolean; recoveryCodesLeft: number }

function errorText(e: unknown, fallback: string) {
  return (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback;
}

/** Phase I5: the signed-in user's own two-step sign-in (Cin7: User profile → two-factor authentication). */
export default function AccountPage() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const { data: status } = useQuery<Status>({ queryKey: ["two-factor"], queryFn: getTwoFactorStatus });
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[] | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const refresh = () => qc.invalidateQueries({ queryKey: ["two-factor"] });
  const fail = (fallback: string) => (e: unknown) => { setMessage({ ok: false, text: errorText(e, fallback) }); setCode(""); };
  const start = useMutation({ mutationFn: startTwoFactor, onSuccess: s => { setSetup(s); setMessage(null); }, onError: fail("Could not start") });
  const confirm = useMutation({
    mutationFn: () => confirmTwoFactor(code),
    onSuccess: r => { setCodes(r.recoveryCodes); setSetup(null); setCode(""); setMessage({ ok: true, text: "Two-step sign-in is on" }); refresh(); },
    onError: fail("That code is not right"),
  });
  const renew = useMutation({
    mutationFn: () => newRecoveryCodes(code),
    onSuccess: r => { setCodes(r.recoveryCodes); setCode(""); setMessage({ ok: true, text: "New recovery codes: the old ones no longer work" }); refresh(); },
    onError: fail("That code is not right"),
  });
  const disable = useMutation({
    mutationFn: () => disableTwoFactor(code),
    onSuccess: () => { setCode(""); setCodes(null); setMessage({ ok: true, text: "Two-step sign-in is off" }); refresh(); },
    onError: fail("That code is not right"),
  });

  const codeInput = (
    <div>
      <label htmlFor="account-code" className="block text-xs font-medium text-gray-600 mb-1">Code from the app</label>
      <input id="account-code" value={code} onChange={e => setCode(e.target.value)} autoComplete="one-time-code" maxLength={9}
        className="w-40 border border-gray-300 rounded-md px-3 py-2 text-lg tracking-widest font-mono" />
    </div>
  );

  return (
    <div className="max-w-2xl" data-testid="account">
      <PageHeader title="My account" subtitle={`${user?.username ?? ""} · ${user?.tenantName ?? ""}`} />
      <section className="bg-white border border-gray-200 rounded-xl p-6 space-y-4" data-testid="two-factor-card">
        <div>
          <h2 className="font-semibold text-gray-800">Two-step sign-in</h2>
          <p className="text-sm text-gray-500">After your password, a 6-digit code from an authenticator app on your phone (Google or Microsoft Authenticator, 1Password…).</p>
        </div>
        {message && <p role={message.ok ? "status" : "alert"} className={`text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.text}</p>}
        {status && <p className="text-sm" data-testid="two-factor-status">
          {status.enabled ? <><b className="text-emerald-700">On</b> · {status.recoveryCodesLeft} recovery codes left</> : <b className="text-gray-600">Off</b>}
          {status.required && <span className="ml-2 text-xs text-amber-700">Your organisation requires it</span>}
        </p>}

        {codes && (
          <div className="border border-amber-200 bg-amber-50 rounded-md p-3" data-testid="recovery-codes">
            <p className="text-sm text-amber-800 mb-2">Keep these recovery codes somewhere safe. Each works once, if you lose your phone. They are not shown again.</p>
            <ul className="grid grid-cols-2 gap-1 font-mono text-sm">{codes.map(c => <li key={c}>{c}</li>)}</ul>
          </div>
        )}

        {status && !status.enabled && !setup && (
          <button type="button" className={BTN_PRIMARY} onClick={() => start.mutate()} disabled={start.isPending}>Turn on two-step sign-in</button>
        )}
        {setup && (
          <form className="space-y-3" onSubmit={e => { e.preventDefault(); confirm.mutate(); }}>
            <p className="text-sm text-gray-600">In the app choose <b>Enter a setup key</b> and type this key, or on your phone <a href={setup.otpauthUri} className="text-brand-600 underline">open it in the app</a>:</p>
            <p className="font-mono text-xs bg-gray-50 rounded p-2 break-all select-all" data-testid="setup-key">{setup.secret}</p>
            {codeInput}
            <div className="flex gap-2">
              <button type="submit" className={BTN_PRIMARY} disabled={confirm.isPending || code.trim().length < 6}>Turn on</button>
              <button type="button" className={BTN_SECONDARY} onClick={() => { setSetup(null); setCode(""); }}>Cancel</button>
            </div>
          </form>
        )}
        {status?.enabled && (
          <div className="space-y-3">
            {codeInput}
            <div className="flex gap-2">
              <button type="button" className={BTN_SECONDARY} disabled={renew.isPending || code.trim().length < 6} onClick={() => renew.mutate()}>New recovery codes</button>
              {!status.required && (
                <button type="button" className={BTN_DANGER} disabled={disable.isPending || code.trim().length < 6} onClick={() => disable.mutate()}>Turn off</button>
              )}
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
