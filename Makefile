PROFILE ?= core
SVC ?=
SCRIPT ?= link-baseline
EXP ?=

.PHONY: up down ps build test itest smoke load chaos k8s-up k8s-down tilt

up:
	docker compose --profile $(PROFILE) up -d --wait

down:
	docker compose --profile $(PROFILE) down

ps:
	docker compose ps

# Dispatch by marker file: pom.xml -> jib, go.mod -> go build, pyproject.toml -> docker
build:
ifneq ($(wildcard services/$(SVC)/pom.xml),)
	./mvnw -pl services/$(SVC) -am compile jib:dockerBuild
else ifneq ($(wildcard services/$(SVC)/go.mod),)
	cd services/$(SVC) && go build -o bin/$(SVC) ./cmd/...
else ifneq ($(wildcard services/$(SVC)/pyproject.toml),)
	docker build -t $(SVC):dev services/$(SVC)
else
	@echo "unknown service: $(SVC)" && exit 1
endif

test:
	mvn -q test
	@for d in services/*/go.mod; do \
	  [ -f $$d ] && (cd $$(dirname $$d) && go test ./...); \
	done || true

itest:
	mvn -q verify -DskipUTs

smoke:
	@echo "--- infra ---"
	@docker compose ps --format '{{.Name}}: {{.Status}}'
	@echo "--- link-service ---"
	@curl -sf http://localhost:8081/healthz && echo " OK" || echo " DOWN"

load:
	k6 run load/scenarios/$(SCRIPT).js

chaos:
	./chaos/run.sh $(EXP)

k8s-up:
	k3d cluster create --config infra/k8s/k3d-config.yaml

k8s-down:
	k3d cluster delete agora

tilt:
	tilt up
