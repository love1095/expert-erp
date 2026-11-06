-- Phase I3: Cin7's report groups as permissions; the starting roles get them like the defaults do
UPDATE access_roles SET permissions = permissions || ',REPORTS_SALE,REPORTS_PURCHASE,REPORTS_INVENTORY' WHERE name = 'Manager';
UPDATE access_roles SET permissions = permissions || ',REPORTS_SALE,REPORTS_PURCHASE' WHERE name = 'Staff';
