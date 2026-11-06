import AccountTransactionsPage from "./account-transactions-page";

// ?account=&from=&to= open the report straight away (links from the chart and the trial balance)
export default async function Page({ searchParams }: { searchParams: Promise<{ [key: string]: string | string[] | undefined }> }) {
  const p = await searchParams;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : "");
  return <AccountTransactionsPage key={JSON.stringify(p)} initialAccount={one(p.account)} initialFrom={one(p.from)} initialTo={one(p.to)} />;
}
