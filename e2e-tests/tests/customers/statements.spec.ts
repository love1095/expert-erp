import { test, expect } from '../../fixtures/test';

const day = (offset: number) => {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

// Phase G3 (spec §5): statements like Cin7's (Activity, Outstanding) with ageing; overdue reminders that get firmer
test.describe('Statements and overdue reminders', () => {
  test('STATEMENT-001 a customer statement adds up, ages what is open, and each recorded reminder is firmer', { tag: ['@P1', '@customers'] },
    async ({ page, api }) => {
      const customer = await api.createCustomer({ name: `Statement ${Date.now()}` });
      // Typed by hand: £50 invoiced 40 days ago, due 10 days ago, £10 paid; £20 due next month
      const invoice = async (amount: number, issued: number, due: number) => {
        const inv = await (await api.http.post('/api/invoices', { data: { type: 'SALES', partyName: customer.name, customerId: customer.id,
          issueDate: day(issued), dueDate: day(due), currency: 'GBP', items: [{ description: 'Site safety kit', quantity: 1, unitPrice: amount }] } })).json();
        expect((await api.http.post(`/api/invoices/${inv.id}/issue`)).ok()).toBe(true);
        return inv;
      };
      const old = await invoice(50, -40, -10);
      await invoice(20, -2, 28);
      expect((await api.http.post(`/api/invoices/${old.id}/pay`, { data: { amount: 10, method: 'BANK' } })).ok()).toBe(true);

      const pdf = await api.http.get(`/api/customers/${customer.id}/statement/pdf?type=OUTSTANDING`);
      expect(pdf.ok()).toBe(true);
      expect(pdf.headers()['content-type']).toContain('application/pdf');

      await page.goto(`/customers/${customer.id}`);
      const card = page.getByTestId('customer-statement');
      // Activity, last three months: both invoices and the payment, closing £60
      await expect(card.getByTestId('statement-entries')).toContainText(old.invoiceNumber);
      await expect(card.getByTestId('statement-entries')).toContainText('Payment');
      await expect(card.getByTestId('statement-closing')).toHaveText('£60.00');
      // Outstanding: £40 left on the old invoice, 10 days overdue, aged 1–30 days; £20 current
      await card.getByTestId('statement-type-outstanding').click();
      await expect(card.getByTestId('statement-open-items')).toContainText('£40.00');
      await expect(card.getByTestId('statement-closing')).toHaveText('£60.00');
      await expect(card.getByTestId('statement-ageing')).toContainText('1–30 days£40.00');
      await expect(card.getByTestId('statement-ageing')).toContainText('Current£20.00');

      // The statement prints in a new tab
      const statementTab = page.context().waitForEvent('page');
      await card.getByTestId('print-statement').click();
      await (await statementTab).close();

      // First reminder, printed and recorded: the next one is the second
      await expect(page.getByTestId('reminder-level')).toHaveText('1st reminder');
      const reminderTab = page.context().waitForEvent('page');
      await page.getByTestId('send-reminder').click();
      await (await reminderTab).close();
      await expect(page.getByTestId('reminder-level')).toHaveText('2nd reminder');

      // The Overdue list shows the customer with what is overdue and the next reminder
      await page.goto('/overdue');
      const row = page.getByTestId(`overdue-row-${customer.id}`);
      await expect(row).toContainText('£40.00');
      await expect(row).toContainText('£60.00');
      await expect(page.getByTestId(`overdue-next-${customer.id}`)).toHaveText('2nd reminder');
    });
});
