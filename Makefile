SHELL := /usr/bin/env bash

ENV_FILE ?= relay-server/.env.local
PROJECT ?= vocesleft-local
COMPOSE := docker compose --env-file $(ENV_FILE) -p $(PROJECT) -f relay-server/docker-compose.yml

.PHONY: help test build ci local-up local-down local-restart local-logs local-status local-config \
	datos-guardar datos-restaurar datos-test

help:
	@echo "make test          Ejecuta las pruebas Java"
	@echo "make build         Construye el JAR"
	@echo "make ci            Ejecuta pruebas y construye la imagen Docker"
	@echo "make local-up      Levanta Postgres y el servidor local"
	@echo "make local-down    Detiene el ambiente local sin borrar datos"
	@echo "make local-restart Reconstruye y reinicia el servidor local"
	@echo "make local-logs    Sigue los logs del servidor"
	@echo "make local-status  Muestra contenedores y healthchecks"
	@echo "make datos-guardar   Escribe los creadores de la base local en datos/development/"
	@echo "make datos-restaurar Devuelve a la base local lo que falte de datos/development/"
	@echo "make datos-test      Pruebas de la herramienta de datos"

test:
	cd relay-server && ./gradlew test --no-daemon

build:
	cd relay-server && ./gradlew bootJar --no-daemon

ci: test
	docker build --tag vocesleft-relay:local relay-server

local-config:
	@test -f "$(ENV_FILE)" || (echo "Falta $(ENV_FILE). Copia relay-server/.env.local.example y rellena sus valores." >&2; exit 1)
	$(COMPOSE) config --quiet

local-up: local-config
	$(COMPOSE) up -d --build --wait

local-down: local-config
	$(COMPOSE) down

local-restart: local-config
	$(COMPOSE) up -d --build --force-recreate servidor

local-logs: local-config
	$(COMPOSE) logs --follow servidor

local-status: local-config
	$(COMPOSE) ps

# El directorio en archivos. Ver datos/README.md. Testing y produccion se
# guardan solos desde el VPS (scripts/vps/respaldo-datos.sh).
datos-guardar:
	python3 scripts/datos/datos.py guardar development

datos-restaurar:
	python3 scripts/datos/datos.py restaurar development

datos-test:
	python3 -m unittest discover -s scripts/datos
