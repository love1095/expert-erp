import { test, expect } from '../../fixtures/test';
import { uid } from '../../fixtures/api';

/**
 * Review R2 / rule D1: list pages only list. "+ New" opens a page of its own (/x/new), saving opens the record's page,
 * and Edit happens on the record's page.
 */
const NEW_PAGES: { list: string; link: string; heading: string }[] = [
  { list: '/sale-orders', link: '+ New SO', heading: 'New sale' },
  { list: '/purchase-orders', link: '+ New PO', heading: 'New purchase order' },
  { list: '/products', link: '+ Add Product', heading: 'New product' },
  { list: '/customers', link: '+ New Customer', heading: 'New customer' },
  { list: '/suppliers', link: '+ New Supplier', heading: 'New supplier' },
  { list: '/invoices', link: '+ New Invoice', heading: 'New invoice' },
  { list: '/accounting/manual-journals', link: '+ New journal', heading: 'New manual journal' },
];

test.describe('New records on their own pages', () => {
  test('NEW-001 every "+ New" opens its own page; Cancel and Back return to the list; no form on the list', { tag: ['@P1', '@layout'] },
    async ({ page }) => {
      for (const { list, link, heading } of NEW_PAGES) {
        await page.goto(list);
        // The record forms (sale-order-form, product-form...) are only on the /new and record pages
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        await expect(page.locator('main [data-testid$="-form"]')).toHaveCount(0);
        await page.getByRole('link', { name: link }).click();
        await expect(page).toHaveURL(new RegExp(`${list}/new$`));
        await expect(page.getByRole('heading', { level: 1 })).toContainText(heading);
        await page.getByRole('button', { name: 'Cancel' }).click();
        await expect(page).toHaveURL(new RegExp(`${list}$`));

        // The browser's Back works too: the new page has an address of its own
        await page.goto(`${list}/new`);
        await page.getByRole('link', { name: '‹ Back to list' }).click();
        await expect(page).toHaveURL(new RegExp(`${list}$`));
      }
    });

  test('NEW-002 a supplier is created on its own page, opens on its record page and is edited there', { tag: ['@P1', '@layout', '@purchase-orders'] },
    async ({ page, api }) => {
      const name = `Orn Clothing ${uid()}`;
      await page.goto('/suppliers/new');
      await page.getByLabel('Name *').fill(name);
      await page.getByLabel('Payment Term').fill('30 Day EOM');
      await page.getByLabel('Email').fill('sales@orn.test');
      await page.getByRole('button', { name: 'Create Supplier' }).click();

      await expect(page).toHaveURL(/\/suppliers\/\d+$/);
      const id = Number(page.url().split('/').pop());
      const record = page.getByTestId(`supplier-page-${id}`);
      await expect(record).toContainText(name);
      await expect(record).toContainText('30 Day EOM');
      await expect(record).toContainText('sales@orn.test');

      // Edit from the list opens the supplier's page in edit mode
      await page.goto('/suppliers');
      await page.getByTestId(`supplier-row-${id}`).getByRole('link', { name: 'Edit' }).click();
      await expect(page).toHaveURL(new RegExp(`/suppliers/${id}\\?edit=1$`));
      await page.getByLabel('Payment Term').fill('14 days');
      await page.getByRole('button', { name: 'Save Supplier' }).click();
      await expect(record).toContainText('14 days');
      const saved = await (await api.http.get(`/api/suppliers/${id}`)).json();
      expect(saved).toMatchObject({ name, paymentTerm: '14 days', email: 'sales@orn.test' });

      // A click on the row opens the same page
      await page.goto('/suppliers');
      await page.getByTestId(`supplier-row-${id}`).getByRole('link', { name }).click();
      await expect(page).toHaveURL(new RegExp(`/suppliers/${id}$`));
    });

  test('NEW-003 a hand-typed invoice is made on its own page and opens on its record page', { tag: ['@P2', '@layout', '@invoices'] },
    async ({ page }) => {
      await page.goto('/invoices/new');
      const form = page.getByTestId('invoice-form');
      await form.getByLabel('Customer').fill(`Walk-in ${uid()}`);
      await form.getByPlaceholder('Description').fill('Site visit');
      await form.getByPlaceholder('Qty').fill('1');
      await form.getByPlaceholder('Unit price').fill('80');
      await form.getByRole('button', { name: 'Create Invoice' }).click();
      await expect(page).toHaveURL(/\/invoices\/\d+$/);
      await expect(page.locator('main')).toContainText('Site visit');
    });
});
