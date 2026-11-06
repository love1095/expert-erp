import CallbackPage from "./callback-page";

// HMRC sends the user back here with ?code=&state= after granting access (or ?error= when they did not)
export default async function Page({ searchParams }: { searchParams: Promise<{ [key: string]: string | string[] | undefined }> }) {
  const p = await searchParams;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : "");
  return <CallbackPage code={one(p.code)} state={one(p.state)} error={one(p.error_description) || one(p.error)} />;
}
