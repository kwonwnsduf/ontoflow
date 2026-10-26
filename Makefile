.PHONY: dev down logs test validate

dev:
	docker compose up --build

down:
	docker compose down

logs:
	docker compose logs -f

test:
	docker compose --profile test run --rm platform-api-test
	docker compose --profile test run --rm ai-service-test
	docker compose --profile test run --rm web-test

validate:
	docker compose config --quiet

