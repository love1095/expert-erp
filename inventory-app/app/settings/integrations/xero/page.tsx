import XeroPage from "./xero-page";

export default async function Page({ searchParams }: { searchParams: Promise<{ [key: string]: string | string[] | undefined }> }) {
  const p = await searchParams;
  return <XeroPage section={typeof p.section === "string" ? p.section : undefined} />;
}
