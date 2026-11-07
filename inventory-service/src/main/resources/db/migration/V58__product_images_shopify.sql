-- Phase P4: which Shopify product an image was sent to (sent once per product; a new Shopify product gets them again)
ALTER TABLE product_files ADD COLUMN shopify_product_id varchar(100);
