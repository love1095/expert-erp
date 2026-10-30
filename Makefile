.PHONY: up down logs backend app e2e tunnel tunnel-down

# Backend in the background (rebuilt from the current code); returns once the API answers on :8080
backend:
	docker compose up -d --build postgres inventory-service
	@printf "Waiting for the API on :8080"; \
	for i in $$(seq 1 90); do \
	  code=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/error); \
	  if [ "$$code" != "000" ]; then echo " - ready"; exit 0; fi; printf "."; sleep 2; \
	done; echo " - not up after 3 min, check: make logs"; exit 1

# Frontend dev server on :3000 (needs `make backend`)
app:
	cd inventory-app && npm run dev

# Playwright suite (needs `make backend` and `make app`); 2 workers so the machine doesn't freeze
e2e:
	cd e2e-tests && npx playwright test --workers=2 $(ARGS)

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
