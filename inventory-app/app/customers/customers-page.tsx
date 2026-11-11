"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getCustomersPage, deleteCustomer, importCustomers, exportCustomers, CustomerFileType } from "@/lib/api";
import { ExportMenu, ImportMenu } from "@/components/FileMenus";
import { Customer, CustomerImportResult, Page } from "@/types";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, ListHead } from "@/components/ListToolbar";
import { useConfirm } from "@/components/ConfirmDialog";
import { SkeletonRows } from "@/components/ui/Skeleton";

// Cin7 Core's customer files, on the Import and Export menus
const FILE_TYPES: { value: CustomerFileType; label: string }[] = [
  { value: "CUSTOMERS", label: "Customers" },
  { value: "ADDRESSES", label: "Addresses" },
  { value: "CONTACTS", label: "Contacts" },
  { value: "CUSTOM_PRICES", label: "Custom prices" },
];

export default function CustomersPage() {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const router = useRouter();
  const [importResult, setImportResult] = useState<CustomerImportResult | null>(null);
  const [importError, setImportError] = useState("");

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Customer>>({
    queryKey: ["customers-page", search, page, size],
    queryFn: () => getCustomersPage({ search: search || undefined, page, size }),
  });

  const customers = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["customers-page"] });
    qc.invalidateQueries({ queryKey: ["customers"] });
  };
  const deleteMutation = useMutation({
    mutationFn: deleteCustomer,
    onSuccess: refresh,
    onError: (e: any) => alert(e.response?.data?.error || "Failed to delete customer"),
  });
  const importMutation = useMutation({
    mutationFn: importCustomers,
    onSuccess: (r: CustomerImportResult) => { setImportResult(r); setImportError(""); refresh(); },
    onError: (e: any) => { setImportResult(null); setImportError(e.response?.data?.error || "Import failed"); },
  });

  return (
    <div>
      <PageHeader title="Customers" count={totalElements} noun="customers">
          <ImportMenu items={FILE_TYPES} inputLabel="Customer CSV file" busy={importMutation.isPending}
            onFile={(file, type) => importMutation.mutate({ file, type })} />
          <ExportMenu items={FILE_TYPES.map(t => ({ label: t.label, file: () => exportCustomers(t.value) }))} onError={setImportError} />
          <Link href="/customers/new"
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
            + New Customer
          </Link>
      </PageHeader>

      {importError && <p role="alert" data-testid="customer-import-error" className="mb-4 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{importError}</p>}
      {importResult && (
        <div role="status" data-testid="customer-import-result" className="mb-4 text-sm bg-emerald-50 text-emerald-800 px-4 py-3 rounded-lg">
          <div className="flex items-start justify-between">
            <p>
              Imported {importResult.rows} rows: {importResult.customersCreated} customers created,{" "}
              {importResult.customersUpdated} updated
              {importResult.contacts > 0 && <>, {importResult.contacts} contacts</>}
              {importResult.addresses > 0 && <>, {importResult.addresses} addresses</>}
              {importResult.prices > 0 && <>, {importResult.prices} contract prices</>}.
            </p>
            <button onClick={() => setImportResult(null)} className="text-xs text-emerald-700 hover:underline ml-4">Dismiss</button>
          </div>
          {importResult.warnings.length > 0 && (
            <details className="mt-2 text-amber-800">
              <summary className="cursor-pointer">{importResult.warnings.length} warning(s)</summary>
              <ul className="list-disc ml-5 mt-1 text-xs space-y-0.5">
                {importResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            </details>
          )}
        </div>
      )}

      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        searchLabel="Search customers" placeholder="Name, contact, email or phone"
        onRefresh={() => refetch()} refreshing={isFetching} />

      {isLoading ? <SkeletonRows /> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="list-table">
              <ListHead cols={["Name", "Status", "Main contact", "Terms", "Sales rep", "Credit limit", "Orders", ""]} right={["Credit limit", "Orders"]} />
              <tbody className="divide-y divide-gray-100">
                {customers.length === 0 && (
                  <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No customers found</td></tr>
                )}
                {customers.map(c => {
                  const main = c.contacts[0];
                  return (
                      <tr key={c.id} data-testid={`customer-row-${c.id}`} onClick={() => router.push(`/customers/${c.id}`)}
                        className="hover:bg-gray-50 cursor-pointer">
                        <td className="px-4 py-3">
                          <Link href={`/customers/${c.id}`} onClick={e => e.stopPropagation()} title={c.name} className="inline-block max-w-64 truncate align-middle font-medium text-gray-900 hover:underline">{c.name}</Link>
                          {c.source === "SHOPIFY" && <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700">Shopify</span>}
                        </td>
                        <td className="px-4 py-3">
                          <span className={`text-xs font-semibold px-2 py-0.5 rounded ${c.status === "ACTIVE" ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-500"}`}>
                            {c.status === "ACTIVE" ? "Active" : "Deprecated"}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-xs">
                          {main ? (
                            <span className="flex items-center gap-2 max-w-[26rem]" title={[main.name, main.email, main.phone || main.mobilePhone].filter(Boolean).join(" · ")}>
                              <span className="text-gray-700 truncate">{main.name || "—"}{c.contacts.length > 1 && <span className="text-gray-400"> +{c.contacts.length - 1}</span>}</span>
                              {main.email && <a href={`mailto:${main.email}`} onClick={e => e.stopPropagation()} className="text-brand-700 hover:underline truncate">{main.email}</a>}
                            </span>
                          ) : <span className="text-gray-400">—</span>}
                        </td>
                        <td className="px-4 py-3 text-xs text-gray-600">
                          {c.paymentTerm || "—"}{c.priceTier && <span className="text-gray-400"> · {c.priceTier}</span>}
                          {c.onHold && <span title={c.holdReason ?? undefined} className="ml-1 text-[10px] font-semibold px-1.5 py-0.5 rounded bg-red-100 text-red-700">On hold</span>}
                        </td>
                        
                        <td className="px-4 py-3 text-xs text-gray-600">{c.salesRepresentative || "—"}</td>
                        <td className="num text-gray-600">{c.creditLimit != null ? formatGBP(c.creditLimit) : "—"}</td>
                        <td className="num">
                          {c.orderCount > 0 ? (
                            <Link href={`/customers/${c.id}`} onClick={e => e.stopPropagation()} className="text-brand-700 hover:underline font-medium">
                              {c.orderCount}
                            </Link>
                          ) : <span className="text-gray-400">0</span>}
                        </td>
                        <td className="px-4 py-3 text-right space-x-3 whitespace-nowrap" onClick={e => e.stopPropagation()}>
                          <Link href={`/customers/${c.id}?edit=1`} className="text-xs text-gray-500 hover:text-gray-800">Edit</Link>
                          <button onClick={async () => { if (await ask({ title: `Delete customer "${c.name}"?`, action: "Delete", danger: true })) deleteMutation.mutate(c.id); }}
                            className="text-xs text-red-400 hover:text-red-600">Delete</button>
                        </td>
                      </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage}
            size={size} onSizeChange={n => { setSize(n); setPage(0); }} />
        </>
      )}
    </div>
  );
}
