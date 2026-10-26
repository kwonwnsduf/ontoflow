from fastapi.testclient import TestClient

from app.main import app


def test_health_and_generated_trace_id() -> None:
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "ai-service"}
    assert len(response.headers["X-Trace-Id"]) == 32


def test_valid_trace_id_is_propagated() -> None:
    response = TestClient(app).get("/health", headers={"X-Trace-Id": "trace-123"})
    assert response.headers["X-Trace-Id"] == "trace-123"


def test_invalid_trace_id_is_replaced() -> None:
    response = TestClient(app).get("/health", headers={"X-Trace-Id": "bad trace id"})
    assert response.headers["X-Trace-Id"] != "bad trace id"

