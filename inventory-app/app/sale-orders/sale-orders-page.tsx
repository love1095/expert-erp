"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getSaleOrders, getProducts, getWarehouses, getCategories, getCustomers } from "@/lib/api";
import { SaleOrder, Product, Warehouse, SaleOrderStatus, FulfilmentStatus, Category, Customer, Page } from "@/types";
import { Fragment, useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import SaleOrderPanel from "./sale-order-panel";
import SaleOrderForm from "./sale-order-form";
import { ShopifyImportButton, ShopifyImportPanel } from "./shopify-import";
import { useAuth } from "@/contexts/AuthContext";

const STATUS_COLOR: Record<SaleOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  CONFIRMED: "bg-sky-100 text-sky-700",
  COMPLETED: "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

const FULFILMENT_LABEL: Record<FulfilmentStatus, { label: string; color: string }> = {
  UNFULFILLED:         { label: "Not shipped", color: "text-gray-400" },
  PARTIALLY_FULFILLED: { label: "Partly shipped", color: "text-amber-600" },
  FULFILLED:           { label: "Shipped", color: "text-emerald-600" },
};

export default function SaleOrdersPage() {
  const { user } = useAuth();
  // The Shopify import is admin-only (like the Shopify settings)
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const [showShopifyImport, setShowShopifyImport] = useState(false);
  const qc = useQueryClient();
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders", page],
    queryFn: () => getSaleOrders({ page }),
  });
  const { data: products = [] } = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });
  const { data: customers = [] } = useQuery<Customer[]>({ queryKey: ["customers"], queryFn: getCustomers });

  const orders: SaleOrder[] = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const [showForm, setShowForm] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);

  const onCreated = (so: SaleOrder) => {
    qc.invalidateQueries({ queryKey: ["sale-orders"] });
    qc.invalidateQueries({ queryKey: ["customers"] });
    qc.invalidateQueries({ queryKey: ["customers-page"] });
    setShowForm(false);
    setOpenId(so.id);
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Sale Orders</h1>
        <div className="flex gap-2">
          {isAdmin && <ShopifyImportButton onOpen={() => setShowShopifyImport(true)} />}
          <button onClick={() => setShowForm(!showForm)}
            className="bg-rose-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-rose-700">
            {showForm ? "Cancel" : "+ New SO"}
          </button>
        </div>
      </div>

      {isAdmin && showShopifyImport && <ShopifyImportPanel onClose={() => setShowShopifyImport(false)} />}

      {showForm && (
        <SaleOrderForm customers={customers} products={products} categories={categories} warehouses={warehouses} onCreated={onCreated} />
      )}

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["#", "Customer", "Reference", "Sales rep", "Total (£, inc VAT)", "Status", "Created", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {orders.length === 0 && <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No sale orders yet</td></tr>}
                {orders.map(o => (
                  <Fragment key={o.id}>
                  <tr data-testid={`so-row-${o.id}`} className={`hover:bg-gray-50 ${openId === o.id ? "bg-gray-50" : ""}`}>
                    <td className="px-4 py-3 font-mono text-xs text-gray-400">
                      {o.orderNumber}
                      {o.source === "SHOPIFY" && (
                        <span className="block mt-1 font-sans text-[10px] font-semibold px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700 w-fit">
                          Shopify {o.externalReference}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 font-medium">
                      {o.customerName}
                      <span className="block text-[11px] font-normal text-gray-400">{o.warehouseName} · {o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</span>
                    </td>
                    <td className="px-4 py-3 text-gray-600">{o.customerReference ?? "—"}</td>
                    <td className="px-4 py-3 text-gray-600">{o.salesRep ?? "—"}</td>
                    <td className="px-4 py-3 tabular-nums text-gray-700">{formatGBP(o.total)}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                      {o.needsReview && (
                        <span title={o.reviewReason} className="ml-1 text-xs font-semibold px-2 py-0.5 rounded bg-amber-100 text-amber-800">Needs review</span>
                      )}
                      {o.status !== "DRAFT" && o.status !== "CANCELLED" && (
                        <span className={`block mt-1 text-[11px] ${FULFILMENT_LABEL[o.fulfilmentStatus].color}`}>
                          {FULFILMENT_LABEL[o.fulfilmentStatus].label}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => setOpenId(openId === o.id ? null : o.id)}
                        className="text-xs text-rose-600 hover:text-rose-800 font-medium">
                        {openId === o.id ? "Close" : "Manage"}
                      </button>
                    </td>
                  </tr>
                  {openId === o.id && (
                    <tr>
                      <td colSpan={8} className="p-0">
                        <SaleOrderPanel orderId={o.id} warehouses={warehouses} />
                      </td>
                    </tr>
                  )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
