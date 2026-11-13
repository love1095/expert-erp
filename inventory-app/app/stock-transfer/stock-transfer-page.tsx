"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { createTransfer, getWarehouses } from "@/lib/api";
import ProductPicker from "@/app/sale-orders/product-picker";
import { Warehouse, StockMovement } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import { useConfirm } from "@/components/ConfirmDialog";

export default function StockTransferPage() {
  const ask = useConfirm();
  const qc = useQueryClient();

  // Review R5: the product is searched as you type (20 at a time), not picked from a list of every product
  const [pickerKey, setPickerKey] = useState(0);
  const [form, setForm] = useState({ productId: "", fromWarehouseId: "", toWarehouseId: "", quantity: "", notes: "" });
  const [result, setResult] = useState<StockMovement[] | null>(null);
  const [error, setError] = useState("");

  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });

  const mutation = useMutation({
    mutationFn: createTransfer,
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ["stock-overview"] });
      qc.invalidateQueries({ queryKey: ["movements"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      setResult(data);
      setForm({ productId: "", fromWarehouseId: "", toWarehouseId: "", quantity: "", notes: "" });
      setPickerKey(k => k + 1);
      setError("");
    },
    onError: (e: any) => setError(e.response?.data?.error || "Transfer failed"),
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setResult(null);
    if (!(await ask({ title: "Transfer the stock?", message: "It leaves one warehouse and arrives in the other.",
      details: `${form.quantity} unit${form.quantity === "1" ? "" : "s"}`, action: "Transfer" }))) return;
    mutation.mutate({
      productId: Number(form.productId),
      fromWarehouseId: Number(form.fromWarehouseId),
      toWarehouseId: Number(form.toWarehouseId),
      quantity: Number(form.quantity),
      notes: form.notes,
    });
  };

  return (
    <div className="max-w-xl">
      <h1 className="text-2xl font-bold mb-1">Stock Transfer</h1>
      <p className="text-gray-500 text-sm mb-6">Move stock between warehouses — creates TRANSFER_OUT + TRANSFER_IN atomically</p>

      <form data-confirm="own" onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
        {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

        {result && (
          <div className="bg-emerald-50 border border-emerald-200 rounded-lg px-4 py-3 text-sm text-emerald-800">
            Transfer complete — ref <span className="font-mono font-bold">{result[0]?.referenceId}</span>
          </div>
        )}

        <div>
          <p className="block text-xs font-medium text-gray-600 mb-1">Product *</p>
          <ProductPicker key={pickerKey} label="Product" mode="purchase" allowAdd={false} placeholder="Type a SKU, name or barcode"
            onSelect={p => setForm(f => ({ ...f, productId: String(p.id) }))} />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label htmlFor="stock-transfer-from-warehouse" className="block text-xs font-medium text-gray-600 mb-1">From Warehouse *</label>
            <select id="stock-transfer-from-warehouse"
              value={form.fromWarehouseId}
              onChange={(e) => setForm({ ...form, fromWarehouseId: e.target.value })}
              required
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400"
            >
              <option value="">Select</option>
              {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="stock-transfer-to-warehouse" className="block text-xs font-medium text-gray-600 mb-1">To Warehouse *</label>
            <select id="stock-transfer-to-warehouse"
              value={form.toWarehouseId}
              onChange={(e) => setForm({ ...form, toWarehouseId: e.target.value })}
              required
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400"
            >
              <option value="">Select</option>
              {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>
        </div>

        <div>
          <label htmlFor="stock-transfer-quantity" className="block text-xs font-medium text-gray-600 mb-1">Quantity *</label>
          <input id="stock-transfer-quantity"
            type="number" min="0.0001" step="any"
            value={form.quantity}
            onChange={(e) => setForm({ ...form, quantity: e.target.value })}
            required
            placeholder="e.g. 50"
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400"
          />
        </div>

        <div>
          <label htmlFor="stock-transfer-notes" className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
          <input id="stock-transfer-notes"
            value={form.notes}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}
            placeholder="Optional reason"
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400"
          />
        </div>

        <button
          type="submit"
          disabled={mutation.isPending}
          className="w-full bg-brand-600 text-white py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50"
        >
          {mutation.isPending ? "Processing..." : "Transfer Stock"}
        </button>
      </form>
    </div>
  );
}
