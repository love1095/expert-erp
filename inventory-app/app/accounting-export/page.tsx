import { redirect } from "next/navigation";

// The Xero CSV export moved into Settings → Integrations → Xero (Accounts and CSV files)
export default function Page() {
  redirect("/settings/integrations/xero?section=accounts");
}
