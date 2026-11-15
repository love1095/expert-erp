-- Review R3 point 5: the same returned goods could be put back twice (Return on the order, then a credit note with
-- "restock"). Each order line now remembers how many restocked returned units a credit note has covered, so a credit
-- note first takes what is already back and only the rest comes in as new stock.
ALTER TABLE sale_order_lines ADD COLUMN restock_credited_quantity numeric(18,4) NOT NULL DEFAULT 0;

-- Returns that credit notes made themselves so far are covered by those credit notes
UPDATE sale_order_lines l SET restock_credited_quantity = x.qty
  FROM (SELECT rl.sale_order_line_id AS line_id, SUM(rl.quantity) AS qty
          FROM sale_return_lines rl JOIN sale_returns r ON r.id = rl.sale_return_id
         WHERE rl.restock AND r.idempotency_key LIKE 'credit-note-%'
         GROUP BY rl.sale_order_line_id) x
 WHERE x.line_id = l.id;
