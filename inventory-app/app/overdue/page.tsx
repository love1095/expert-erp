"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getOverdueCustomers, getStatementsPdf, sendReminderPdf, getReminderPreviewPdf, StatementType } from "@/lib/api";
import { OverdueCustomer } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_SECONDARY } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";
import { useAuth } from "@/contexts/AuthContext";
import { REMINDER_LABEL, defaultFrom, isoDay } from "../customers/statement-card";

/**
 * Phase G3: customers with invoices past their due date, most overdue first. From here: preview or print the next
 * reminder (printing records it, so the next one is firmer), or print statements for the ticked customers.
 */
export default function OverduePage() {
  const qc = useQueryClient();
  const { user } = useAuth();
  const canRemind = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const { data: rows = [], isLoading } = useQuery<OverdueCustomer[]>({ queryKey: ["overdue-customers"], queryFn: getOverdueCustomers });
  const [ticked, setTicked] = useState<number[]>([]);
  const [type, setType] = useState<StatementType>("OUTSTANDING");
  const [busy, setBusy] = useState<string>("");
  const [error, setError] = useState("");
  const total = rows.reduce((s, r) => s + Number(r.overdue), 0);

  const run = async (key: string, load: () => Promise<Blob>, file: string, after?: () => void) => {
    setError("");
    setBusy(key);
    try { await openPdf(load, file); after?.(); } catch (e) { setError((e as Error).message); } finally { setBusy(""); }
  };
  const toggle = (id: number) => setTicked(t => (t.includes(id) ? t.filter(x => x !== id) : [...t, id]));
  const all = rows.length > 0 && ticked.length === rows.length;

  return (
    <div>
      <PageHeader title="Overdue" subtitle={rows.length ? `${rows.length} customers, ${formatGBP(total)} overdue` : undefined}>
        <select value={type} onChange={e => setType(e.target.value as StatementType)} aria-label="Statement type"
          className="border border-gray-300 rounded-md px-2 py-2 text-sm bg-white">
          <option value="OUTSTANDING">Outstanding statement</option>
          <option value="ACTIVITY">Activity statement (last 3 months)</option>
        </select>
        <button type="button" className={BTN_SECONDARY} disabled={ticked.length === 0 || busy !== ""} data-testid="print-statements"
          onClick={() => run("statements", () => getStatementsPdf(ticked, type === "ACTIVITY"
            ? { type, from: defaultFrom(), to: isoDay(new Date()) } : { type }), "statements.pdf")}>
          {busy === "statements" ? "Preparing…" : `Print statements${ticked.length ? ` (${ticked.length})` : ""}`}
        </button>
      </PageHeader>
      {error && <p role="alert" className="mb-3 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      <div className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
        <table className="w-full text-sm" data-testid="overdue-table">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-200 bg-gray-50">
              <th className="px-3 py-2 w-8">
                <input type="checkbox" checked={all} aria-label="Tick all customers"
                  onChange={() => setTicked(all ? [] : rows.map(r => r.customerId))} />
              </th>
              <th className="py-2 pr-3 font-medium">Customer</th>
              <th className="pr-3 font-medium text-right">Overdue</th>
              <th className="pr-3 font-medium text-right">Invoices</th>
              <th className="pr-3 font-medium">Oldest due</th>
              <th className="pr-3 font-medium text-right">Days</th>
              <th className="pr-3 font-medium text-right">Balance</th>
              <th className="pr-3 font-medium">Last reminder</th>
              <th className="pr-3 font-medium">Next</th>
              <th className="pr-3"></th>
            </tr>
          </thead>
          <tbody className="tabular-nums">
            {rows.map(r => (
              <tr key={r.customerId} className="border-b border-gray-100" data-testid={`overdue-row-${r.customerId}`}>
                <td className="px-3 py-2">
                  <input type="checkbox" checked={ticked.includes(r.customerId)} onChange={() => toggle(r.customerId)}
                    aria-label={`Tick ${r.customerName}`} />
                </td>
                <td className="py-2 pr-3">
                  <Link href={`/customers/${r.customerId}`} className="text-brand-700 hover:underline">{r.customerName}</Link>
                  {r.onHold && <span className="ml-2 text-xs bg-red-100 text-red-700 rounded px-1.5 py-0.5">On hold</span>}
                </td>
                <td className="pr-3 text-right text-red-700 font-semibold">{formatGBP(r.overdue)}</td>
                <td className="pr-3 text-right">{r.invoices}</td>
                <td className="pr-3">{new Date(r.oldestDue + "T00:00:00").toLocaleDateString("en-GB")}</td>
                <td className="pr-3 text-right">{r.daysOverdue}</td>
                <td className="pr-3 text-right">{formatGBP(r.balance)}</td>
                <td className="pr-3 text-gray-600">{r.lastReminderAt ? <ClientDate iso={r.lastReminderAt} /> : "None"}</td>
                <td className="pr-3" data-testid={`overdue-next-${r.customerId}`}>{REMINDER_LABEL[r.nextLevel]}</td>
                <td className="pr-3 py-1.5 whitespace-nowrap text-right">
                  <button type="button" className="text-xs text-brand-700 hover:underline mr-3" disabled={busy !== ""}
                    onClick={() => run(`preview-${r.customerId}`, () => getReminderPreviewPdf(r.customerId), `Reminder-${r.customerId}.pdf`)}>
                    Preview
                  </button>
                  {canRemind && (
                    <button type="button" className="text-xs text-brand-700 hover:underline" disabled={busy !== ""}
                      data-testid={`overdue-send-${r.customerId}`}
                      onClick={() => run(`send-${r.customerId}`, () => sendReminderPdf(r.customerId), `Reminder-${r.customerId}.pdf`,
                        () => qc.invalidateQueries({ queryKey: ["overdue-customers"] }))}>
                      {busy === `send-${r.customerId}` ? "Preparing…" : `Print ${REMINDER_LABEL[r.nextLevel].toLowerCase()}`}
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {!isLoading && rows.length === 0 && (
              <tr><td colSpan={10} className="px-3 py-6 text-center text-gray-500">No customer has an overdue invoice</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-gray-400 mt-2">Printing a reminder records it on the overdue invoices: the next one is firmer (1st reminder → 2nd reminder → final notice). E-mail comes later.</p>
    </div>
  );
}
