"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getDocumentTemplates, getDocumentsPdf, getPdf } from "@/lib/api";
import { DocType, DocumentTemplate } from "@/types";
import { BTN_SECONDARY } from "@/components/DetailParts";

type Kind = "sale-orders" | "invoices" | "purchase-orders";

/**
 * Opens documents as PDF in a new tab (D5). The API needs the login token, so the PDF is fetched here and shown from
 * memory; the tab is opened first so the browser doesn't block it as a pop-up. If it is blocked, the file downloads.
 */
export async function openPdf(load: () => Promise<Blob>, fileName: string) {
  const tab = window.open("", "_blank");
  try {
    const blob = await load();
    const url = URL.createObjectURL(new Blob([blob], { type: "application/pdf" }));
    if (tab) {
      tab.location.href = url;
    } else {
      const a = document.createElement("a");
      a.href = url;
      a.download = fileName;
      a.click();
    }
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    tab?.close();
    throw new Error(await errorText(e));
  }
}

export const printOne = (kind: Kind, id: number, fileName: string, template?: number) =>
  openPdf(() => getPdf(`/${kind}/${id}/pdf${template ? `?template=${template}` : ""}`), fileName);
export const printMany = (kind: Kind, ids: number[]) => openPdf(() => getDocumentsPdf(kind, ids), `${kind}.pdf`);

// Errors come back as a JSON blob ({ error: "..." })
async function errorText(e: unknown): Promise<string> {
  const data = (e as { response?: { data?: unknown } })?.response?.data;
  if (data instanceof Blob) {
    try { return JSON.parse(await data.text()).error ?? "Could not print"; } catch { return "Could not print"; }
  }
  return "Could not print";
}

/**
 * Print, with the organisation's templates for this document type (I4) to choose from when it has more than one;
 * the default is chosen first, as in Cin7.
 */
export default function PrintButton({ kind, id, fileName, label = "Print", docType }: {
  kind: Kind; id: number; fileName: string; label?: string; docType?: DocType;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [picked, setPicked] = useState<number | null>(null);
  const { data: all = [] } = useQuery<DocumentTemplate[]>({ queryKey: ["document-templates"], queryFn: getDocumentTemplates, enabled: !!docType, staleTime: 60_000 });
  const templates = all.filter(t => t.docType === docType);
  const chosen = picked ?? templates.find(t => t.isDefault)?.id ?? null;
  return (
    <span className="inline-flex flex-col items-end">
      <span className="inline-flex items-center gap-1">
        {templates.length > 1 && (
          <select aria-label="Template" className="border border-gray-300 rounded-md px-2 py-2 text-sm bg-white" value={chosen ?? ""}
            onChange={e => setPicked(Number(e.target.value))}>
            {templates.map(t => <option key={t.id} value={t.id!}>{t.name}</option>)}
          </select>
        )}
        <button type="button" disabled={busy} className={BTN_SECONDARY} data-testid="print-document"
          onClick={async () => {
            setError("");
            setBusy(true);
            try { await printOne(kind, id, fileName, chosen ?? undefined); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
          }}>
          {busy ? "Preparing…" : label}
        </button>
      </span>
      {error && <span role="alert" className="text-xs text-red-600 mt-1">{error}</span>}
    </span>
  );
}
