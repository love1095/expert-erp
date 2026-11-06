-- Phase K5: Xero's Adviser as a permission, and an "Accountant" starting role for organisations that have roles already
INSERT INTO access_roles (tenant_id, name, permissions)
SELECT DISTINCT r.tenant_id, 'Accountant',
       'ACCOUNTING,ADVISER,REPORTS_FINANCIAL,REPORTS_SALE,REPORTS_PURCHASE,REPORTS_INVENTORY,SALE_VIEW,SALE_PROFIT,PURCHASE_VIEW,PURCHASE_PRICES,PRODUCT_VIEW,PRODUCT_COST,STOCK_VALUE,DASHBOARD,AUDIT_LOG'
  FROM access_roles r
ON CONFLICT DO NOTHING;
