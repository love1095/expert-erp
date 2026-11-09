"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getAccountingChanged, getAccountingExportFile, getAccountingExports, getAccountingPending, getAccountingSettings,
  runAccountingExport, undoAccountingExport, updateAccountingSettings,
} from "@/lib/api";
import { AccountingSettings, ExportBatch, ExportChanged, ExportKind, ExportPending } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ukDate } from "@/lib/periods";
import { useAuth } from "@/contexts/AuthContext";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { download } from "../reports/report-parts";
import { useConfirm } from "@/components/ConfirmDialog";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400 disabled:bg-gray-50";
const LABEL = "block text-xs font-medium text-gray-600 mb-1";

const KIND: Record<ExportKind, string> = {
  INVOICE: "Invoices", CREDIT_NOTE: "Credit notes", BILL: "Bills", SUPPLIER_CREDIT: "Supplier credit notes",
  JOURNAL: "Manual journals", PAYMENT: "Payments (to match)",
};

// Cin7 Core's account mapping, with what each account is for
const ACCOUNTS: [keyof AccountingSettings, string, string][] = [
  ["inventoryAccount", "Inventory control account", "Stock on hand. Bills put stock here; shipments take it out at FIFO cost (Cin7: 631)."],
  ["cogsAccount", "Cost of goods sold account", "The cost of the goods shipped (Cin7: 310)."],
  ["discrepancyAccount", "Inventory discrepancy account", "Stock adjustments and stocktake differences, instead of COGS."],
  ["revenueAccount", "Default revenue account", "Sales invoice and credit note lines (Cin7: 200 Sales)."],
  ["purchaseChargesAccount", "Purchase charges account", "Carriage, duty and other charges on supplier bills (an expense)."],
  ["customerCreditAccount", "Customer credit account", "Money received before an invoice (Cin7: 802 Customer Credits)."],
  ["supplierDepositsAccount", "Supplier deposits account", "Money paid to a supplier before the bill (Cin7: 615 Supplier Deposits)."],
];

function errorText(e: unknown, fallback: string) {
  return (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback;
}

/** An error from a blob request comes back as a blob: read the message out of it. */
async function blobError(e: unknown, fallback: string) {
  const data = (e as { response?: { data?: unknown } })?.response?.data;
  if (data instanceof Blob) {
    try { return JSON.parse(await data.text()).error ?? fallback; } catch { return fallback; }
  }
  return errorText(e, fallback);
}

/**
 * Phase H5: hand the ERP's documents to Xero as its import files, CSV first, like Cin7 sends them through its Xero link:
 * invoices and credit notes, bills and supplier credit notes, COGS and stock journals; payments listed to match.
 */
export default function AccountingExportPage() {
  const ask = useConfirm();
  const { user } = useAuth();
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const qc = useQueryClient();
  const { data: settings } = useQuery<AccountingSettings>({ queryKey: ["accounting-settings"], queryFn: getAccountingSettings });
  const { data: pending, error: pendingError } = useQuery<ExportPending>({ queryKey: ["accounting-pending"], queryFn: getAccountingPending });
  const { data: batches = [] } = useQuery<ExportBatch[]>({ queryKey: ["accounting-exports"], queryFn: getAccountingExports });
  const { data: changed = [] } = useQuery<ExportChanged[]>({ queryKey: ["accounting-changed"], queryFn: getAccountingChanged });
  // The settings as edited; until the first change, as saved
  const [edited, setForm] = useState<AccountingSettings | null>(null);
  const form = edited ?? settings ?? null;
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [exportMessage, setExportMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [showItems, setShowItems] = useState(false);

  const refresh = () => ["accounting-pending", "accounting-exports", "accounting-changed"].forEach(k => qc.invalidateQueries({ queryKey: [k] }));
  const save = useMutation({
    mutationFn: updateAccountingSettings,
    onSuccess: (s: AccountingSettings) => { qc.setQueryData(["accounting-settings"], s); setForm(null); setMessage({ ok: true, text: "Saved" }); refresh(); },
    onError: e => setMessage({ ok: false, text: errorText(e, "Could not save") }),
  });
  const run = useMutation({
    mutationFn: runAccountingExport,
    onSuccess: ({ blob, filename }) => { download(Promise.resolve(blob), filename); setExportMessage({ ok: true, text: `Exported: ${filename}` }); refresh(); },
    onError: async e => setExportMessage({ ok: false, text: await blobError(e, "Could not export") }),
  });
  const undo = useMutation({
    mutationFn: undoAccountingExport,
    onSuccess: () => { setExportMessage({ ok: true, text: "Undone: its documents can be exported again" }); refresh(); },
    onError: e => setExportMessage({ ok: false, text: errorText(e, "Could not undo") }),
  });

  const items = pending?.items ?? [];
  const ready = (pending?.missing.length ?? 1) === 0 && items.length > 0;

  return (
    <div className="space-y-6" data-testid="accounting-export">
      <PageHeader title="Xero export" subtitle="Invoices, credit notes, bills, COGS and stock journals as Xero's import files, each only once" />

      <section className="bg-white border border-gray-200 rounded-xl p-6" data-testid="export-ready">
        <div className="flex items-start justify-between gap-4 mb-3">
          <div>
            <h2 className="font-semibold text-gray-800">Ready to export</h2>
            <p className="text-xs text-gray-500">
              {pending?.startDate ? <>Dated from {ukDate(pending.startDate)} to today, not exported yet.</> : "Set the export start date below first."}
            </p>
          </div>
          <button type="button" className={BTN_PRIMARY} disabled={!ready || run.isPending} onClick={async () => { if (!(await ask({ title: "Export to Xero files now?", message: "The documents in the files are marked as exported.", action: "Export" }))) return; setExportMessage(null); run.mutate(); }}>
            {run.isPending ? "Exporting…" : "Export for Xero"}
          </button>
        </div>
        {pendingError && <p role="alert" className="text-sm text-red-600">{errorText(pendingError, "Could not load what is ready")}</p>}
        {pending && pending.missing.length > 0 && (
          <p role="alert" className="text-sm text-amber-700 mb-2" data-testid="export-missing">Still to set up: {pending.missing.join(", ")}</p>
        )}
        {exportMessage && <p role={exportMessage.ok ? "status" : "alert"} className={`text-sm mb-2 ${exportMessage.ok ? "text-emerald-700" : "text-red-600"}`}>{exportMessage.text}</p>}
        <div className="flex flex-wrap gap-2">
          {(Object.keys(KIND) as ExportKind[]).map(k => (
            <span key={k} className="px-2.5 py-1 rounded-md bg-gray-50 border border-gray-200 text-sm" data-testid={`export-count-${k}`}>
              {KIND[k]} <b className="tabular-nums">{pending?.counts[k] ?? 0}</b>
            </span>
          ))}
        </div>
        {items.length > 0 && (
          <button type="button" className="mt-3 text-sm text-brand-700 hover:underline" onClick={() => setShowItems(!showItems)}>
            {showItems ? "Hide" : "Show"} the {items.length} items
          </button>
        )}
        {showItems && (
          <table className="w-full text-sm mt-2" data-testid="export-items">
            <thead><tr className="text-left text-xs text-gray-500 border-b border-gray-200">
              <th className="py-1.5 font-medium">Date</th><th className="font-medium">Type</th><th className="font-medium">Document</th>
              <th className="font-medium">Contact</th><th className="font-medium text-right">Amount</th></tr></thead>
            <tbody className="tabular-nums">
              {items.map(i => (
                <tr key={i.key} className="border-b border-gray-100">
                  <td className="py-1">{ukDate(i.date)}</td><td>{KIND[i.kind]}</td><td>{i.number}</td><td>{i.party}</td>
                  <td className="text-right">{formatGBP(i.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="mt-3 text-xs text-gray-500">
          In Xero: invoices from <i>Business › Invoices › Import</i>, bills from <i>Business › Bills to pay › Import</i>, journals from
          <i> Accounting › Manual journals › Import</i>; amounts are tax exclusive. Xero cannot import payments from a file: Payments.csv lists them to match in Xero.
        </p>
      </section>

      {changed.length > 0 && (
        <section className="bg-white border border-amber-200 rounded-xl p-6" data-testid="export-changed">
          <h2 className="font-semibold text-amber-800 mb-1">Changed after export: put right in Xero</h2>
          <ul className="text-sm space-y-1">
            {changed.map(c => <li key={c.key}>{ukDate(c.date)} · <b>{c.number}</b> {c.party} · {c.what}</li>)}
          </ul>
        </section>
      )}

      <section className="bg-white border border-gray-200 rounded-xl p-6" data-testid="export-batches">
        <h2 className="font-semibold text-gray-800 mb-2">Exports</h2>
        <table className="w-full text-sm">
          <thead><tr className="text-left text-xs text-gray-500 border-b border-gray-200">
            <th className="py-1.5 font-medium">When</th><th className="font-medium">By</th><th className="font-medium text-right">Invoices</th>
            <th className="font-medium text-right">Credit notes</th><th className="font-medium text-right">Bills</th>
            <th className="font-medium text-right">Supplier credits</th><th className="font-medium text-right">Journals</th>
            <th className="font-medium text-right">Payments</th><th /></tr></thead>
          <tbody className="tabular-nums">
            {batches.map(b => (
              <tr key={b.id} className={`border-b border-gray-100 ${b.undoneAt ? "text-gray-400" : ""}`}>
                <td className="py-1.5">{new Date(b.createdAt).toLocaleString("en-GB")}</td><td>{b.createdBy}</td>
                <td className="text-right">{b.invoices}</td><td className="text-right">{b.creditNotes}</td><td className="text-right">{b.bills}</td>
                <td className="text-right">{b.supplierCredits}</td><td className="text-right">{b.journals}</td><td className="text-right">{b.payments}</td>
                <td className="text-right whitespace-nowrap pl-3">
                  <button type="button" className="text-brand-700 hover:underline mr-3"
                    onClick={() => download(getAccountingExportFile(b.id), `Xero_export_${b.id}.zip`)}>Download</button>
                  {b.undoneAt ? <span title={`Undone by ${b.undoneBy}`}>Undone</span> : (
                    <button type="button" className="text-red-600 hover:underline"
                      onClick={async () => { if (await ask({ title: "Undo this export? Only if Xero did not import its files: its documents will be exported again.", action: "Undo export", danger: true })) undo.mutate(b.id); }}>
                      Undo
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {batches.length === 0 && <tr><td colSpan={9} className="py-4 text-center text-gray-500">Nothing exported yet</td></tr>}
          </tbody>
        </table>
      </section>

      {form && (
        <form className="bg-white border border-gray-200 rounded-xl p-6" data-testid="account-mapping"
          onSubmit={e => { e.preventDefault(); setMessage(null); save.mutate(form); }}>
          <h2 className="font-semibold text-gray-800">Account mapping</h2>
          <p className="text-xs text-gray-500 mb-4">Xero account codes, as in Cin7&apos;s account mapping. {isAdmin ? "" : "Only an admin can change them."}</p>
          <div className="grid md:grid-cols-2 gap-4">
            <div>
              <label htmlFor="acc-start" className={LABEL}>Export start date</label>
              <input id="acc-start" type="date" className={INPUT} disabled={!isAdmin} value={form.exportStartDate ?? ""}
                onChange={e => setForm({ ...form, exportStartDate: e.target.value || null })} />
              <p className="text-[11px] text-gray-400 mt-1">Nothing dated before it is exported: Xero already has what Cin7 sent. Set it to the day the ERP takes over from Cin7.</p>
            </div>
            {ACCOUNTS.map(([k, label, help]) => (
              <div key={k}>
                <label htmlFor={`acc-${k}`} className={LABEL}>{label}</label>
                <input id={`acc-${k}`} className={INPUT} disabled={!isAdmin} maxLength={20} value={(form[k] as string | null) ?? ""}
                  onChange={e => setForm({ ...form, [k]: e.target.value })} />
                <p className="text-[11px] text-gray-400 mt-1">{help}</p>
              </div>
            ))}
          </div>
          {isAdmin && (
            <div className="mt-4 flex items-center gap-3">
              <button type="submit" className={BTN_SECONDARY} disabled={save.isPending}>Save</button>
              {message && <span role={message.ok ? "status" : "alert"} className={`text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.text}</span>}
            </div>
          )}
        </form>
      )}
    </div>
  );
}
