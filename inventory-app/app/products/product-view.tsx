"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteProduct, getAuditLog, getMovementsPage, getProduct, getProductStock } from "@/lib/api";
import { AuditLog, Page, Product, ProductStock, StockMovement } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";
import Pagination from "@/components/Pagination";
import MoreActions from "@/components/MoreActions";
import { BackLink, RecordHeader, Card, Field, BTN_PRIMARY, TH, TD } from "@/components/DetailParts";

// Cin7's product sections (left), those the ERP has data for; the rest come later
const SECTIONS = ["General", "Prices", "Stock", "Suppliers", "Reorder levels", "Movements", "Activity log"] as const;
type Section = typeof SECTIONS[number];

const qty = (n?: number | null) => (n == null ? "—" : Number(n).toLocaleString());
const money = (n?: number | null) => (n == null ? "—" : formatGBP(n));

/** One product on its own page, like Cin7's product details: sections on the left, the chosen one on the right. */
export default function ProductView({ productId }: { productId: number }) {
  const router = useRouter();
  const qc = useQueryClient();
  const [section, setSection] = useState<Section>("General");
  const [error, setError] = useState("");
  const { data: p, isLoading } = useQuery<Product>({ queryKey: ["product", productId], queryFn: () => getProduct(productId) });

  const remove = useMutation({
    mutationFn: () => deleteProduct(productId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["products-page"] }); router.push("/products"); },
    onError: (e: any) => setError(e.response?.data?.error || "Could not delete the product"),
  });

  if (isLoading || !p) return <p className="text-gray-400 text-sm">Loading…</p>;

  return (
    <div data-testid={`product-page-${p.id}`}>
      <BackLink href="/products" />
      <RecordHeader
        title={<><span className="font-mono">{p.sku}</span> : {p.name}</>}
        badges={
          <div className="flex flex-wrap items-center gap-1.5 mt-1.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${p.status === "ACTIVE" ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-500"}`}>
              {p.status === "ACTIVE" ? "Active" : "Deprecated"}
            </span>
            <span className="text-xs text-gray-500">{p.type === "STOCK" ? "Stock" : "Service"}{p.categoryName ? ` · ${p.categoryName}` : ""}</span>
          </div>
        }
        actions={<>
          <MoreActions actions={[
            { label: "Delete product", danger: true,
              onClick: () => { if (confirm(`Delete ${p.sku}? Only a product without stock movements or orders can be deleted.`)) remove.mutate(); } },
          ]} />
          <Link href={`/products?edit=${p.id}`} className={BTN_PRIMARY}>Edit</Link>
        </>}
      />
      {error && <p role="alert" data-testid="product-error" className="mb-4 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      <div className="grid grid-cols-1 lg:grid-cols-[13rem_minmax(0,1fr)] gap-5 items-start">
        <nav aria-label="Product sections" className="bg-white border border-gray-200 rounded-lg py-1">
          {SECTIONS.map(s => (
            <button key={s} type="button" onClick={() => setSection(s)} aria-current={section === s ? "page" : undefined}
              className={`w-full text-left px-4 py-2 text-sm ${section === s ? "bg-gray-100 font-semibold text-gray-900" : "text-gray-600 hover:bg-gray-50"}`}>
              {s}
            </button>
          ))}
        </nav>

        <div className="min-w-0 space-y-4" data-testid="product-section">
          {section === "General" && <General p={p} />}
          {section === "Prices" && <Prices p={p} />}
          {section === "Stock" && <StockSection p={p} />}
          {section === "Suppliers" && <Suppliers p={p} />}
          {section === "Reorder levels" && <ReorderLevels p={p} />}
          {section === "Movements" && <Movements p={p} />}
          {section === "Activity log" && <Activity p={p} />}
        </div>
      </div>
    </div>
  );
}

function Grid({ children }: { children: React.ReactNode }) {
  return <dl className="grid grid-cols-1 md:grid-cols-3 gap-x-6 gap-y-3">{children}</dl>;
}

function General({ p }: { p: Product }) {
  const d = p.details ?? {};
  return <>
    <Card title="General">
      <Grid>
        <Field label="SKU" value={<span className="font-mono">{p.sku}</span>} />
        <Field label="Product name" value={p.name} />
        <Field label="Status" value={p.status === "ACTIVE" ? "Active" : "Deprecated"} />
        <Field label="Type" value={p.type === "STOCK" ? "Stock" : "Service"} />
        <Field label="Category" value={p.categoryName} />
        <Field label="Brand" value={p.brand} />
        <Field label="Unit of measure" value={p.unit} />
        <Field label="Barcode" value={p.barcode} />
        <Field label="Costing method" value="FIFO" />
        <Field label="I am selling this product" value={p.sellable ? "Yes" : "No"} />
        <Field label="Purchase tax rule" value={d.purchaseTaxRule} />
        <Field label="Sale tax rule" value={d.saleTaxRule} />
        <Field label="Tags" value={d.tags} />
        <Field label="HS code" value={d.hsCode} />
        <Field label="Country of origin" value={d.countryOfOrigin} />
        {p.familySku && <Field label="Family" value={`${p.familySku}${p.familyName ? ` · ${p.familyName}` : ""}`} />}
        {p.variantOptions?.length ? <Field label="Variant" value={p.variantOptions.map(o => `${o.name}: ${o.value}`).join(" · ")} /> : null}
      </Grid>
    </Card>
    <Card title="Description">
      <p className="text-sm text-gray-700 whitespace-pre-line">{p.description || p.shortDescription || <span className="text-gray-400">No description</span>}</p>
    </Card>
    {d.internalNote && (
      <Card title="Internal notes"><p className="text-sm text-gray-700 whitespace-pre-line">{d.internalNote}</p></Card>
    )}
    {(d.weight != null || d.length != null) && (
      <Card title="Dimensions">
        <Grid>
          <Field label="Weight" value={d.weight != null ? `${d.weight} ${d.weightUnit ?? ""}` : undefined} />
          <Field label="Length × width × height"
            value={d.length != null ? `${d.length} × ${d.width ?? "—"} × ${d.height ?? "—"} ${d.dimensionUnit ?? ""}` : undefined} />
        </Grid>
      </Card>
    )}
  </>;
}

function Prices({ p }: { p: Product }) {
  const d = p.details ?? {};
  const margin = (price?: number) => (price && p.costPrice != null && price > 0 ? `${Math.round(((price - p.costPrice) / price) * 1000) / 10}%` : "—");
  return (
    <Card title="Prices" flush>
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>{["Price", "Amount (ex VAT)", "Margin over cost"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          <tr><td className={TD}>Cost price</td><td className={`${TD} tabular-nums`}>{money(p.costPrice)}</td><td className={TD}>—</td></tr>
          <tr><td className={TD}>Shop price (tier 1, sent to Shopify)</td><td className={`${TD} tabular-nums`}>{money(p.sellPrice)}</td><td className={`${TD} tabular-nums`}>{margin(p.sellPrice)}</td></tr>
          <tr><td className={TD}>Trade price (tier 2)</td><td className={`${TD} tabular-nums`}>{money(p.tradePrice)}</td><td className={`${TD} tabular-nums`}>{margin(p.tradePrice)}</td></tr>
          <tr><td className={TD}>Price tier 3</td><td className={`${TD} tabular-nums`}>{money(d.priceTier3)}</td><td className={`${TD} tabular-nums`}>{margin(d.priceTier3)}</td></tr>
          <tr><td className={TD}>Price tier 4</td><td className={`${TD} tabular-nums`}>{money(d.priceTier4)}</td><td className={`${TD} tabular-nums`}>{margin(d.priceTier4)}</td></tr>
        </tbody>
      </table>
    </Card>
  );
}

function StockSection({ p }: { p: Product }) {
  const { data: s, isLoading } = useQuery<ProductStock>({ queryKey: ["product-stock", p.id], queryFn: () => getProductStock(p.id) });
  if (isLoading || !s) return <p className="text-gray-400 text-sm">Loading…</p>;
  return <>
    <Card title="Stock by warehouse" flush testId="product-stock">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>{["Warehouse", "On hand", "Reserved", "Available"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {s.warehouses.length === 0 && <tr><td colSpan={4} className="px-3 py-6 text-center text-gray-400">Never held in stock</td></tr>}
          {s.warehouses.map(w => (
            <tr key={w.warehouseId}>
              <td className={TD}>{w.warehouseName}</td>
              <td className={`${TD} tabular-nums`}>{qty(w.onHand)}</td>
              <td className={`${TD} tabular-nums text-gray-600`}>{qty(w.reserved)}</td>
              <td className={`${TD} tabular-nums font-medium ${Number(w.available) < 0 ? "text-red-600" : ""}`}>{qty(w.available)}</td>
            </tr>
          ))}
        </tbody>
        {s.warehouses.length > 1 && (
          <tfoot className="border-t border-gray-200 font-semibold">
            <tr><td className={TD}>Total</td><td className={`${TD} tabular-nums`}>{qty(s.onHand)}</td><td className={`${TD} tabular-nums`}>{qty(s.reserved)}</td><td className={`${TD} tabular-nums`}>{qty(s.available)}</td></tr>
          </tfoot>
        )}
      </table>
    </Card>
    <Card title={`On order: ${qty(s.onOrderTotal)}`} flush>
      {s.onOrder.length === 0 ? <p className="p-4 text-sm text-gray-400">Not on any open purchase order</p> : (
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>{["Purchase order", "Supplier", "Status", "Still to come", "Required by"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {s.onOrder.map(o => (
              <tr key={o.purchaseOrderId}>
                <td className={`${TD} font-mono text-xs`}><Link href={`/purchase-orders/${o.purchaseOrderId}`} className="text-brand-700 hover:underline">{o.orderNumber}</Link></td>
                <td className={TD}>{o.supplierName}</td>
                <td className={`${TD} text-xs text-gray-600`}>{o.status}</td>
                <td className={`${TD} tabular-nums`}>{qty(o.outstanding)}</td>
                <td className={`${TD} text-xs text-gray-500`}>{o.requiredBy ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  </>;
}

function Suppliers({ p }: { p: Product }) {
  const d = p.details ?? {};
  return (
    <Card title="Suppliers" flush>
      {!p.defaultSupplierId ? <p className="p-4 text-sm text-gray-400">No supplier set — the reorder page can&apos;t raise a PO for it</p> : (
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>{["Supplier", "Supplier SKU", "Supplier's product name", "Supplier price", ""].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
          </thead>
          <tbody>
            <tr>
              <td className={TD}>{p.defaultSupplierName}</td>
              <td className={`${TD} font-mono text-xs`}>{p.supplierProductCode ?? "—"}</td>
              <td className={TD}>{d.supplierProductName ?? "—"}</td>
              <td className={`${TD} tabular-nums`}>{money(d.supplierPrice)}</td>
              <td className={`${TD} text-xs text-gray-500`}>Default</td>
            </tr>
          </tbody>
        </table>
      )}
    </Card>
  );
}

function ReorderLevels({ p }: { p: Product }) {
  const { data: s } = useQuery<ProductStock>({ queryKey: ["product-stock", p.id], queryFn: () => getProductStock(p.id) });
  const toReorder = s && Number(p.minStock ?? 0) + Number(p.reorderQuantity ?? 0) > 0
    && Number(s.available) + Number(s.onOrderTotal) <= Number(p.minStock ?? 0);
  return (
    <Card title="Reorder levels">
      <Grid>
        <Field label="Minimum before reorder" value={qty(p.minStock)} />
        <Field label="Reorder quantity" value={qty(p.reorderQuantity)} />
        <Field label="Available + on order" value={s ? qty(Number(s.available) + Number(s.onOrderTotal)) : "…"} />
      </Grid>
      {toReorder && (
        <p className="mt-4 text-sm text-amber-800 bg-amber-50 px-3 py-2 rounded">
          At or below its minimum: it is on the <Link href="/reorder" className="underline">reorder list</Link>.
        </p>
      )}
    </Card>
  );
}

function Movements({ p }: { p: Product }) {
  const [page, setPage] = useState(0);
  const { data } = useQuery<Page<StockMovement>>({
    queryKey: ["movements", "product", p.id, page],
    queryFn: () => getMovementsPage({ productId: p.id, page, size: 20 }),
  });
  return <>
    <Card title="Stock movements" flush>
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>{["Date", "Type", "Warehouse", "Quantity", "Reference", "Notes"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {(data?.content.length ?? 0) === 0 && <tr><td colSpan={6} className="px-3 py-6 text-center text-gray-400">No movements</td></tr>}
          {data?.content.map(m => (
            <tr key={m.id}>
              <td className={`${TD} text-xs text-gray-500`}><ClientDate iso={m.createdAt} showTime /></td>
              <td className={`${TD} text-xs`}>{m.type.replace(/_/g, " ").toLowerCase()}</td>
              <td className={TD}>{m.warehouseName}</td>
              <td className={`${TD} tabular-nums ${m.type.endsWith("_IN") ? "text-emerald-700" : "text-gray-800"}`}>{m.type.endsWith("_IN") ? "+" : "−"}{qty(m.quantity)}</td>
              <td className={`${TD} font-mono text-xs`}>{m.referenceId ?? "—"}</td>
              <td className={`${TD} text-xs text-gray-500`}>{m.notes ?? ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
    {data && <Pagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage} size={20} />}
  </>;
}

function Activity({ p }: { p: Product }) {
  const { data } = useQuery<Page<AuditLog>>({
    queryKey: ["audit", "PRODUCT", p.id],
    queryFn: () => getAuditLog({ entityType: "PRODUCT", entityId: p.id, size: 50 }),
  });
  const changes = (raw?: string) => {
    if (!raw) return "";
    try { return Object.entries(JSON.parse(raw)).map(([k, v]) => `${k}: ${v}`).join(" · "); } catch { return raw; }
  };
  return (
    <Card title="Activity log" flush testId="product-activity">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>{["When", "Who", "What", "Changes"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {(data?.content.length ?? 0) === 0 && <tr><td colSpan={4} className="px-3 py-6 text-center text-gray-400">No changes recorded yet</td></tr>}
          {data?.content.map(a => (
            <tr key={a.id}>
              <td className={`${TD} text-xs text-gray-500 whitespace-nowrap`}><ClientDate iso={a.createdAt} showTime /></td>
              <td className={TD}>{a.username ?? a.actorType}</td>
              <td className={`${TD} text-xs`}>{a.action.toLowerCase()}</td>
              <td className={`${TD} text-xs text-gray-600`}>{changes(a.changes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
