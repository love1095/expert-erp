"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getImportLogs, getImportTemplate, getWarehouses, importTasks } from "@/lib/api";
import { ImportLog, TaskImportResult, Warehouse } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { download } from "@/app/reports/report-parts";
import { useConfirm } from "@/components/ConfirmDialog";

type Tab = "sales" | "purchases" | "logs";
const SELECT = "border border-gray-300 rounded-md px-3 py-1.5 text-sm";

/**
 * Phase J: Cin7 Core's Sale / Purchase task CSV import (Sale → Import CSV file), same template, same three steps.
 * Used at cutover for the orders still open and the invoices / bills still unpaid. Preview first, then import:
 * all or nothing, and a document whose number is used already is refused, so a file cannot be imported twice.
 */
export default function DataImportPage() {
  const [tab, setTab] = useState<Tab>("sales");
  return (
    <div className="p-6 max-w-6xl">
      <PageHeader title="Import sales & purchases" />
      <div className="flex gap-6 border-b border-gray-200 mb-6 text-sm" role="tablist">
        {([["sales", "Sale tasks"], ["purchases", "Purchase tasks"], ["logs", "Logs"]] as [Tab, string][]).map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`pb-2 -mb-px ${tab === k ? "border-b-2 border-brand-600 text-brand-700 font-medium" : "text-gray-500 hover:text-gray-700"}`}>
            {label}
          </button>
        ))}
      </div>
      {tab === "logs" ? <Logs /> : <Importer key={tab} kind={tab} />}
    </div>
  );
}

function Importer({ kind }: { kind: "sales" | "purchases" }) {
  const ask = useConfirm();
  const sales = kind === "sales";
  const qc = useQueryClient();
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses-all"], queryFn: getWarehouses });
  const [file, setFile] = useState<File | null>(null);
  // Cin7's options (status Authorised and Export to Xero ticked by default, as on Cin7's screen)
  const [status, setStatus] = useState("AUTHORISED");
  const [fulfilment, setFulfilment] = useState("NO_PICKING");
  const [backorder, setBackorder] = useState(false);
  const [generateReceived, setGenerateReceived] = useState(false);
  const [alreadyReceived, setAlreadyReceived] = useState(false);
  const [exportToXero, setExportToXero] = useState(true);
  const [warehouseId, setWarehouseId] = useState("");
  const [result, setResult] = useState<TaskImportResult | null>(null);
  const [error, setError] = useState("");
  const reset = () => { setResult(null); setError(""); };

  const run = useMutation({
    mutationFn: (apply: boolean) => importTasks({ kind, file: file!, apply, options: {
      status, exportToXero, warehouseId: warehouseId ? Number(warehouseId) : undefined,
      ...(sales ? { fulfilment, backorder } : { generateReceived, alreadyReceived }) } }),
    onSuccess: (r: TaskImportResult) => { setResult(r); setError(""); if (r.applied) qc.invalidateQueries({ queryKey: ["import-logs"] }); },
    onError: (e: unknown) => { setResult(null); setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Import failed"); },
  });

  const docWord = sales ? "invoice" : "bill";
  return (
    <div className="space-y-6" data-testid={`import-${kind}`}>
      <section className="bg-white border border-gray-200 rounded-xl p-6 flex items-start justify-between gap-6">
        <div>
          <h2 className="font-semibold text-gray-800">Step 1 · Download the template</h2>
          <p className="text-xs text-gray-500 mt-1">The same headings as Cin7 Core&apos;s {sales ? "sale" : "purchase"} task template, so a file made for Cin7 imports here as it is.</p>
        </div>
        <button onClick={() => download(getImportTemplate(kind), sales ? "SaleTaskTemplate.csv" : "PurchaseTaskTemplate.csv")}
          className="border border-brand-600 text-brand-700 px-4 py-1.5 rounded-md text-sm font-medium hover:bg-brand-50 whitespace-nowrap">
          Download CSV template
        </button>
      </section>

      <section className="bg-white border border-gray-200 rounded-xl p-6 space-y-2 text-sm text-gray-600">
        <h2 className="font-semibold text-gray-800">Step 2 · Copy your {sales ? "sales" : "purchases"} into the template</h2>
        <p>One row per record, grouped by <b>InvoiceNumber</b>; <b>RecordType</b> says what the row is:</p>
        <ul className="list-disc ml-5 space-y-0.5">
          <li><b>Invoice</b>: the header: {sales ? "CustomerName (must exist), SalesRepresentative, Terms, PriceTier" : "SupplierName (must exist), Terms"}, InvoiceDate, InvoiceDueDate, StockLocation, Reference, MemoOnInvoice, TaxInclusive</li>
          <li><b>InvoiceLines</b>: Product (SKU), Quantity, Price/Amount, Discount (%), TaxRule</li>
          <li><b>InvoiceAdditionalCharges</b>: Product (the description), Quantity, Price/Amount, TaxRule</li>
          <li><b>Payment</b>: Price/Amount, {sales ? "DatePaid" : "DatePaid/DateReceived"}, Account (payment account code, e.g. 650) — needs status Authorised</li>
          <li><b>CreditLines</b> / <b>CreditAdditionalCharges</b>: a credit note (CreditNoteNumber, CreditNoteDate){sales ? " — needs Authorised and Auto Pick + Pack + Ship" : " — needs Authorised"}; <b>Refund</b>: the money paid back (Price/Amount, date, Account)</li>
          {!sales && <li><b>Received</b>: stock received (Product, Quantity, DateReceived); <b>Unstock</b>: credited goods sent back to the supplier</li>}
        </ul>
        <p>Rows are grouped by {sales ? "CustomerName" : "SupplierName"} + InvoiceNumber. Line totals must equal price × quantity less discount; dates (30/10/2026 or 2026-10-30) not in the future. Only new tasks are imported. With status &quot;No {docWord} yet&quot; the order keeps the InvoiceNumber as its number (Cin7&apos;s SO / PO number), or the optional <b>OrderNumber</b> column.</p>
      </section>

      <section className="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
        <h2 className="font-semibold text-gray-800">Step 3 · Import the file</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 max-w-3xl text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-gray-600">Invoice status</span>
            <select aria-label="Invoice status" value={status} onChange={e => { setStatus(e.target.value); reset(); }} className={SELECT}>
              <option value="DRAFT">Draft</option>
              <option value="AUTHORISED">Authorised</option>
              <option value="NONE">No {docWord} yet (open orders at cutover)</option>
            </select>
          </label>
          {sales ? (
            <label className="flex flex-col gap-1">
              <span className="text-xs font-medium text-gray-600">Pick, pack and ship processing mode</span>
              <select aria-label="Pick, pack and ship processing mode" value={fulfilment} onChange={e => { setFulfilment(e.target.value); reset(); }} className={SELECT}>
                <option value="NO_PICKING">No picking</option>
                <option value="AUTO_PICK">Auto Pick</option>
                <option value="AUTO_PICK_PACK">Auto Pick + Pack</option>
                <option value="AUTO_PICK_PACK_SHIP">Auto Pick + Pack + Ship</option>
                <option value="ALREADY_SHIPPED">Already shipped in the old system (no stock moved)</option>
              </select>
            </label>
          ) : <span />}
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-gray-600">Warehouse when StockLocation is empty</span>
            <select aria-label="Warehouse" value={warehouseId} onChange={e => { setWarehouseId(e.target.value); reset(); }} className={SELECT}>
              <option value="">The first warehouse</option>
              {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-gray-600">CSV file</span>
            <input aria-label="CSV file" type="file" accept=".csv,.txt,text/csv" onChange={e => { setFile(e.target.files?.[0] ?? null); reset(); }} className="text-sm" />
          </label>
        </div>
        <div className="space-y-2 text-sm text-gray-700">
          {sales ? (
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={backorder} onChange={e => { setBackorder(e.target.checked); reset(); }} />
              Set order backordered on low stock level
            </label>
          ) : (<>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={generateReceived} onChange={e => { setGenerateReceived(e.target.checked); if (e.target.checked) setAlreadyReceived(false); reset(); }} />
              Generate stock received from invoice lines
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={alreadyReceived} onChange={e => { setAlreadyReceived(e.target.checked); if (e.target.checked) setGenerateReceived(false); reset(); }} />
              Already received in the old system (no stock moved)
            </label>
          </>)}
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={exportToXero} onChange={e => { setExportToXero(e.target.checked); reset(); }} />
            Export imported tasks to Xero
          </label>
        </div>
        <p className="text-xs text-gray-500">
          At cutover untick <b>Export imported tasks to Xero</b> for {docWord}s Cin7 has already sent to Xero: they are recorded as exported and never sent again.
          A {sales ? "customer" : "supplier"} not found by name is created (as in Cin7); new ones are listed in the preview.
        </p>
        <div className="flex items-center gap-3">
          <button onClick={() => run.mutate(false)} disabled={!file || run.isPending}
            className="border border-gray-300 text-gray-700 px-4 py-1.5 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50">
            Preview
          </button>
          {run.isPending && <span className="text-sm text-gray-500">Working…</span>}
        </div>

        {error && <p role="alert" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">✕ {error}</p>}
        {result && <ResultView result={result} onImport={async () => { if (await ask({ title: "Import these sales and purchases?", message: "The documents in the file are created, with their stock and money.", action: "Import" })) run.mutate(true); }} busy={run.isPending} />}
      </section>
    </div>
  );
}

function ResultView({ result, onImport, busy }: { result: TaskImportResult; onImport: () => void; busy: boolean }) {
  const sales = result.kind === "SALES";
  return (
    <div data-testid="import-result" className="text-sm bg-gray-50 rounded-lg px-4 py-3 space-y-3">
      {result.errors.length > 0 ? (
        <div role="alert" className="text-red-700 space-y-1">
          <p className="font-medium">Nothing imported: fix these in the file and try again</p>
          <ul className="list-disc ml-5 text-xs max-h-48 overflow-y-auto">{result.errors.map((e, i) => <li key={i}>{e}</li>)}</ul>
        </div>
      ) : (
        <p>
          {result.applied ? "✓ Imported" : "Preview"}: <b>{result.documents} {sales ? "sales" : "purchases"}</b>, total {formatGBP(result.total)}
          {result.paid > 0 && <>, {formatGBP(result.paid)} already paid</>}.
        </p>
      )}
      {result.warnings.length > 0 && (
        <ul className="text-xs text-amber-800 list-disc ml-5 space-y-0.5">{result.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
      )}
      {result.docs.length > 0 && (
        <div className="max-h-72 overflow-y-auto">
          <table className="w-full text-xs">
            <thead className="text-gray-500 text-left sticky top-0 bg-gray-50">
              <tr><th className="py-1">In the file</th><th>{sales ? "Customer" : "Supplier"}</th><th>Order</th><th>{sales ? "Invoice" : "Bill"}</th>
                <th>Credit note</th><th>Date</th><th className="text-right">Lines</th><th className="text-right">Total</th><th className="text-right">Paid</th></tr>
            </thead>
            <tbody className="divide-y divide-gray-200 tabular-nums">
              {result.docs.map(d => (
                <tr key={d.number}>
                  <td className="py-1 font-mono">{d.number}</td><td className="truncate max-w-[14rem]">{d.party}</td>
                  <td className="font-mono">{d.orderNumber ?? "next number"}</td><td className="font-mono">{d.invoiceNumber ?? "—"}</td>
                  <td className="font-mono">{d.creditNoteNumber ?? ""}</td>
                  <td>{new Date(d.date).toLocaleDateString("en-GB")}</td><td className="text-right">{d.lines}</td>
                  <td className="text-right">{formatGBP(d.total)}</td><td className="text-right">{d.paid > 0 ? formatGBP(d.paid) : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {result.documents > result.docs.length && <p className="text-xs text-gray-400 mt-1">First {result.docs.length} shown</p>}
        </div>
      )}
      {!result.applied && result.errors.length === 0 && result.documents > 0 && (
        <button onClick={onImport} disabled={busy}
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
          Import {result.documents} {sales ? "sales" : "purchases"}
        </button>
      )}
    </div>
  );
}

function Logs() {
  const { data: logs = [], isLoading } = useQuery<ImportLog[]>({ queryKey: ["import-logs"], queryFn: getImportLogs });
  if (isLoading) return <p className="text-sm text-gray-500">Loading…</p>;
  if (logs.length === 0) return <p className="text-sm text-gray-500" data-testid="import-logs">No imports yet.</p>;
  return (
    <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="import-logs">
      <thead className="text-xs text-gray-500 text-left bg-gray-50">
        <tr><th className="px-4 py-2">When</th><th>What</th><th>File</th><th>Options</th><th className="text-right">Documents</th><th className="text-right px-4">Total</th><th>By</th></tr>
      </thead>
      <tbody className="divide-y divide-gray-100">
        {logs.map(l => (
          <tr key={l.id}>
            <td className="px-4 py-2">{new Date(l.createdAt).toLocaleString("en-GB")}</td>
            <td>{l.kind === "SALES" ? "Sales" : "Purchases"}</td><td className="font-mono text-xs">{l.fileName}</td>
            <td className="text-xs text-gray-500">{l.options}</td><td className="text-right">{l.documents}</td>
            <td className="text-right px-4">{formatGBP(l.total)}</td><td>{l.createdBy}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
