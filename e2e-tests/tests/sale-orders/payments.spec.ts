import { test, expect } from '../../fixtures/test';

const day = (offset: number) => {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

// Phase H1 (spec §6): payments with day, payment account and reference; overpayment → customer credit; void; aged debtors
test.describe('Sales ledger', () => {
  test('LEDGER-001 an overpayment becomes customer credit that pays the next invoice; a manager voids a payment', { tag: ['@P1', '@invoices'] },
    async ({ page, api }) => {
      await api.http.put('/api/settings/payment-accounts', { data: [
        { code: '650', name: 'Current Account - RBS', kind: 'BANK', active: true, defaultAccount: true },
        { code: '643', name: 'Card Payments', kind: 'CARD', active: true, defaultAccount: false },
      ] });
      const customer = await api.createCustomer({ name: `Ledger ${Date.now()}` });
      const invoice = async (amount: number, issued: number, due: number) => {
        const inv = await (await api.http.post('/api/invoices', { data: { type: 'SALES', partyName: customer.name, customerId: customer.id,
          issueDate: day(issued), dueDate: day(due), currency: 'GBP', items: [{ description: 'Hi-vis vests', quantity: 1, unitPrice: amount }] } })).json();
        expect((await api.http.post(`/api/invoices/${inv.id}/issue`)).ok()).toBe(true);
        return inv;
      };
      const first = await invoice(50, -40, -10);

      // £80 by card, two days ago, against £50 due: £30 goes on the customer's credit
      await page.goto(`/invoices/${first.id}`);
      const pay = page.getByTestId('record-payment');
      await pay.getByLabel('Amount').fill('80');
      await expect(pay).toContainText('£30.00 over: kept as the customer\'s credit');
      await pay.getByLabel('Date paid').fill(day(-2));
      await pay.getByLabel('Payment account').selectOption({ label: '643: Card Payments' });
      await pay.getByLabel('Reference').fill('Card 4417');
      await pay.getByRole('button', { name: 'Record payment' }).click();
      const payment = page.locator('[data-testid^="payment-"]');
      await expect(payment).toContainText('643: Card Payments');
      await expect(payment).toContainText('Card 4417');
      await expect(payment).toContainText('50.00');
      await expect(page.getByText('Settled')).toBeVisible();

      // The next invoice is paid from that credit
      const second = await invoice(20, 0, 30);
      await page.goto(`/invoices/${second.id}`);
      await page.getByTestId('use-customer-credit').click();
      await expect(page.getByText('Settled')).toBeVisible();
      await page.goto(`/customers/${customer.id}`);
      await expect(page.getByTestId('customer-credit-available')).toHaveText('£10.00');

      // A manager voids the card payment: the first invoice is due again, the void is kept with its reason
      await page.goto(`/invoices/${first.id}`);
      page.removeAllListeners('dialog');
      page.once('dialog', d => d.accept('Card payment was declined'));
      await page.locator('[aria-label^="Void payment"]').click();
      await expect(page.getByText(/GBP 50\.00 due/)).toBeVisible();
      await expect(page.getByText('1 voided')).toBeVisible();

      // Aged debtors: £50 ten days overdue, less £10 credit = £40
      await page.goto('/aged-debtors');
      const row = page.getByTestId(`aged-row-${customer.id}`);
      await expect(row).toContainText('£50.00');
      await expect(row).toContainText('-£10.00');
      await expect(row).toContainText('£40.00');
    });
});
