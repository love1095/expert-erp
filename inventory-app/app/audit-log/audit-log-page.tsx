"use client";

import { useQuery } from "@tanstack/react-query";
import { getAuditLog } from "@/lib/api";
import { AuditLog } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import Pagination from "@/components/Pagination";

const ACTION_COLOR: Record<string, string> = {
  CREATE:  "bg-emerald-100 text-emerald-700",
  RECEIVE: "bg-blue-100 text-blue-700",
  FULFILL: "bg-indigo-100 text-indigo-700",
  CONFIRM: "bg-sky-100 text-sky-700",
  RETURN:  "bg-teal-100 text-teal-700",
  CANCEL:  "bg-gray-100 text-gray-500",
  CANCEL_REMAINING: "bg-gray-100 text-gray-500",
};

export default function AuditLogPage() {
  const [entityType, setEntityType] = useState("");
  const [action, setAction]         = useState("");
  const [username, setUsername]     = useState("");
  const [page, setPage]             = useState(0);

  const { data, isLoading } = useQuery({
    queryKey: ["audit-log", entityType, action, username, page],
    queryFn: () => getAuditLog({ entityType: entityType || undefined, action: action || undefined, username: username || undefined, page }),
  });

  const entries: AuditLog[] = data?.content ?? [];
  const totalPages: number   = data?.totalPages ?? 0;
  const totalElements: number | undefined = data?.totalElements;

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Audit Log</h1>
        <p className="text-gray-500 text-sm mt-0.5">Full trail of every create, update and status change</p>
      </div>

      <div className="flex gap-3 mb-5 flex-wrap">
        <select value={entityType} onChange={e => { setEntityType(e.target.value); setPage(0); }}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 w-44">
          <option value="">All entities</option>
          {["CUSTOMER", "PURCHASE_ORDER", "SALE_ORDER", "STOCK_MOVEMENT"].map(t => (
            <option key={t} value={t}>{t.replace("_", " ")}</option>
          ))}
        </select>

        <select value={action} onChange={e => { setAction(e.target.value); setPage(0); }}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 w-36">
          <option value="">All actions</option>
          {["CREATE", "CONFIRM", "RECEIVE", "FULFILL", "RETURN", "CANCEL", "CANCEL_REMAINING"].map(a => (
            <option key={a} value={a}>{a}</option>
          ))}
        </select>

        <input value={username} onChange={e => { setUsername(e.target.value); setPage(0); }}
          placeholder="Filter by user..."
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 w-48" />
      </div>

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Entity", "ID", "Action", "User", "Changes", "When"].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {entries.length === 0 && (
                  <tr><td colSpan={6} className="px-4 py-8 text-center text-gray-400">No audit entries found</td></tr>
                )}
                {entries.map(e => (
                  <tr key={e.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-xs font-mono text-gray-500">{e.entityType.replace("_", " ")}</td>
                    <td className="px-4 py-3 text-xs font-mono text-gray-400">{e.entityId ?? "—"}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${ACTION_COLOR[e.action] ?? "bg-orange-100 text-orange-700"}`}>
                        {e.action}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-600">
                      {e.actorId ?? e.username ?? "—"}
                      {e.actorType && e.actorType !== "USER" && (
                        <span className="ml-1.5 text-[10px] font-semibold px-1.5 py-0.5 rounded bg-gray-100 text-gray-500">{e.actorType}</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-400 max-w-xs truncate font-mono">{e.changes ?? "—"}</td>
                    <td className="px-4 py-3 text-xs text-gray-400"><ClientDate iso={e.createdAt} showTime /></td>
                  </tr>
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
