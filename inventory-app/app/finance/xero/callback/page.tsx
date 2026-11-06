import CallbackPage from "./callback-page";

// Xero sends the user back here with ?code=&state= (the fake Xero comes straight here)
export default async function Page({ searchParams }: { searchParams: Promise<{ [key: string]: string | string[] | undefined }> }) {
  const p = await searchParams;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : "");
  return <CallbackPage code={one(p.code)} state={one(p.state)} error={one(p.error_description) || one(p.error)} />;
}
