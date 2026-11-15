import type { FormEvent } from "react";

/**
 * A quantity box: the browser's up / down arrows count 1, 2, 3 from an empty box (with min 0.0001 the first click gave
 * 0.0001, and typing "1" after it made 0.00011), decimals stay allowed, and 0 or less is refused with a message.
 * Use as {...QTY_INPUT} on an <input>.
 */
export const QTY_INPUT = {
  type: "number",
  min: "0",
  step: "any",
  onInput: (e: FormEvent<HTMLInputElement>) => {
    const el = e.currentTarget;
    el.setCustomValidity(el.value !== "" && Number(el.value) <= 0 ? "Enter a quantity above 0" : "");
  },
} as const;
