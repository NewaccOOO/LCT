# Команды из корня репозитория. Переменные переопределяются: make cli IN=вход.geojson OUT=выход.geojson
SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

PRESET ?= small
SEED ?= 1
IN ?= data/samples/small-1.geojson
OUT ?= data/out/$(basename $(notdir $(IN))).geojson
RULES ?=
ENV := . scripts/env.sh
PY := uv run --project tools

.PHONY: help up down logs jar cli synth validate run real test test-unit test-java viz

help: ## список команд
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

up: ## поднять приложение и PostgreSQL в Docker
	$(ENV); $$COMPOSE up -d --build

down: ## остановить и удалить контейнеры с томами
	$(ENV); $$COMPOSE down -v

logs: ## логи приложения
	$(ENV); $$COMPOSE logs -f app

jar: ## собрать target/heatnet.jar, если исходники новее
	$(ENV); ensure_jar

cli: jar ## расчёт из командной строки: IN -> OUT
	$(ENV); mkdir -p $(dir $(OUT)); java -jar target/heatnet.jar --cli $(IN) $(OUT) $(if $(RULES),--rules=$(RULES))

synth: ## сгенерировать синтетический вход PRESET/SEED в data/synth
	mkdir -p data/synth
	$(PY) python -m heatsynth --preset $(PRESET) --seed $(SEED) --out data/synth/$(PRESET)-$(SEED).geojson

validate: ## проверить OUT по правилам кейса (tools/validator/check18.py)
	$(PY) python tools/validator/check18.py $(IN) $(OUT)

viz: ## офлайн HTML-карта результата OUT рядом с ним
	python3 scripts/visualize.py $(IN) $(OUT)

run: cli validate ## посчитать IN и проверить выход

real: ## датасет организаторов из data/real/dataset.geojson: посчитать и проверить
	$(MAKE) run IN=data/real/dataset.geojson OUT=data/out/real.geojson

test: test-java ## все тесты

test-unit: ## юнит-тесты Java без интеграционных
	$(ENV); mvn -B test

test-java: ## юнит- и интеграционные тесты Java, нужен Docker
	$(ENV); mvn -B verify
