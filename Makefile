.PHONY: up down test seed recon agent-eval bench

up:
	docker-compose up -d

down:
	docker-compose down

test:
	cd engine && mvn verify -q
	cd agent && pip install -e . -q && pytest tests/ -q
	cd dashboard && npm ci --silent && npm run build --silent

seed:
	@echo "not yet implemented (Phase 2)"

recon:
	@echo "not yet implemented (Phase 4)"

agent-eval:
	@echo "not yet implemented (Phase 6)"

bench:
	@echo "not yet implemented (Phase 7)"
