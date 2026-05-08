"use client";

import { useEffect, useState } from "react";

export function ClientDate({ iso, showTime = false }: { iso: string; showTime?: boolean }) {
  const [label, setLabel] = useState("");

  useEffect(() => {
    const d = new Date(iso);
    setLabel(showTime ? d.toLocaleString() : d.toLocaleDateString());
  }, [iso, showTime]);

  return <span>{label || "—"}</span>;
}
