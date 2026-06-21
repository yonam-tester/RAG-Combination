"""Smoke tests for rag_server endpoints."""
import pytest
from fastapi.testclient import TestClient


@pytest.fixture(scope="module")
def client():
    from main import app
    with TestClient(app) as c:
        yield c


def test_health_returns_healthy(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "healthy"


def test_metrics_contains_counter(client):
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert b"rag_tokens_total" in resp.content
