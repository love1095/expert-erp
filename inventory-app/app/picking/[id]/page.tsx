import PickListPage from "./pick-list-page";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <PickListPage id={Number(id)} />;
}
