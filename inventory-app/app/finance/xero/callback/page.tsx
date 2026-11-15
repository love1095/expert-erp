import CallbackPage from "@/app/settings/integrations/xero/callback/callback-page";

// The old return address (a Xero app still set to /finance/xero/callback keeps working); the new one is
// /settings/integrations/xero/callback
export default async function Page({ searchParams }: { searchParams: Promise<{ [key: string]: string | string[] | undefined }> }) {
  const p = await searchParams;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : "");
  return <CallbackPage code={one(p.code)} state={one(p.state)} error={one(p.error_description) || one(p.error)} />;
}
