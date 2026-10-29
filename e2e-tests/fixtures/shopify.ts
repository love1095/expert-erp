import { createHmac, randomUUID } from 'node:crypto';
import { APIResponse, request } from '@playwright/test';
import { ErpApi, uid } from './api';

/** Webhooks go straight to the backend, as they do in production (tunnel → gateway → backend). */
export const BACKEND_URL = process.env.E2E_BACKEND_URL ?? 'http://localhost:8080';

export interface FakeShop {
  domain: string;
  secret: string;
}

/**
 * Saves a Shopify connection for the test's tenant with a made-up store and secret. The connection test against
 * Shopify fails (the store does not exist), but the store and secret are saved, which is all webhooks need.
 */
export async function connectFakeShop(api: ErpApi): Promise<FakeShop> {
  const shop = { domain: `e2e-shop-${uid()}.myshopify.com`, secret: `shpss_e2e_${uid()}` };
  const res = await api.http.put('/api/integrations/shopify', {
    data: { shopDomain: shop.domain, clientId: 'e2e-client', clientSecret: shop.secret },
  });
  if (!res.ok()) throw new Error(`connect fake shop failed: HTTP ${res.status()} ${await res.text()}`);
  return shop;
}

/** Sends a webhook signed like Shopify does (HMAC-SHA256 of the raw body with the app's client secret). */
export async function sendWebhook(shop: FakeShop, topic: string, payload: unknown,
                                  opts: { eventId?: string; secret?: string } = {}): Promise<APIResponse> {
  const body = JSON.stringify(payload);
  const hmac = createHmac('sha256', opts.secret ?? shop.secret).update(body, 'utf8').digest('base64');
  const ctx = await request.newContext({ baseURL: BACKEND_URL });
  try {
    return await ctx.post('/api/webhooks/shopify', {
      data: body,
      headers: {
        'Content-Type': 'application/json',
        'X-Shopify-Shop-Domain': shop.domain,
        'X-Shopify-Topic': topic,
        'X-Shopify-Hmac-Sha256': hmac,
        'X-Shopify-Event-Id': opts.eventId ?? randomUUID(),
        'X-Shopify-Webhook-Id': randomUUID(),
      },
    });
  } finally {
    await ctx.dispose();
  }
}

/** A Shopify order in the webhook (REST) shape. */
export function shopifyOrder(id: number, lines: { sku: string | null; title: string; quantity: number; price: string }[],
                             opts: { financialStatus?: string; currency?: string; customer?: { id: number; first: string; last: string; email: string } } = {}) {
  return {
    id,
    name: `#${id}`,
    financial_status: opts.financialStatus ?? 'paid',
    currency: opts.currency ?? 'GBP',
    created_at: new Date().toISOString(),
    cancelled_at: null,
    email: opts.customer?.email ?? null,
    phone: null,
    customer: opts.customer
      ? { id: opts.customer.id, first_name: opts.customer.first, last_name: opts.customer.last, email: opts.customer.email, phone: null }
      : null,
    line_items: lines.map(l => ({ sku: l.sku, title: l.title, quantity: l.quantity, price: l.price, discount_allocations: [] })),
  };
}
