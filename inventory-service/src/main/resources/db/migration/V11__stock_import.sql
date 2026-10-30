-- Stock on hand imported from Cin7 (opening balances), and the cost of stock coming in.
--
-- unit_cost: cost of one unit on an inbound movement (FIFO cost layers will be built from these, oldest first);
-- empty on outbound movements and on older rows.
ALTER TABLE stock_movements ADD COLUMN unit_cost numeric(18,4);

-- STOCK_IMPORT: adjustments that bring stock to the quantities in an imported stock file
ALTER TABLE stock_movements DROP CONSTRAINT IF EXISTS stock_movements_reference_type_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_reference_type_check
    CHECK (reference_type IN ('PURCHASE_ORDER', 'SALE_ORDER', 'SALE_RETURN',
                              'TRANSFER', 'ADJUSTMENT', 'MANUAL', 'STOCK_IMPORT'));
