# Команды из корня репозитория. Переменные переопределяются: make e2e PRESET=medium SEED=3
SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

PRESET ?= small
SEED ?= 1
IN ?= data/synth/$(PRESET)-$(SEED).geojson
OUT ?= data/out/$(PRESET)-$(SEED).geojson
ARGS ?=
ENV := . scripts/gates/env.sh
PY := uv run --project tools

.PHONY: help up down logs jar cli synth validate e2e real test test-unit test-java test-python test-scenarios sweep gate

help: ## список команд
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-16s %s\n", $$1, $$2}'

up: ## поднять приложение и PostgreSQL в Docker
	$(ENV); $$COMPOSE up -d --build

down: ## остановить и удалить контейнеры с томами
	$(ENV); $$COMPOSE down -v

logs: ## логи приложения
	$(ENV); $$COMPOSE logs -f app

jar: ## собрать target/heatnet.jar, если исходники новее
	$(ENV); ensure_jar

cli: jar ## расчёт из командной строки: IN -> OUT
	$(ENV); mkdir -p $(dir $(OUT)); java -jar target/heatnet.jar --cli $(IN) $(OUT)

synth: ## сгенерировать синтетический вход PRESET/SEED в IN
	mkdir -p $(dir $(IN))
	$(PY) python -m heatsynth --preset $(PRESET) --seed $(SEED) --out $(IN)
	$(PY) python -m heatsynth.check $(IN) $(if $(filter medium,$(PRESET)),--preset medium)

validate: ## проверить OUT валидатором по всем правилам
	$(PY) python -m heatcheck $(IN) $(OUT)

e2e: synth cli validate ## сгенерировать вход, посчитать через CLI, проверить валидатором

real: ## датасет организаторов: посчитать через CLI и проверить валидатором
	$(MAKE) cli validate IN=data/real/dataset.geojson OUT=data/out/real.geojson

test: test-java test-python test-scenarios ## все тесты

test-unit: ## юнит-тесты Java без интеграционных
	$(ENV); mvn -B test

test-java: ## юнит- и интеграционные тесты Java, нужен Docker
	$(ENV); mvn -B verify

test-python: ## pytest валидатора
	$(PY) pytest -q -p no:cacheprovider tests/validator

test-scenarios: ## сценарии S00–S14 через CLI, фильтр: ARGS="-k S03"
	scripts/gates/scenarios.sh $(ARGS)

sweep: ## случайный прогон 300 сидов через CLI и валидатор
	scripts/gates/sweep.sh

gate: ## один гейт приёмки: make gate GATE=perf ARGS=...
	scripts/gates/$(GATE).sh $(ARGS)
