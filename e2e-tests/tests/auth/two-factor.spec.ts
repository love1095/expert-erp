import { createHmac } from 'node:crypto';
import { test, expect, logInAs } from '../../fixtures/test';
import { addUser, anonymousApi } from '../../fixtures/api';

// What an authenticator app shows for a base32 secret (RFC 6238: SHA-1, 30 s, 6 digits)
function totp(secret: string, offset = 0): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of secret.replace(/=+$/, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g)!.map(b => parseInt(b, 2)));
  const step = Math.floor(Date.now() / 1000 / 30) + offset;
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(step));
  const h = createHmac('sha1', key).update(msg).digest();
  const o = h[h.length - 1] & 0x0f;
  return String(((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000)).padStart(6, '0');
}

// Phase I5 (spec §8): two-step sign-in with an authenticator app, like Cin7's two-factor authentication
test.describe('Two-step sign-in', () => {
  test('TWOFA-001 a user turns it on in My account; the password then asks for the code; a wrong code is refused',
    { tag: ['@P0', '@auth', '@security'] }, async ({ browser, api, baseURL }) => {
      const session = await addUser(api, 'STAFF');
      const ctx = await browser.newContext();
      const page = await ctx.newPage();
      await logInAs(page, session);

      await page.goto('/account');
      await page.getByRole('button', { name: 'Turn on two-step sign-in' }).click();
      const secret = (await page.getByTestId('setup-key').textContent())!.trim();
      await page.getByLabel('Code from the app').fill(totp(secret));
      await page.getByRole('button', { name: 'Turn on', exact: true }).click();
      await expect(page.getByTestId('recovery-codes').locator('li')).toHaveCount(8);
      await expect(page.getByTestId('two-factor-status')).toContainText('On');

      // The password alone gives no session any more
      const anon = await anonymousApi(baseURL!);
      const res = await (await anon.post('/api/auth/login', { data: { username: session.user.username, password: session.password } })).json();
      expect(res.token).toBeUndefined();
      expect(res.twoFactor).toBe('CODE');
      expect((await anon.get('/api/auth/me', { headers: { Authorization: `Bearer ${res.challenge}` } })).status()).toBe(401);
      await anon.dispose();

      // Signing in on the page: password, then the code
      await ctx.clearCookies();
      await page.evaluate(() => localStorage.clear());
      await page.goto('/login');
      await page.getByLabel('Username').fill(session.user.username);
      await page.getByLabel('Password').fill(session.password);
      await page.getByRole('button', { name: 'Sign in' }).click();
      await expect(page.getByTestId('two-factor')).toBeVisible();
      await page.getByLabel('Code').fill('000000');
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(page.getByRole('alert')).toContainText('not right');
      await page.getByLabel('Code').fill(totp(secret, 1));
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(page).toHaveURL(/\/$/);
      await ctx.close();
    });
});
