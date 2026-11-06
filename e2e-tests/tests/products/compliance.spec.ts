import { test, expect } from '../../fixtures/test';

// Phase P1 / P4: safety compliance per SKU with its certificate, the expiring certificates report, product images
test.describe('Product compliance', () => {
  test('COMPLY-001 a SKU gets its standards and certificate, shows in the expiring report, and an image is uploaded',
    { tag: ['@P1', '@products'] }, async ({ page, api }) => {
      const product = await api.createProduct();
      const other = await api.createProduct();
      const soon = new Date(Date.now() + 20 * 86400000).toISOString().slice(0, 10);

      await page.goto(`/products/${product.id}`);
      await page.getByRole('navigation', { name: 'Product sections' }).getByRole('button', { name: 'Compliance' }).click();
      const card = page.getByTestId('compliance-card');
      await card.getByLabel('Standards').fill('EN 388:2016+A1:2018');
      await card.getByLabel('CE marked').check();
      await card.getByLabel('Protection class').fill('Category II');
      await card.getByLabel('Certificate number').fill('CE-E2E-1');
      await card.getByLabel('Certificate issuer').fill('SATRA');
      await card.getByLabel('Certificate expiry').fill(soon);
      await card.getByRole('button', { name: 'Save compliance' }).click();
      await expect(card.getByRole('status')).toHaveText('Saved');
      await expect(card.getByTestId('expiry-badge')).toHaveText('Expires in 20 days');
      await card.getByLabel('Certificate file').setInputFiles({ name: 'certificate.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 e2e') });
      await expect(card.getByTestId('certificate-file')).toHaveText('certificate.pdf');

      // Images and files: a PNG image
      await page.getByRole('navigation', { name: 'Product sections' }).getByRole('button', { name: 'Images and files' }).click();
      const png = Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d4944415478da63f8cfc0f01f0005000201a3d5c1e60000000049454e44ae426082', 'hex');
      await page.getByLabel('Product file').setInputFiles({ name: 'front.png', mimeType: 'image/png', buffer: png });
      await expect(page.getByTestId('product-images')).toContainText('front.png');

      // The report lists it; the other product (no certificate) is not there
      await page.goto('/certificates');
      await page.getByLabel('Days').selectOption('30');
      const report = page.getByTestId('expiring-certificates');
      await expect(report).toContainText(product.sku);
      await expect(report).toContainText('CE-E2E-1');
      await expect(report).toContainText('Attached');
      await expect(report).not.toContainText(other.sku);
    });
});
