"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getSubscription, getPlans, upgradePlan } from "@/lib/api";
import { SubscriptionInfo, Plan } from "@/types";
import { useAuth } from "@/contexts/AuthContext";

const TIER_COLOR: Record<string, string> = {
  FREE:       "bg-gray-100 text-gray-700",
  PRO:        "bg-indigo-100 text-indigo-700",
  ENTERPRISE: "bg-amber-100 text-amber-700",
};

const TIER_BORDER: Record<string, string> = {
  FREE:       "border-gray-200",
  PRO:        "border-indigo-400 ring-2 ring-indigo-200",
  ENTERPRISE: "border-amber-400 ring-2 ring-amber-100",
};

function UsageBar({ label, used, max }: { label: string; used: number; max: number }) {
  const unlimited = max < 0;
  const pct = unlimited ? 0 : Math.min(100, Math.round((used / max) * 100));
  const near = !unlimited && pct >= 80;
  const over = !unlimited && used >= max;

  return (
    <div>
      <div className="flex items-center justify-between text-sm mb-1">
        <span className="font-medium text-gray-700">{label}</span>
        <span className={`text-xs ${over ? "text-red-600 font-semibold" : near ? "text-amber-600" : "text-gray-500"}`}>
          {used.toLocaleString()} / {unlimited ? "∞" : max.toLocaleString()}
        </span>
      </div>
      {!unlimited && (
        <div className="h-2 rounded-full bg-gray-100 overflow-hidden">
          <div
            className={`h-full rounded-full transition-all ${over ? "bg-red-500" : near ? "bg-amber-400" : "bg-indigo-500"}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      )}
    </div>
  );
}

function PlanCard({ plan, current, canUpgrade, onSelect }: {
  plan: Plan;
  current: boolean;
  canUpgrade: boolean;
  onSelect: () => void;
}) {
  const isDowngrade = current; // simplification — current plan is neither upgrade nor downgrade
  return (
    <div className={`border rounded-xl p-5 flex flex-col gap-3 ${current ? TIER_BORDER[plan.tier] : "border-gray-200"}`}>
      <div className="flex items-center justify-between">
        <div>
          <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${TIER_COLOR[plan.tier]}`}>{plan.tier}</span>
          <p className="text-lg font-semibold mt-1">{plan.displayName}</p>
        </div>
        <div className="text-right">
          {plan.monthlyPrice === 0
            ? <p className="text-2xl font-bold text-gray-800">Free</p>
            : <><p className="text-2xl font-bold text-gray-800">${plan.monthlyPrice}</p><p className="text-xs text-gray-400">/ month</p></>
          }
        </div>
      </div>

      <ul className="text-sm text-gray-600 space-y-1.5">
        <li>Products: <span className="font-medium">{plan.maxProducts < 0 ? "Unlimited" : plan.maxProducts.toLocaleString()}</span></li>
        <li>Users: <span className="font-medium">{plan.maxUsers < 0 ? "Unlimited" : plan.maxUsers.toLocaleString()}</span></li>
        <li>Warehouses: <span className="font-medium">{plan.maxWarehouses < 0 ? "Unlimited" : plan.maxWarehouses.toLocaleString()}</span></li>
      </ul>

      {current ? (
        <span className="mt-auto inline-block text-center text-xs font-semibold text-indigo-600 bg-indigo-50 px-3 py-1.5 rounded-lg">
          Current plan
        </span>
      ) : (
        <button
          onClick={onSelect}
          disabled={!canUpgrade}
          className="mt-auto w-full text-sm font-semibold px-3 py-1.5 rounded-lg border border-indigo-400 text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          Switch to {plan.displayName}
        </button>
      )}
    </div>
  );
}

export default function SubscriptionPage() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";

  const { data: sub, isLoading } = useQuery<SubscriptionInfo>({
    queryKey: ["subscription"],
    queryFn: getSubscription,
  });

  const { data: plans = [] } = useQuery<Plan[]>({
    queryKey: ["plans"],
    queryFn: getPlans,
  });

  const mutation = useMutation({
    mutationFn: (tier: string) => upgradePlan(tier),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["subscription"] }),
  });

  if (isLoading) return <p className="text-gray-400 text-sm">Loading…</p>;
  if (!sub) return null;

  return (
    <div className="max-w-3xl">
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Subscription</h1>
        <p className="text-gray-500 text-sm mt-0.5">Manage your plan and usage limits</p>
      </div>

      {/* Current plan badge + usage */}
      <div className="bg-white border border-gray-200 rounded-xl p-6 mb-6">
        <div className="flex items-center gap-3 mb-5">
          <span className={`text-sm font-bold px-3 py-1 rounded-full ${TIER_COLOR[sub.planTier]}`}>
            {sub.planTier}
          </span>
          <span className="text-gray-500 text-sm">
            {sub.monthlyPrice === 0 ? "Free forever" : `$${sub.monthlyPrice}/month`}
          </span>
          <span className={`ml-auto text-xs px-2 py-0.5 rounded-full font-medium ${
            sub.status === "ACTIVE" ? "bg-emerald-50 text-emerald-700" : "bg-red-50 text-red-700"
          }`}>{sub.status}</span>
        </div>

        <div className="space-y-4">
          <UsageBar label="Products"   used={sub.productCount}   max={sub.maxProducts} />
          <UsageBar label="Users"      used={sub.userCount}      max={sub.maxUsers} />
          <UsageBar label="Warehouses" used={sub.warehouseCount} max={sub.maxWarehouses} />
        </div>
      </div>

      {/* Plan cards */}
      {isAdmin && (
        <>
          <h2 className="text-base font-semibold text-gray-700 mb-3">Available plans</h2>
          {mutation.isError && (
            <p className="text-sm text-red-600 mb-3">
              {(mutation.error as any)?.response?.data?.error ?? "Failed to switch plan"}
            </p>
          )}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            {plans.map(plan => (
              <PlanCard
                key={plan.id}
                plan={plan}
                current={plan.tier === sub.planTier}
                canUpgrade={isAdmin && plan.tier !== sub.planTier && !mutation.isPending}
                onSelect={() => mutation.mutate(plan.tier)}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
