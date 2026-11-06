import StockTakePage from "./stock-take-page";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <StockTakePage id={Number(id)} />;
}
