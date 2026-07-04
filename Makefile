.PHONY: up down test seed recon agent-eval bench

N        ?= 10000
SEED     ?= 42
MODE     ?= rules
BATCH_ID ?=
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
	@if [ -z "$(BATCH_ID)" ]; then echo "Usage: make recon BATCH_ID=<id>"; exit 1; fi
	@echo "Reconciling batch $(BATCH_ID)..."
	@curl -s -X POST "$(ENGINE_URL)/api/batches/$(BATCH_ID)/reconcile" \
	  -H "Content-Type: application/json" | python3 -m json.tool

agent-eval:
	@if [ -z "$(BATCH_ID)" ]; then echo "Usage: make agent-eval BATCH_ID=<id> [MODE=rules|llm]"; exit 1; fi
	@echo "Running agent eval: batch=$(BATCH_ID) mode=$(MODE)"
	cd agent && python -m reconai.eval \
	  --batch-id $(BATCH_ID) \
	  --mode $(MODE) \
	  --out-dir ../benchmarks \
	  --verbose

bench:
	@echo "Running full benchmark pipeline: N=$(N) SEED=$(SEED)"
	@bash benchmarks/run_bench.sh $(N) $(SEED)
