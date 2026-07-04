.PHONY: up down test seed recon agent-eval bench

N    ?= 10000
SEED ?= 42
ENGINE_URL ?= http://localhost:8080

up:
	docker-compose up -d

down:
	docker-compose down

test:
	cd engine && mvn verify -q
	cd agent && pip install -e . -q && python -m pytest tests/ -q
	cd dashboard && npm ci --silent && npm run build --silent

seed:
	@echo "Seeding: N=$(N)  SEED=$(SEED)"
	@curl -s -X POST "$(ENGINE_URL)/api/batches/simulate?n=$(N)&seed=$(SEED)" \
	  -H "Content-Type: application/json" | python3 -m json.tool

recon:
	@echo "not yet implemented (Phase 4)"

agent-eval:
	@echo "not yet implemented (Phase 6)"

bench:
	@echo "not yet implemented (Phase 7)"
