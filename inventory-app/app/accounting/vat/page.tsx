"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { connectMtd, disconnectMtd, getFiledVatReturns, getMtdObligations, getMtdStatus, getVatReview, recordVatReturn, setMtdVrn, submitVatReturn } from "@/lib/api";
import { FiledVatReturn, MtdStatus, VatBoxes, VatPeriod, VatReview } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { INPUT, errorOf } from "../ledger-parts";
import { clientInfo } from "./client-info";

const day = (d: string | null) => (d ? new Date(d).toLocaleDateString("en-GB") : "");
const BOXES: [keyof VatBoxes, string][] = [
  ["box1", "VAT due on sales and other outputs"], ["box2", "VAT due on acquisitions from other EC Member States"], ["box3", "Total VAT due (box 1 + box 2)"],
  ["box4", "VAT reclaimed on purchases and other inputs"], ["box5", "Net VAT to pay to HMRC or reclaim"], ["box6", "Total value of sales, excluding VAT"],
  ["box7", "Total value of purchases, excluding VAT"], ["box8", "Total value of supplies to EC Member States, excluding VAT"],
  ["box9", "Total value of acquisitions from EC Member States, excluding VAT"],
];

/**
 * Phase K4: VAT returns like Xero's — connect to HMRC (Making Tax Digital), the VAT periods HMRC expects, review a
 * period's nine boxes, submit with the legal declaration (or record a return filed elsewhere), and the returns filed.
 */
export default function VatReturnsPage() {
  // Xero's Adviser (K5): the accountant, and admins
  const { can } = useAuth();
  const isAdmin = can("ADVISER");
  const { data: status } = useQuery<MtdStatus>({ queryKey: ["mtd-status"], queryFn: getMtdStatus });
  const [period, setPeriod] = useState<{ from: string; to: string; periodKey?: string; due?: string } | null>(null);
  return (
    <div className="p-6 max-w-6xl space-y-5">
      <PageHeader title="VAT returns" />
      {status && <Connection status={status} isAdmin={isAdmin} />}
      {status?.connected && <Obligations onPick={p => setPeriod({ from: p.start, to: p.end, periodKey: p.periodKey, due: p.due })} />}
      <PeriodPicker onPick={(from, to) => setPeriod({ from, to })} />
      {period && <ReviewPanel key={`${period.from}-${period.to}`} {...period} canSubmit={!!status?.connected && !!period.periodKey && isAdmin} isAdmin={isAdmin}
        onDone={() => setPeriod(null)} />}
      <Filed />
    </div>
  );
}

function Connection({ status, isAdmin }: { status: MtdStatus; isAdmin: boolean }) {
  const qc = useQueryClient();
  const [vrn, setVrn] = useState(status.vrn ?? "");
  const [msg, setMsg] = useState("");
  const refresh = () => qc.invalidateQueries({ queryKey: ["mtd-status"] });
  const saveVrn = useMutation({ mutationFn: () => setMtdVrn(vrn), onSuccess: () => { setMsg("Saved"); refresh(); }, onError: e => setMsg(errorOf(e)) });
  const connect = useMutation({ mutationFn: connectMtd, onSuccess: r => { window.location.href = r.url; }, onError: e => setMsg(errorOf(e)) });
  const disconnect = useMutation({ mutationFn: disconnectMtd, onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="mtd-connection">
      <h2 className="font-semibold">Making Tax Digital</h2>
      {!status.configured && <p className="text-sm text-amber-800 bg-amber-50 rounded px-3 py-2">Not set up on this server yet: it needs the HMRC developer credentials.
        Meanwhile returns can be reviewed here and recorded once filed another way.</p>}
      <div className="flex flex-wrap items-end gap-3 text-sm">
        <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">VAT number</span>
          <input aria-label="VAT number" className={INPUT} disabled={!isAdmin} value={vrn} onChange={e => setVrn(e.target.value)} placeholder="123456789" /></label>
        {isAdmin && <button className={BTN_SECONDARY} onClick={() => saveVrn.mutate()}>Save</button>}
        {status.connected
          ? <span className="text-green-700">✓ Connected to HMRC{status.connectedAt && ` on ${day(status.connectedAt)}`}{status.connectedBy && ` by ${status.connectedBy}`}</span>
          : <span className="text-gray-500">Not connected</span>}
        {isAdmin && status.configured && (status.connected
          ? <button className={BTN_SECONDARY} onClick={() => disconnect.mutate()}>Disconnect</button>
          : <button className={BTN_PRIMARY} disabled={!status.vrn} onClick={() => connect.mutate()}>Connect to HMRC</button>)}
      </div>
      {msg && <p role="status" className="text-sm text-gray-600">{msg}</p>}
    </section>
  );
}

function Obligations({ onPick }: { onPick: (p: VatPeriod) => void }) {
  const { data = [], isLoading, error } = useQuery<VatPeriod[]>({ queryKey: ["mtd-obligations"], queryFn: () => getMtdObligations(clientInfo()) });
  return (
    <section className="bg-white border border-gray-200 rounded-xl" data-testid="mtd-obligations">
      <h2 className="font-semibold px-5 pt-4">VAT periods at HMRC</h2>
      {isLoading ? <p className="px-5 py-3 text-sm text-gray-500">Asking HMRC…</p> : error ? <p role="alert" className="px-5 py-3 text-sm text-red-700">{errorOf(error)}</p> : (
        <table className="w-full text-sm mt-2">
          <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">Period</th><th>Due</th><th>Status</th><th /></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {data.map(p => (
              <tr key={p.periodKey}>
                <td className="px-5 py-2">{day(p.start)} – {day(p.end)}</td><td>{day(p.due)}</td>
                <td>{p.status === "F" || p.filedReturnId ? "Filed" : "Open"}</td>
                <td className="text-right px-5">{p.status === "O" && !p.filedReturnId && <button className={BTN_SECONDARY} onClick={() => onPick(p)}>Review</button>}</td>
              </tr>
            ))}
            {data.length === 0 && <tr><td colSpan={4} className="px-5 py-4 text-gray-400">HMRC has no VAT periods for these dates</td></tr>}
          </tbody>
        </table>
      )}
    </section>
  );
}

function PeriodPicker({ onPick }: { onPick: (from: string, to: string) => void }) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <span className="text-gray-600">Or review any period:</span>
      <input aria-label="From" type="date" className={INPUT} value={from} onChange={e => setFrom(e.target.value)} />
      <input aria-label="To" type="date" className={INPUT} value={to} onChange={e => setTo(e.target.value)} />
      <button className={BTN_SECONDARY} disabled={!from || !to} onClick={() => onPick(from, to)}>Review</button>
    </div>
  );
}

function ReviewPanel({ from, to, periodKey, due, canSubmit, isAdmin, onDone }: { from: string; to: string; periodKey?: string; due?: string;
  canSubmit: boolean; isAdmin: boolean; onDone: () => void }) {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery<VatReview>({ queryKey: ["vat-review", from, to], queryFn: () => getVatReview(from, to) });
  const [declared, setDeclared] = useState(false);
  const [msg, setMsg] = useState("");
  const done = () => { ["mtd-obligations", "filed-vat-returns", "vat-review"].forEach(k => qc.invalidateQueries({ queryKey: [k] })); onDone(); };
  const submit = useMutation({ mutationFn: () => submitVatReturn({ periodKey, from, to, due, declaration: declared, client: clientInfo() }), onSuccess: done, onError: e => setMsg(errorOf(e)) });
  const record = useMutation({ mutationFn: () => recordVatReturn({ from, to, note: "Filed another way" }), onSuccess: done, onError: e => setMsg(errorOf(e)) });
  if (isLoading) return <p className="text-sm text-gray-500">Working out the return…</p>;
  if (error || !data) return <p role="alert" className="text-sm text-red-700">{errorOf(error)}</p>;
  const b = data.vatReturn.boxes;
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="vat-review">
      <h2 className="font-semibold">VAT return {day(from)} – {day(to)}{periodKey && <span className="text-gray-400 font-normal"> · HMRC period {periodKey}</span>}</h2>
      {data.warnings.length > 0 && <ul className="text-sm text-amber-800 bg-amber-50 rounded px-4 py-2">{data.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
      <table className="w-full text-sm">
        <tbody className="divide-y divide-gray-100">
          {BOXES.map(([k, label], i) => (
            <tr key={k} className={k === "box5" ? "font-semibold" : ""}>
              <td className="py-1.5 w-16 text-gray-500">Box {i + 1}</td><td>{label}</td>
              <td className="text-right tabular-nums" data-testid={`vat-${k}`}>{formatGBP(Number(b[k]))}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-gray-500">{data.vatReturn.refund ? "HMRC owes the business" : "To pay HMRC"} · {data.vatReturn.documents.length} documents ·
        VAT account moved {formatGBP(data.ledgerVatMovement)} in the period</p>
      {isAdmin && (
        <div className="space-y-2 border-t border-gray-100 pt-3">
          {canSubmit && (<>
            <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={declared} onChange={e => setDeclared(e.target.checked)} />
              <span>{data.declaration}</span></label>
            <button className={BTN_PRIMARY} disabled={!declared || submit.isPending} onClick={() => submit.mutate()}>Submit to HMRC</button>
          </>)}
          <button className={`${BTN_SECONDARY} ml-2`} disabled={record.isPending} onClick={() => record.mutate()}>Mark as filed (filed another way)</button>
          {msg && <p role="alert" className="text-sm text-red-700">{msg}</p>}
        </div>
      )}
    </section>
  );
}

function Filed() {
  const { data = [] } = useQuery<FiledVatReturn[]>({ queryKey: ["filed-vat-returns"], queryFn: getFiledVatReturns });
  return (
    <section className="bg-white border border-gray-200 rounded-xl" data-testid="filed-vat-returns">
      <h2 className="font-semibold px-5 pt-4">Filed returns</h2>
      <table className="w-full text-sm mt-2">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">Period</th><th>How</th><th>Filed</th><th>HMRC receipt</th><th className="text-right px-5">Box 5</th></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {data.map(r => (
            <tr key={r.id}>
              <td className="px-5 py-2">{day(r.from)} – {day(r.to)}</td>
              <td>{r.how === "MTD" ? "Sent to HMRC" : "Filed another way"}</td>
              <td>{day(r.filedAt)}{r.filedBy && ` · ${r.filedBy}`}</td>
              <td className="text-xs">{r.formBundleNumber ? `Form bundle ${r.formBundleNumber}${r.chargeRefNumber ? ` · charge ${r.chargeRefNumber}` : ""}` : r.note ?? ""}</td>
              <td className="text-right tabular-nums px-5">{formatGBP(Number(r.boxes.box5))}</td>
            </tr>
          ))}
          {data.length === 0 && <tr><td colSpan={5} className="px-5 py-4 text-gray-400">No returns filed yet</td></tr>}
        </tbody>
      </table>
    </section>
  );
}
