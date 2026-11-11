-- New purchase like Cin7: Supplier details (contact, phone, vendor address), Accounting details (order tax rule,
-- tax inclusive for later), Shipping details (order date, blind receipt, ship to a different company, shipping address).
ALTER TABLE purchase_orders ADD COLUMN contact_name      varchar(255);
ALTER TABLE purchase_orders ADD COLUMN contact_phone     varchar(100);
ALTER TABLE purchase_orders ADD COLUMN vendor_line1      varchar(255);
ALTER TABLE purchase_orders ADD COLUMN vendor_line2      varchar(255);
ALTER TABLE purchase_orders ADD COLUMN vendor_city       varchar(100);
ALTER TABLE purchase_orders ADD COLUMN vendor_state      varchar(100);
ALTER TABLE purchase_orders ADD COLUMN vendor_postcode   varchar(30);
ALTER TABLE purchase_orders ADD COLUMN vendor_country    varchar(100);
ALTER TABLE purchase_orders ADD COLUMN order_date        date;
ALTER TABLE purchase_orders ADD COLUMN tax_rule          varchar(100);
ALTER TABLE purchase_orders ADD COLUMN blind_receipt     boolean NOT NULL DEFAULT false;
ALTER TABLE purchase_orders ADD COLUMN ship_to_company   varchar(255);
ALTER TABLE purchase_orders ADD COLUMN shipping_line1    varchar(255);
ALTER TABLE purchase_orders ADD COLUMN shipping_line2    varchar(255);
ALTER TABLE purchase_orders ADD COLUMN shipping_city     varchar(100);
ALTER TABLE purchase_orders ADD COLUMN shipping_state    varchar(100);
ALTER TABLE purchase_orders ADD COLUMN shipping_postcode varchar(30);
ALTER TABLE purchase_orders ADD COLUMN shipping_country  varchar(100);
-- Orders so far: their order date is the day they were made
UPDATE purchase_orders SET order_date = CAST(created_at AS date) WHERE order_date IS NULL;

-- One address per supplier for now (Cin7 keeps a list: later, with the supplier address list)
ALTER TABLE suppliers ADD COLUMN address_line1    varchar(255);
ALTER TABLE suppliers ADD COLUMN address_line2    varchar(255);
ALTER TABLE suppliers ADD COLUMN address_city     varchar(100);
ALTER TABLE suppliers ADD COLUMN address_state    varchar(100);
ALTER TABLE suppliers ADD COLUMN address_postcode varchar(30);
ALTER TABLE suppliers ADD COLUMN address_country  varchar(100);

-- A warehouse's postal address: where purchases are shipped to
ALTER TABLE warehouses ADD COLUMN address_line1    varchar(255);
ALTER TABLE warehouses ADD COLUMN address_line2    varchar(255);
ALTER TABLE warehouses ADD COLUMN address_city     varchar(100);
ALTER TABLE warehouses ADD COLUMN address_state    varchar(100);
ALTER TABLE warehouses ADD COLUMN address_postcode varchar(30);
ALTER TABLE warehouses ADD COLUMN address_country  varchar(100);
