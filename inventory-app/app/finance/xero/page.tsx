import { redirect } from "next/navigation";

// Xero moved next to the other integrations: Settings → Integrations → Xero
export default function Page() {
  redirect("/settings/integrations/xero");
}
