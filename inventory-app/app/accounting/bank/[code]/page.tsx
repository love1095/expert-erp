import ReconcilePage from "./reconcile-page";

export default async function Page({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return <ReconcilePage code={decodeURIComponent(code)} />;
}
