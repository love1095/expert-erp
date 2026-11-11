"use client";

import { useRef, useState } from "react";
import MoreActions from "@/components/MoreActions";
import type { DownloadedFile } from "@/lib/api";

// Cin7's Export and Import menus on a list: a drop-down of file types, not one file.
// Each file is made on the server in Cin7's columns, so it can be imported back here or into Cin7.

/** Saves a file the server made, under the server's file name. */
export function saveFile({ blob, fileName }: DownloadedFile) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}

export type ExportItem = {
  label: string;
  file: () => Promise<DownloadedFile>;
  disabledReason?: string;
  divider?: boolean;
};

export function ExportMenu({ items, onError, label = "Export" }: {
  items: ExportItem[];
  onError?: (message: string) => void;
  label?: string;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <MoreActions label={busy ? "Exporting…" : label}
      actions={items.map(i => ({
        label: i.label, disabledReason: i.disabledReason, divider: i.divider,
        onClick: async () => {
          setBusy(true);
          try {
            saveFile(await i.file());
          } catch (e) {
            const status = (e as { response?: { status?: number } })?.response?.status;
            onError?.(status === 403 ? `You may not export the ${i.label.toLowerCase()}` : `Could not export the ${i.label.toLowerCase()}`);
          } finally {
            setBusy(false);
          }
        },
      }))} />
  );
}

export type ImportItem<T extends string> = { value: T; label: string };

/**
 * The Import menu: choosing a file type opens the file picker; the file chosen is handed over with that type
 * (the server refuses a file of another type).
 */
export function ImportMenu<T extends string>({ items, onFile, busy, inputLabel = "CSV file", label = "Import" }: {
  items: ImportItem<T>[];
  onFile: (file: File, type: T) => void;
  busy?: boolean;
  inputLabel?: string;
  label?: string;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [type, setType] = useState<T>(items[0].value);
  return (
    <>
      <input ref={input} type="file" accept=".csv,text/csv" className="hidden" aria-label={inputLabel}
        onChange={e => {
          const f = e.target.files?.[0];
          if (f) onFile(f, type);
          e.target.value = "";
        }} />
      <MoreActions label={busy ? "Importing…" : label}
        actions={items.map(i => ({ label: i.label, onClick: () => { setType(i.value); input.current?.click(); } }))} />
    </>
  );
}
