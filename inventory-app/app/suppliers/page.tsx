import SuppliersPage from "./suppliers-page";

// ?q= opens the list searched (the top bar search links suppliers here)
export default async function Page({ searchParams }: { searchParams: Promise<{ [key: string]: string | string[] | undefined }> }) {
  const q = (await searchParams).q;
  const initial = typeof q === "string" ? q : "";
  return <SuppliersPage key={initial} initialSearch={initial} />;
}
