"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { chooseXeroOrganisation, connectXero, disconnectXero, getXeroConnection, getXeroLink, getXeroPayments, getXeroQueue,
  pullXeroPayments, retryXeroItem, runXeroQueue, setXeroLink } from "@/lib/api";
import { XeroConnection, XeroPulledPayment, XeroQueue } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { useConfirm } from "@/components/ConfirmDialog";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const when = (d: string | null) => (d ? new Date(d).toLocaleString("en-GB") : "");

/**
 * Phase L5: Finance → Xero — the Xero API link: connection (fake or live), the way to Xero (CSV files or this link),
 * the queue with failed items and Retry, payments pulled back from Xero, and the history of what was sent.
 */
export default function XeroPage() {
  const { can } = useAuth();
  const manage = can("ADVISER");
  const { data: conn } = useQuery<XeroConnection>({ queryKey: ["xero-connection"], queryFn: getXeroConnection });
  const { data: link } = useQuery({ queryKey: ["xero-link"], queryFn: getXeroLink });
  return (
    <div className="p-6 max-w-6xl space-y-5">
      <PageHeader title="Xero" />
      {conn && <Connection conn={conn} manage={manage} />}
      {link && <LinkChoice link={link.link} manage={manage} connected={conn?.status === "CONNECTED"} />}
      {link?.link === "API" && <Queue manage={manage} />}
      {link?.link === "API" && <PaymentsFromXero manage={manage} />}
    </div>
  );
}

function Connection({ conn, manage }: { conn: XeroConnection; manage: boolean }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [msg, setMsg] = useState("");
  const refresh = () => qc.invalidateQueries({ queryKey: ["xero-connection"] });
  const connect = useMutation({ mutationFn: connectXero, onSuccess: r => { window.location.href = r.url; }, onError: e => setMsg(errorOf(e)) });
  const choose = useMutation({ mutationFn: chooseXeroOrganisation, onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  const disconnect = useMutation({ mutationFn: disconnectXero, onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="xero-connection">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">Connection</h2>
        <span className={`text-xs px-2 py-0.5 rounded ${conn.mode === "fake" ? "bg-amber-50 text-amber-800" : "bg-green-50 text-green-700"}`}>
          {conn.mode === "fake" ? "Fake Xero (test mode)" : "Live Xero"}</span>
      </div>
      {conn.mode === "fake" && <p className="text-xs text-gray-500">Nothing leaves the server: a stand-in for Xero answers like Xero. The live link needs the Xero developer app (see TODO).</p>}
      {!conn.configured && <p className="text-sm text-amber-800">The live link is not set up on this server yet (Xero developer app).</p>}
      <div className="flex flex-wrap items-center gap-3 text-sm">
        {conn.status === "CONNECTED" && <span className="text-green-700" data-testid="xero-status">✓ Connected to {conn.xeroTenantName}{conn.connectedBy && ` · by ${conn.connectedBy}`}{conn.connectedAt && ` on ${when(conn.connectedAt)}`}</span>}
        {conn.status === "NOT_CONNECTED" && <span className="text-gray-500" data-testid="xero-status">Not connected</span>}
        {conn.status === "EXPIRED" && <span className="text-red-700" data-testid="xero-status">The connection has expired: connect again</span>}
        {conn.status === "CHOOSE_ORGANISATION" && <span className="text-amber-800" data-testid="xero-status">Choose the Xero organisation to send to:</span>}
        {manage && conn.status === "CHOOSE_ORGANISATION" && conn.organisations.map(o => (
          <button key={o.tenantId} className={BTN_SECONDARY} onClick={() => choose.mutate(o.tenantId)}>{o.tenantName}</button>))}
        {manage && conn.configured && conn.status !== "CONNECTED" && <button className={BTN_PRIMARY} onClick={() => connect.mutate()}>Connect to Xero</button>}
        {manage && conn.status === "CONNECTED" && <button className={BTN_SECONDARY} onClick={async () => { if (await ask({ title: "Disconnect Xero?", message: "Nothing is sent to Xero until it is connected again.", action: "Disconnect", danger: true })) disconnect.mutate(); }}>Disconnect</button>}
      </div>
      {conn.lastError && <p className="text-sm text-red-700">{conn.lastError}</p>}
      {msg && <p role="alert" className="text-sm text-red-700">{msg}</p>}
    </section>
  );
}

function LinkChoice({ link, manage, connected }: { link: "CSV" | "API"; manage: boolean; connected: boolean }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [msg, setMsg] = useState("");
  const set = useMutation({ mutationFn: setXeroLink, onSuccess: () => { setMsg(""); qc.invalidateQueries({ queryKey: ["xero-link"] }); }, onError: e => setMsg(errorOf(e)) });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-2" data-testid="xero-link">
      <h2 className="font-semibold">How documents reach Xero</h2>
      <p className="text-sm text-gray-600">
        {link === "API" ? "Through this link: invoices, bills, credit notes, payments and stock journals are sent as they are ready, and payments entered in Xero come back."
          : <>As CSV files you import into Xero yourself (<Link href="/accounting-export" className="text-brand-700 hover:underline">Xero export</Link>).</>}
        {" "}Either way, a document is sent once: whatever one way sent, the other skips.
      </p>
      {manage && (
        <div className="flex gap-2 text-sm">
          <button className={link === "CSV" ? BTN_PRIMARY : BTN_SECONDARY} disabled={link === "CSV"} onClick={async () => { if (await ask({ title: "Switch the Xero link to CSV files?", action: "Switch" })) set.mutate("CSV"); }}>CSV files</button>
          <button className={link === "API" ? BTN_PRIMARY : BTN_SECONDARY} disabled={link === "API" || !connected} onClick={async () => { if (await ask({ title: "Switch the Xero link to the Xero API?", message: "Documents are then sent to Xero directly.", action: "Switch" })) set.mutate("API"); }}>
            Send through the Xero link</button>
          {!connected && link === "CSV" && <span className="text-xs text-gray-500 self-center">Connect to Xero first</span>}
        </div>
      )}
      {msg && <p role="alert" className="text-sm text-red-700">{msg}</p>}
    </section>
  );
}

function Queue({ manage }: { manage: boolean }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [status, setStatus] = useState<"" | "FAILED" | "PENDING" | "SENT">("");
  const [msg, setMsg] = useState("");
  const { data } = useQuery<XeroQueue>({ queryKey: ["xero-queue", status], queryFn: () => getXeroQueue(status || undefined) });
  const refresh = () => qc.invalidateQueries({ queryKey: ["xero-queue"] });
  const run = useMutation({ mutationFn: runXeroQueue, onSuccess: (r: { sent: number; failed: number }) => { setMsg(`Sent ${r.sent}, failed ${r.failed}`); refresh(); }, onError: e => setMsg(errorOf(e)) });
  const retry = useMutation({ mutationFn: retryXeroItem, onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  const c = data?.counts ?? {};
  return (
    <section className="bg-white border border-gray-200 rounded-xl" data-testid="xero-queue">
      <div className="px-5 pt-4 flex flex-wrap items-center gap-3">
        <h2 className="font-semibold">Sent to Xero</h2>
        <span className="text-sm text-gray-600" data-testid="xero-counts">{c.PENDING ?? 0} waiting · <span className={(c.FAILED ?? 0) > 0 ? "text-red-700 font-medium" : ""}>{c.FAILED ?? 0} failed</span> · {c.SENT ?? 0} sent</span>
        {manage && <button className={BTN_SECONDARY} disabled={run.isPending} onClick={async () => { if (await ask({ title: "Send to Xero now?", message: "The documents waiting are sent to Xero.", action: "Send" })) run.mutate(); }}>{run.isPending ? "Sending…" : "Send now"}</button>}
        {msg && <span role="status" className="text-sm text-gray-600">{msg}</span>}
      </div>
      <div className="flex gap-5 border-b border-gray-200 text-sm px-5 mt-2" role="tablist">
        {([["", "All"], ["FAILED", "Failed"], ["PENDING", "Waiting"], ["SENT", "History"]] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={status === k} onClick={() => setStatus(k)}
            className={`pb-2 -mb-px ${status === k ? "border-b-2 border-brand-600 text-brand-700 font-medium" : "text-gray-500"}`}>{l}</button>
        ))}
      </div>
      <table className="w-full text-sm">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">What</th><th>Status</th><th>Tries</th><th>When</th><th>Xero / problem</th><th className="px-5" /></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {(data?.items ?? []).map(i => (
            <tr key={i.id} data-testid={`xero-item-${i.kind}`}>
              <td className="px-5 py-1.5">{i.label ?? i.key}</td>
              <td><span className={`text-xs px-2 py-0.5 rounded ${i.status === "SENT" ? "bg-green-50 text-green-700" : i.status === "FAILED" ? "bg-red-50 text-red-700" : "bg-amber-50 text-amber-800"}`}>
                {i.status === "SENT" ? "Sent" : i.status === "FAILED" ? "Failed" : "Waiting"}</span></td>
              <td>{i.attempts}</td>
              <td className="text-xs text-gray-500">{i.status === "SENT" ? when(i.sentAt) : i.status === "PENDING" ? `next ${when(i.nextAttemptAt)}` : when(i.createdAt)}</td>
              <td className="text-xs max-w-md">{i.status === "SENT" ? <span className="font-mono text-gray-400">{i.lastError ?? i.xeroId}</span> : <span className="text-red-700">{i.lastError}</span>}</td>
              <td className="px-5 text-right">{manage && i.status === "FAILED" && <button className={BTN_SECONDARY} onClick={async () => { if (await ask({ title: "Send this document to Xero again?", action: "Retry" })) retry.mutate(i.id); }}>Retry</button>}</td>
            </tr>
          ))}
          {(data?.items ?? []).length === 0 && <tr><td colSpan={6} className="px-5 py-4 text-gray-400">Nothing here</td></tr>}
        </tbody>
      </table>
    </section>
  );
}

function PaymentsFromXero({ manage }: { manage: boolean }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [msg, setMsg] = useState("");
  const { data = [] } = useQuery<XeroPulledPayment[]>({ queryKey: ["xero-payments"], queryFn: getXeroPayments });
  const pull = useMutation({
    mutationFn: pullXeroPayments,
    onSuccess: (r: { recorded: number; voided: number; unmatched: number; errors: number }) => {
      setMsg(`Recorded ${r.recorded}, voided ${r.voided}, unmatched ${r.unmatched}, errors ${r.errors}`);
      qc.invalidateQueries({ queryKey: ["xero-payments"] });
    },
    onError: e => setMsg(errorOf(e)),
  });
  return (
    <section className="bg-white border border-gray-200 rounded-xl" data-testid="xero-payments">
      <div className="px-5 pt-4 flex items-center gap-3">
        <h2 className="font-semibold">Payments entered in Xero</h2>
        {manage && <button className={BTN_SECONDARY} disabled={pull.isPending} onClick={async () => { if (await ask({ title: "Pull payments from Xero now?", message: "Payments made in Xero are recorded here.", action: "Pull" })) pull.mutate(); }}>Pull now</button>}
        {msg && <span role="status" className="text-sm text-gray-600">{msg}</span>}
      </div>
      <p className="px-5 text-xs text-gray-500">Checked every 5 minutes (and at once when Xero says something changed); recorded against the invoice or bill here.</p>
      <table className="w-full text-sm mt-2">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">Paid on</th><th>Document</th><th className="text-right">Amount</th><th>Status</th><th className="px-5">Note</th></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {data.map(p => (
            <tr key={p.id}>
              <td className="px-5 py-1.5">{p.paidOn ? new Date(p.paidOn).toLocaleDateString("en-GB") : ""}</td>
              <td>{p.docKey ? <Link href={`/invoices/${p.docKey.substring(4)}`} className="text-brand-700 hover:underline">{p.number ?? p.docKey}</Link> : "—"}</td>
              <td className="text-right tabular-nums">{p.amount != null ? formatGBP(p.amount) : ""}</td>
              <td>{({ RECORDED: "Recorded", VOIDED: "Deleted in Xero, voided", UNMATCHED: "Not matched", ERROR: "Not recorded" } as Record<string, string>)[p.status]}</td>
              <td className="px-5 text-xs text-red-700">{p.message}</td>
            </tr>
          ))}
          {data.length === 0 && <tr><td colSpan={5} className="px-5 py-4 text-gray-400">No payments from Xero yet</td></tr>}
        </tbody>
      </table>
    </section>
  );
}
