import Link from "next/link";

// Building blocks of the record pages (sale order, invoice, purchase order, customer), laid out like Cin7:
// a title row with actions, a left-hand trail of related documents and the record itself on the right.

export const BTN_PRIMARY = "bg-brand-600 text-white px-3 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50";
export const BTN_SECONDARY = "border border-gray-300 bg-white text-gray-700 px-3 py-2 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50";
export const BTN_DANGER = "border border-red-200 bg-white text-red-600 px-3 py-2 rounded-md text-sm font-medium hover:bg-red-50 disabled:opacity-50";

export function BackLink({ href }: { href: string }) {
  return <Link href={href} className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-800 mb-3">‹ Back to list</Link>;
}

// "Sale SO-265 for Tina Tinh" + badges on the left, actions on the right
export function RecordHeader({ title, subtitle, badges, actions }: {
  title: React.ReactNode; subtitle?: React.ReactNode; badges?: React.ReactNode; actions?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 mb-5">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">
          {title}{subtitle && <span className="font-normal text-gray-500"> {subtitle}</span>}
        </h1>
        {badges}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

// Left trail + main column
export function RecordLayout({ trail, children }: { trail: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 lg:grid-cols-[17rem_minmax(0,1fr)] gap-5 items-start">
      <aside className="space-y-4">{trail}</aside>
      <div className="space-y-4 min-w-0">{children}</div>
    </div>
  );
}

// A card of the left-hand trail (Cin7: Fulfillments, Invoices, Payments, C&R)
export function TrailCard({ title, badge, children, testId }: { title: string; badge?: React.ReactNode; children: React.ReactNode; testId?: string }) {
  return (
    <section data-testid={testId} className="bg-white border border-gray-200 rounded-lg p-4">
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-sm font-semibold text-gray-800">{title}</h3>
        {badge}
      </div>
      <div className="text-sm space-y-2">{children}</div>
    </section>
  );
}

// White card with a heading, for the main column
export function Card({ title, right, children, testId, flush }: {
  title?: string; right?: React.ReactNode; children: React.ReactNode; testId?: string; flush?: boolean;
}) {
  return (
    <section data-testid={testId} className="bg-white border border-gray-200 rounded-lg overflow-hidden">
      {title && (
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200">
          <h3 className="text-sm font-semibold text-gray-800">{title}</h3>
          {right}
        </div>
      )}
      <div className={flush ? "" : "p-4"}>{children}</div>
    </section>
  );
}

// Cin7's three header columns (e.g. Customer / Accounting / Shipping details)
export function DetailColumns({ columns, testId }: { columns: { title: string; fields: React.ReactNode }[]; testId?: string }) {
  return (
    <section data-testid={testId} className="bg-white border border-gray-200 rounded-lg p-5 grid grid-cols-1 md:grid-cols-3 gap-6">
      {columns.map(c => (
        <div key={c.title}>
          <h3 className="text-sm font-semibold text-gray-800 mb-3">{c.title}</h3>
          <dl className="space-y-2.5">{c.fields}</dl>
        </div>
      ))}
    </section>
  );
}

export function Field({ label, value, warn }: { label: string; value?: React.ReactNode; warn?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-gray-400">{label}</dt>
      <dd className={`text-sm ${warn ? "text-red-600 font-semibold" : "text-gray-800"}`}>{value || value === 0 ? value : "—"}</dd>
    </div>
  );
}

export const TH = "text-left px-3 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap";
export const TD = "px-3 py-2";
