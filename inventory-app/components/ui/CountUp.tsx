"use client";

import { useEffect, useRef, useState } from "react";

/**
 * A figure that counts up once when it first appears (dashboard tiles, review R8); later changes show at once.
 * No counting when the device asks for less motion.
 */
export function CountUp({ value, format = v => Math.round(v).toLocaleString(), duration = 450 }: {
  value: number; format?: (v: number) => string; duration?: number;
}) {
  const [shown, setShown] = useState(0);
  const done = useRef(false);
  useEffect(() => {
    if (done.current || typeof window === "undefined" || window.matchMedia("(prefers-reduced-motion: reduce)").matches || !value) {
      done.current = true; setShown(value); return;
    }
    done.current = true;
    const start = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const p = Math.min(1, (now - start) / duration);
      setShown(value * (1 - Math.pow(1 - p, 3)));
      if (p < 1) raf = requestAnimationFrame(tick); else setShown(value);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value, duration]);
  return <>{format(shown)}</>;
}
