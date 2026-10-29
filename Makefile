.PHONY: up down logs tunnel tunnel-down

up:
	docker compose up --build

down:
	docker compose down

logs:
	docker compose logs -f inventory-service

# Public https address for Shopify webhooks while developing (a new address every time it starts).
# Only POST /api/webhooks/shopify is reachable through it - see infra/webhook-gateway.conf
tunnel:
	docker compose --profile shopify-dev up -d webhook-gateway tunnel
	@for i in $$(seq 1 30); do \
	  url=$$(docker compose logs tunnel 2>&1 | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' | tail -1); \
	  if [ -n "$$url" ]; then \
	    echo "Public address: $$url"; \
	    echo "Enter it on Settings > Integrations > Shopify, then click Register webhooks."; exit 0; \
	  fi; sleep 2; \
	done; echo "No address yet - check: docker compose logs tunnel"; exit 1

tunnel-down:
	docker compose --profile shopify-dev stop tunnel webhook-gateway
