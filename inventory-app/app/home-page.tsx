import Link from "next/link";

const cards = [
  { title: "Products", desc: "Manage your product catalog", href: "/products", color: "bg-indigo-50 text-indigo-700" },
  { title: "Warehouses", desc: "Manage warehouse locations", href: "/warehouses", color: "bg-emerald-50 text-emerald-700" },
  { title: "Stock Movements", desc: "Record IN / OUT movements", href: "/stock-movements", color: "bg-amber-50 text-amber-700" },
  { title: "Stock Overview", desc: "Current stock levels per product/warehouse", href: "/stock-overview", color: "bg-sky-50 text-sky-700" },
];

export default function DashboardPage() {
  return (
    <div>
      <h1 className="text-2xl font-bold mb-1">Dashboard</h1>
      <p className="text-gray-500 mb-8 text-sm">Welcome to Expert ERP — Inventory Management</p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
        {cards.map((c) => (
          <Link
            key={c.href}
            href={c.href}
            className="block bg-white border border-gray-200 rounded-xl p-6 hover:shadow-md transition-shadow"
          >
            <span className={`inline-block text-xs font-semibold px-2 py-0.5 rounded ${c.color} mb-3`}>
              {c.title}
            </span>
            <p className="text-gray-600 text-sm">{c.desc}</p>
          </Link>
        ))}
      </div>
    </div>
  );
}
