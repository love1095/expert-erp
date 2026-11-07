"use client";

import { useQuery } from "@tanstack/react-query";
import { getProductUnits } from "@/lib/api";
import { ProductUnit } from "@/types";

/**
 * Phase P3: the unit an order line is in — the product's own unit, or one of its extra units (Box of 100…).
 * Shows nothing for a product without extra units.
 */
export default function UnitSelect({ productId, value, onChange, label }: {
  productId?: number; value: string; onChange: (unitName: string) => void; label: string;
}) {
  const { data: units = [] } = useQuery<ProductUnit[]>({ queryKey: ["product-units", productId], queryFn: () => getProductUnits(productId!), enabled: !!productId });
  if (!productId || units.length === 0) return null;
  return (
    <select aria-label={label} value={value} onChange={e => onChange(e.target.value)}
            className="mt-1 w-full border border-gray-300 rounded-md px-2 py-1 text-xs text-gray-700">
      <option value="">Own unit</option>
      {units.map(u => <option key={u.id} value={u.name}>{u.name} (× {Number(u.factor).toLocaleString("en-GB")})</option>)}
    </select>
  );
}
