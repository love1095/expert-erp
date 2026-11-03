-- Phase G1: like Cin7, a sale order has its own price tier, the customer's by default and changeable per order.
-- Lines without a typed price are priced at it (a contract price for the customer still wins).
ALTER TABLE sale_orders ADD COLUMN price_tier varchar(100);
