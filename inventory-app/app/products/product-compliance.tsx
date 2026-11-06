"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteProductFile, getCompliance, getProductFile, getProductFiles, makeMainImage, saveCompliance, uploadProductFile } from "@/lib/api";
import { ProductCompliance, ProductFile, ProductFileKind } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY, Card } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";
import { useAuth } from "@/contexts/AuthContext";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm w-full";
const KIND_LABEL: Record<ProductFileKind, string> = { IMAGE: "Image", DATASHEET: "Datasheet", DECLARATION: "Declaration of conformity", CERTIFICATE: "Certificate", OTHER: "Other" };
const kb = (n: number) => n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`;

function daysLeft(date: string | null) {
  if (!date) return null;
  const ms = new Date(`${date}T00:00:00Z`).getTime() - new Date(new Date().toISOString().slice(0, 10) + "T00:00:00Z").getTime();
  return Math.round(ms / 86400000);
}

export function ExpiryBadge({ date }: { date: string | null }) {
  const d = daysLeft(date);
  if (d == null) return null;
  const cls = d < 0 ? "bg-red-100 text-red-700" : d <= 60 ? "bg-amber-100 text-amber-800" : "bg-emerald-100 text-emerald-700";
  return <span className={`text-xs font-semibold px-2 py-0.5 rounded ${cls}`} data-testid="expiry-badge">
    {d < 0 ? `Expired ${-d} day${d === -1 ? "" : "s"} ago` : d === 0 ? "Expires today" : `Expires in ${d} day${d === 1 ? "" : "s"}`}</span>;
}

/**
 * Phase P1: the SKU's safety compliance — standards, CE / UKCA marking, protection class, the certificate (number,
 * issuing body, expiry and its PDF). Each SKU holds its own; nothing comes from other products of the family.
 */
export function ComplianceSection({ productId }: { productId: number }) {
  const qc = useQueryClient();
  const { can } = useAuth();
  const edit = can("PRODUCT_EDIT");
  const { data: c } = useQuery<ProductCompliance>({ queryKey: ["compliance", productId], queryFn: () => getCompliance(productId) });
  const [f, setF] = useState({ standards: "", ceMarked: false, ukcaMarked: false, protectionClass: "", certificateNumber: "", certificateIssuer: "", certificateExpiry: "", notes: "" });
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (c) setF({ standards: c.standards ?? "", ceMarked: c.ceMarked, ukcaMarked: c.ukcaMarked, protectionClass: c.protectionClass ?? "",
      certificateNumber: c.certificateNumber ?? "", certificateIssuer: c.certificateIssuer ?? "", certificateExpiry: c.certificateExpiry ?? "", notes: c.notes ?? "" });
  }, [c]);
  const refresh = () => { qc.invalidateQueries({ queryKey: ["compliance", productId] }); qc.invalidateQueries({ queryKey: ["product-files", productId] }); };
  const save = useMutation({ mutationFn: () => saveCompliance(productId, { ...f, certificateExpiry: f.certificateExpiry || null }),
    onSuccess: () => { setMsg({ ok: true, text: "Saved" }); refresh(); }, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const upload = useMutation({ mutationFn: (file: File) => uploadProductFile(productId, "CERTIFICATE", file),
    onSuccess: () => { setMsg({ ok: true, text: "Certificate attached" }); refresh(); }, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  if (!c) return <p className="text-gray-400 text-sm">Loading…</p>;
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setF({ ...f, [k]: e.target.value });
  return (
    <Card title="Safety compliance" right={<ExpiryBadge date={c.certificateExpiry} />} testId="compliance-card">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
        <label className="md:col-span-2 flex flex-col gap-1 text-xs text-gray-500">Standards (EN / ISO)
          <input className={INPUT} aria-label="Standards" disabled={!edit} placeholder="EN 388:2016+A1:2018; EN ISO 21420:2020" value={f.standards} onChange={set("standards")} /></label>
        <label className="flex items-center gap-2"><input type="checkbox" disabled={!edit} checked={f.ceMarked} onChange={e => setF({ ...f, ceMarked: e.target.checked })} /> CE marked</label>
        <label className="flex items-center gap-2"><input type="checkbox" disabled={!edit} checked={f.ukcaMarked} onChange={e => setF({ ...f, ukcaMarked: e.target.checked })} /> UKCA marked</label>
        <label className="flex flex-col gap-1 text-xs text-gray-500">Protection class
          <input className={INPUT} aria-label="Protection class" disabled={!edit} placeholder="Category II, S3 SRC…" value={f.protectionClass} onChange={set("protectionClass")} /></label>
        <label className="flex flex-col gap-1 text-xs text-gray-500">Certificate number
          <input className={INPUT} aria-label="Certificate number" disabled={!edit} value={f.certificateNumber} onChange={set("certificateNumber")} /></label>
        <label className="flex flex-col gap-1 text-xs text-gray-500">Issued by (notified / approved body)
          <input className={INPUT} aria-label="Certificate issuer" disabled={!edit} value={f.certificateIssuer} onChange={set("certificateIssuer")} /></label>
        <label className="flex flex-col gap-1 text-xs text-gray-500">Certificate expiry
          <input type="date" className={INPUT} aria-label="Certificate expiry" disabled={!edit} value={f.certificateExpiry} onChange={set("certificateExpiry")} /></label>
        <label className="md:col-span-2 flex flex-col gap-1 text-xs text-gray-500">Notes
          <textarea className={INPUT} rows={2} aria-label="Compliance notes" disabled={!edit} value={f.notes} onChange={set("notes")} /></label>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
        {c.certificateFile
          ? <button className="text-brand-700 hover:underline" data-testid="certificate-file"
                    onClick={() => openPdf(() => getProductFile(c.certificateFile!.id), c.certificateFile!.fileName)}>{c.certificateFile.fileName}</button>
          : <span className="text-gray-400">No certificate attached</span>}
        {edit && <>
          <input ref={fileRef} type="file" accept="application/pdf,image/jpeg,image/png" className="hidden" aria-label="Certificate file"
                 onChange={e => { const file = e.target.files?.[0]; if (file) upload.mutate(file); e.target.value = ""; }} />
          <button className={BTN_SECONDARY} onClick={() => fileRef.current?.click()}>{c.certificateFile ? "Replace certificate" : "Attach certificate"}</button>
          <button className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate()}>Save compliance</button>
        </>}
        {msg && <span role="status" className={msg.ok ? "text-green-700" : "text-red-700"}>{msg.text}</span>}
      </div>
      <p className="mt-2 text-xs text-gray-500">These belong to this SKU only: other sizes or colours of the family keep their own.</p>
    </Card>
  );
}

/** Phase P4: images (the first is the main one, sent to Shopify), datasheets, declarations of conformity and other files. */
export function FilesSection({ productId }: { productId: number }) {
  const qc = useQueryClient();
  const { can } = useAuth();
  const edit = can("PRODUCT_EDIT");
  const { data: files = [] } = useQuery<ProductFile[]>({ queryKey: ["product-files", productId], queryFn: () => getProductFiles(productId) });
  const [kind, setKind] = useState<ProductFileKind>("IMAGE");
  const [msg, setMsg] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);
  const refresh = () => { qc.invalidateQueries({ queryKey: ["product-files", productId] }); qc.invalidateQueries({ queryKey: ["compliance", productId] }); setMsg(""); };
  const upload = useMutation({ mutationFn: (file: File) => uploadProductFile(productId, kind, file), onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  const remove = useMutation({ mutationFn: (id: number) => deleteProductFile(id), onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  const main = useMutation({ mutationFn: (id: number) => makeMainImage(id), onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  const images = files.filter(f => f.kind === "IMAGE");
  const docs = files.filter(f => f.kind !== "IMAGE");
  return (
    <Card title="Images and files" testId="files-card">
      <div className="flex flex-wrap gap-3" data-testid="product-images">
        {images.map((f, i) => (
          <figure key={f.id} className="w-32 text-xs">
            <ProductImage id={f.id} alt={f.fileName} />
            <figcaption className="mt-1 truncate" title={f.fileName}>{i === 0 ? <b>Main · </b> : null}{f.fileName}</figcaption>
            {edit && <div className="flex gap-2">
              {i > 0 && <button className="text-brand-700 hover:underline" onClick={() => main.mutate(f.id)}>Make main</button>}
              <button className="text-red-700 hover:underline" onClick={() => remove.mutate(f.id)}>Delete</button>
            </div>}
          </figure>
        ))}
        {images.length === 0 && <p className="text-sm text-gray-400">No images</p>}
      </div>
      <table className="w-full text-sm mt-4">
        <tbody className="divide-y divide-gray-100">
          {docs.map(f => (
            <tr key={f.id}>
              <td className="py-1.5">{KIND_LABEL[f.kind]}</td>
              <td><button className="text-brand-700 hover:underline" onClick={() => openPdf(() => getProductFile(f.id), f.fileName)}>{f.fileName}</button></td>
              <td className="text-gray-500">{kb(f.sizeBytes)}</td>
              <td className="text-right">{edit && <button className="text-red-700 hover:underline" onClick={() => remove.mutate(f.id)}>Delete</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {edit && <div className="mt-3 flex items-center gap-2 text-sm">
        <select className="border border-gray-300 rounded-md px-2 py-1.5" aria-label="Kind of file" value={kind} onChange={e => setKind(e.target.value as ProductFileKind)}>
          {(Object.keys(KIND_LABEL) as ProductFileKind[]).map(k => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
        </select>
        <input ref={fileRef} type="file" className="hidden" aria-label="Product file" accept={kind === "IMAGE" ? "image/*" : "application/pdf,image/jpeg,image/png"}
               onChange={e => { const file = e.target.files?.[0]; if (file) upload.mutate(file); e.target.value = ""; }} />
        <button className={BTN_SECONDARY} onClick={() => fileRef.current?.click()}>Upload</button>
        <span className="text-xs text-gray-500">Up to 10 MB. Images: JPEG, PNG, WebP, GIF; documents: PDF.</span>
      </div>}
      {msg && <p role="alert" className="mt-2 text-sm text-red-700">{msg}</p>}
    </Card>
  );
}

// Images are fetched with the user's token (an <img src> could not send it)
function ProductImage({ id, alt }: { id: number; alt: string }) {
  const { data: url } = useQuery({ queryKey: ["product-file-blob", id], queryFn: async () => URL.createObjectURL(await getProductFile(id)), staleTime: Infinity });
  return url ? <img src={url} alt={alt} className="w-32 h-32 object-contain border border-gray-200 rounded bg-white" />
    : <div className="w-32 h-32 border border-gray-200 rounded bg-gray-50" />;
}
