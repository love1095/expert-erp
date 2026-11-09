"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getSuppliersPage, deleteSupplier } from "@/lib/api";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Supplier, Page } from "@/types";
import { useState } from "react";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar } from "@/components/ListToolbar";
import { useConfirm } from "@/components/ConfirmDialog";
import { SkeletonRows } from "@/components/ui/Skeleton";

export default function SuppliersPage({ initialSearch = "" }: { initialSearch?: string }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [search, setSearch] = useState(initialSearch);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const router = useRouter();

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Supplier>>({
    queryKey: ["suppliers-page", search, page, size],
    queryFn: () => getSuppliersPage({ search: search || undefined, page, size }),
  });

  const suppliers = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const deleteMutation = useMutation({
    mutationFn: deleteSupplier,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["suppliers-page"] });
      qc.invalidateQueries({ queryKey: ["suppliers"] });
    },
  });

  return (
    <div>
      <PageHeader title="Suppliers" count={totalElements} noun="suppliers">
        <Link href="/suppliers/new"
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
          + New Supplier
        </Link>
      </PageHeader>

      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        searchLabel="Search suppliers" placeholder="Name, email or contact"
        onRefresh={() => refetch()} refreshing={isFetching} />

      {isLoading ? <SkeletonRows /> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="list-table">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Name", "Status", "Currency", "Payment Term", "Contact", "Phone", "Email", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {suppliers.length === 0 && (
                  <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No suppliers found</td></tr>
                )}
                {suppliers.map(s => (
                  <tr key={s.id} data-testid={`supplier-row-${s.id}`} onClick={() => router.push(`/suppliers/${s.id}`)} className="hover:bg-gray-50 cursor-pointer">
                    <td className="px-4 py-3 font-medium">
                      <Link href={`/suppliers/${s.id}`} onClick={e => e.stopPropagation()} title={s.name}
                        className="inline-block max-w-72 truncate align-middle text-gray-900 hover:underline">{s.name}</Link>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${s.status === "Active" ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-500"}`}>
                        {s.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500">{s.currency || "—"}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{s.paymentTerm || "—"}</td>
                    <td className="px-4 py-3 text-gray-600">{s.contactName || "—"}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{s.phone || "—"}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{s.email ? (
                      <a href={`mailto:${s.email}`} onClick={e => e.stopPropagation()} className="text-brand-700 hover:underline">{s.email}</a>
                    ) : "—"}</td>
                    <td className="px-4 py-3 text-right space-x-3 whitespace-nowrap" onClick={e => e.stopPropagation()}>
                      <Link href={`/suppliers/${s.id}?edit=1`} className="text-xs text-gray-500 hover:text-gray-800">Edit</Link>
                      <button onClick={async () => { if (await ask({ title: `Delete supplier "${s.name}"?`, action: "Delete", danger: true })) deleteMutation.mutate(s.id); }}
                        className="text-xs text-red-400 hover:text-red-600">Delete</button>
                    </td>
                  </tr>
                ))}
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
