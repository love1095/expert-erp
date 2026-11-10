"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { getCustomerCredit, getReminder, getReminderPreviewPdf, getStatement, getStatementPdf, sendReminderPdf, StatementType } from "@/lib/api";
import { CreditStatus, Customer, Statement, StatementAgeing, StatementOpenItem } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";
import { BTN_SECONDARY, Card } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";
import EmailButton, { SentEmails } from "@/components/EmailButton";
import { useAuth } from "@/contexts/AuthContext";
import { isoDay } from "@/lib/dates";

// yyyy-mm-dd in local time
export { isoDay };
// Like the server: the first day of the month two months back, so the last three months
export const defaultFrom = () => { const d = new Date(); return isoDay(new Date(d.getFullYear(), d.getMonth() - 2, 1)); };

export const REMINDER_LABEL: Record<number, string> = { 1: "1st reminder", 2: "2nd reminder", 3: "Final notice" };
const KIND: Record<string, string> = { INVOICE: "Invoice", CREDIT_NOTE: "Credit note", PAYMENT: "Payment", REFUND: "Refund", CREDIT: "Payment on account" };
const day = (iso?: string | null) => (iso ? new Date(iso + "T00:00:00").toLocaleDateString("en-GB") : "");
const isOverdue = (i: StatementOpenItem) => i.kind === "INVOICE" && i.daysOverdue > 0;

interface ReminderInfo { level: number; overdue: number; items: StatementOpenItem[]; previousReminderAt?: string | null }

/**
 * Phase G3: the customer's statement on screen and as PDF, like Cin7's two statements: Activity (a period with a
 * running balance) and Outstanding (what is still open), both with the ageing. Overdue reminders get firmer each
 * time one is printed and recorded (1st reminder → 2nd reminder → final notice).
 */
export default function StatementCard({ customer }: { customer: Customer }) {
  const qc = useQueryClient();
  const { user } = useAuth();
  const canRemind = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const [type, setType] = useState<StatementType>("ACTIVITY");
  const [from, setFrom] = useState(defaultFrom);
  const [to, setTo] = useState(() => isoDay(new Date()));
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const params = type === "ACTIVITY" ? { type, from, to } : { type, to };

  const { data: st, isError } = useQuery<Statement>({
    queryKey: ["customer-statement", customer.id, params],
    queryFn: () => getStatement(customer.id, params),
  });
  const { data: credit } = useQuery<CreditStatus>({ queryKey: ["customer-credit", customer.id], queryFn: () => getCustomerCredit(customer.id) });
  const hasOverdue = Number(credit?.overdue ?? 0) > 0;
  const { data: reminder } = useQuery<ReminderInfo>({
    queryKey: ["customer-reminder", customer.id],
    queryFn: () => getReminder(customer.id),
    enabled: hasOverdue,
    retry: false,
  });

  const run = async (what: string, load: () => Promise<Blob>, file: string, after?: () => void) => {
    setError("");
    setBusy(what);
    try { await openPdf(load, file); after?.(); } catch (e) { setError((e as Error).message); } finally { setBusy(""); }
  };
  const recorded = () => {
    qc.invalidateQueries({ queryKey: ["customer-reminder", customer.id] });
    qc.invalidateQueries({ queryKey: ["customer-statement", customer.id] });
    qc.invalidateQueries({ queryKey: ["overdue-customers"] });
  };

  return (
    <Card title="Statement" testId="customer-statement"
      right={
        <div className="flex rounded-md border border-gray-300 overflow-hidden text-xs" role="group" aria-label="Statement type">
          {(["ACTIVITY", "OUTSTANDING"] as const).map(t => (
            <button key={t} type="button" onClick={() => setType(t)} aria-pressed={type === t}
              data-testid={`statement-type-${t.toLowerCase()}`}
              className={`px-2.5 py-1 ${type === t ? "bg-brand-600 text-white" : "bg-white text-gray-700 hover:bg-gray-50"}`}>
              {t === "ACTIVITY" ? "Activity" : "Outstanding"}
            </button>
          ))}
        </div>
      }>
      <div className="flex flex-wrap items-end gap-3 mb-3 text-xs">
        {type === "ACTIVITY" && (
          <label className="flex flex-col gap-1 text-gray-500">From
            <input type="date" value={from} max={to} onChange={e => setFrom(e.target.value)} aria-label="Statement from"
              className="border border-gray-300 rounded px-2 py-1 text-sm text-gray-800" />
          </label>
        )}
        <label className="flex flex-col gap-1 text-gray-500">{type === "ACTIVITY" ? "To" : "As at"}
          <input type="date" value={to} max={isoDay(new Date())} onChange={e => setTo(e.target.value)} aria-label="Statement date"
            className="border border-gray-300 rounded px-2 py-1 text-sm text-gray-800" />
        </label>
        <button type="button" className={`${BTN_SECONDARY} ml-auto`} disabled={busy !== ""} data-testid="print-statement"
          onClick={() => run("statement", () => getStatementPdf(customer.id, params), `Statement-${customer.id}.pdf`)}>
          {busy === "statement" ? "Preparing…" : "Print statement"}
        </button>
        <EmailButton label="E-mail statement" docKey={`CUST:${customer.id}`}
          request={{ kind: "STATEMENT", id: customer.id, statementType: type, from: type === "ACTIVITY" ? from : undefined, to }} />
      </div>

      {isError && <p className="text-sm text-red-600">Could not load the statement</p>}
      {st && (st.type === "ACTIVITY" ? <ActivityTable st={st} /> : <OpenTable items={st.openItems} />)}
      {st && <Ageing a={st.ageing} />}

      {hasOverdue && reminder && (
        <div className="mt-4 flex flex-wrap items-center gap-3 text-xs bg-amber-50 border border-amber-200 rounded px-3 py-2" data-testid="customer-reminder">
          <span className="text-amber-900">
            {reminder.items.length} invoice{reminder.items.length === 1 ? "" : "s"} overdue, {formatGBP(reminder.overdue)}.
            Next: <strong data-testid="reminder-level">{REMINDER_LABEL[reminder.level]}</strong>
            {reminder.previousReminderAt && <> · last sent <ClientDate iso={reminder.previousReminderAt} /></>}
          </span>
          <span className="ml-auto flex gap-2">
            <button type="button" className={BTN_SECONDARY} disabled={busy !== ""}
              onClick={() => run("preview", () => getReminderPreviewPdf(customer.id), `Reminder-${customer.id}.pdf`)}>
              {busy === "preview" ? "Preparing…" : "Preview"}
            </button>
            {canRemind && (
              <button type="button" className={BTN_SECONDARY} disabled={busy !== ""} data-testid="send-reminder"
                onClick={() => run("send", () => sendReminderPdf(customer.id), `Reminder-${customer.id}.pdf`, recorded)}>
                {busy === "send" ? "Preparing…" : `Print ${REMINDER_LABEL[reminder.level].toLowerCase()}`}
              </button>
            )}
            {canRemind && <EmailButton label={`E-mail ${REMINDER_LABEL[reminder.level].toLowerCase()}`} docKey={`CUST:${customer.id}`}
              request={{ kind: "REMINDER", id: customer.id }} onSent={recorded} />}
          </span>
        </div>
      )}
      {error && <p role="alert" className="text-sm text-red-600 mt-2">{error}</p>}
      <div className="mt-4"><SentEmails docKey={`CUST:${customer.id}`} /></div>
    </Card>
  );
}

function ActivityTable({ st }: { st: Statement }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs" data-testid="statement-entries">
        <thead>
          <tr className="text-left text-gray-500 border-b border-gray-200">
            <th className="py-1.5 pr-2 font-medium">Date</th><th className="pr-2 font-medium">Type</th><th className="pr-2 font-medium">Number</th>
            <th className="pr-2 font-medium">Reference</th><th className="pr-2 font-medium">Due</th>
            <th className="pr-2 font-medium text-right">Debit</th><th className="pr-2 font-medium text-right">Credit</th>
            <th className="font-medium text-right">Balance</th>
          </tr>
        </thead>
        <tbody className="tabular-nums">
          <tr className="border-b border-gray-100 text-gray-600">
            <td className="py-1.5 pr-2">{day(st.from)}</td><td colSpan={6} className="pr-2">Opening balance</td>
            <td className="text-right">{formatGBP(st.opening)}</td>
          </tr>
          {st.entries.map((e, i) => (
            <tr key={i} className="border-b border-gray-100">
              <td className="py-1.5 pr-2">{day(e.date)}</td>
              <td className="pr-2">{KIND[e.kind]}</td>
              <td className="pr-2">{e.invoiceId ? <Link href={`/invoices/${e.invoiceId}`} className="text-brand-700 hover:underline">{e.number}</Link> : e.number}</td>
              <td className="pr-2 text-gray-600">{e.reference === "BANK" ? "Bank" : e.reference === "CASH" ? "Cash" : e.reference}</td>
              <td className="pr-2 text-gray-600">{day(e.dueDate)}</td>
              <td className="pr-2 text-right">{Number(e.debit) ? formatGBP(e.debit) : ""}</td>
              <td className="pr-2 text-right">{Number(e.credit) ? formatGBP(e.credit) : ""}</td>
              <td className="text-right">{formatGBP(e.balance)}</td>
            </tr>
          ))}
          {st.entries.length === 0 && (
            <tr><td colSpan={8} className="py-2 text-gray-500 italic">No invoices, payments or credit notes in this period</td></tr>
          )}
          <tr className="font-semibold">
            <td colSpan={7} className="py-1.5 pr-2 text-right">Closing balance</td>
            <td className="text-right" data-testid="statement-closing">{formatGBP(st.closing)}</td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

function OpenTable({ items }: { items: StatementOpenItem[] }) {
  const total = items.reduce((s, i) => s + Number(i.open), 0);
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs" data-testid="statement-open-items">
        <thead>
          <tr className="text-left text-gray-500 border-b border-gray-200">
            <th className="py-1.5 pr-2 font-medium">Date</th><th className="pr-2 font-medium">Type</th><th className="pr-2 font-medium">Number</th>
            <th className="pr-2 font-medium">Due</th><th className="pr-2 font-medium text-right">Days overdue</th>
            <th className="pr-2 font-medium text-right">Amount</th><th className="pr-2 font-medium text-right">Paid</th>
            <th className="font-medium text-right">Open</th>
          </tr>
        </thead>
        <tbody className="tabular-nums">
          {items.map(i => (
            <tr key={i.invoiceId ?? `credit-${i.number}-${i.date}`} className={`border-b border-gray-100 ${isOverdue(i) ? "text-red-700" : ""}`}>
              <td className="py-1.5 pr-2">{day(i.date)}</td>
              <td className="pr-2">{KIND[i.kind]}</td>
              <td className="pr-2">{i.invoiceId ? <Link href={`/invoices/${i.invoiceId}`} className="text-brand-700 hover:underline">{i.number}</Link> : i.number}</td>
              <td className="pr-2">{day(i.dueDate)}</td>
              <td className="pr-2 text-right">{isOverdue(i) ? i.daysOverdue : ""}</td>
              <td className="pr-2 text-right">{formatGBP(i.total)}</td>
              <td className="pr-2 text-right">{Number(i.settled) ? formatGBP(Math.abs(Number(i.settled))) : ""}</td>
              <td className="text-right">{formatGBP(i.open)}</td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan={8} className="py-2 text-gray-500 italic">Nothing outstanding</td></tr>}
          <tr className="font-semibold">
            <td colSpan={7} className="py-1.5 pr-2 text-right">Balance due</td>
            <td className="text-right" data-testid="statement-closing">{formatGBP(total)}</td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

function Ageing({ a }: { a: StatementAgeing }) {
  const cells: [string, number][] = [
    ["Current", a.current], ["1–30 days", a.days1to30], ["31–60 days", a.days31to60],
    ["61–90 days", a.days61to90], ["Over 90 days", a.over90], ["Total", a.total],
  ];
  return (
    <dl className="mt-3 grid grid-cols-3 md:grid-cols-6 gap-2 text-xs" data-testid="statement-ageing">
      {cells.map(([label, v]) => (
        <div key={label} className="bg-gray-50 rounded px-2 py-1.5">
          <dt className="text-gray-500">{label}</dt>
          <dd className={`tabular-nums ${label !== "Current" && label !== "Total" && Number(v) > 0 ? "text-red-700 font-semibold" : "text-gray-800"} ${label === "Total" ? "font-semibold" : ""}`}>
            {formatGBP(v)}</dd>
        </div>
      ))}
    </dl>
  );
}
