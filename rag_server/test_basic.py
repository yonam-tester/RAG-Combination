"""Smoke tests for rag_server endpoints."""
import pytest
from fastapi.testclient import TestClient

import vector_store

# qdrant-client 로컬 모드(:memory:)는 payload 인덱스를 지원하지 않아
# ensure_collection의 create_payload_index 호출 시 UserWarning을 낸다.
# 실제 서버 Qdrant에서는 인덱스가 정상 동작하므로 이 경고는 테스트 환경에서만
# 발생하는 무해한 잡음이다.
pytestmark = pytest.mark.filterwarnings(
    "ignore:Payload indexes have no effect in the local Qdrant.*:UserWarning"
)


@pytest.fixture
def client(memory_vector_store, monkeypatch):
    monkeypatch.setattr("ingestion.ingest_knowledge_base", lambda: 0)
    from main import app
    with TestClient(app) as c:
        yield c


def test_health_reports_healthy_when_qdrant_is_up(client):
    resp = client.get("/health")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "healthy"
    assert body["qdrant"] == "up"
    assert "mock_rag" not in body
    assert "queue_size" in body


def test_health_reports_unhealthy_when_qdrant_is_down(client, monkeypatch):
    monkeypatch.setattr(vector_store, "ping", lambda: False)

    resp = client.get("/health")

    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "unhealthy"
    assert body["qdrant"] == "down"


def test_metrics_contains_counter(client):
    resp = client.get("/metrics")

    assert resp.status_code == 200
    assert b"rag_tokens_total" in resp.content


def test_delete_vectors_removes_file_chunks(client, monkeypatch):
    deleted = []
    monkeypatch.setattr(vector_store, "delete_by_file_id", lambda file_id: deleted.append(file_id))

    resp = client.delete("/api/vectors/FILE-A")

    assert resp.status_code == 200
    assert resp.json()["status"] == "success"
    assert deleted == ["FILE-A"]


def test_eval_generate_keeps_response_contract(client, monkeypatch):
    """/api/eval/generate의 응답 스키마는 리팩토링 전후로 동일해야 한다 (evaluation/eval_runner.py가 의존)."""
    async def fake_extract(parsed_documents, api_key):
        return [{"id": "REQ-001", "text": "로그인 실패 시 401을 반환한다"}]

    monkeypatch.setattr("main.extract_requirements", fake_extract)

    resp = client.post("/api/eval/generate", json={
        "text": "1. 인증\n\n로그인 실패 시 401을 반환한다.",
        "perspectives": ["보안"],
    })

    assert resp.status_code == 200
    body = resp.json()
    assert set(body) == {"output", "token_count", "duration_ms"}
    assert isinstance(body["output"], str)
    assert isinstance(body["token_count"], int)
    assert isinstance(body["duration_ms"], int)


def test_no_mock_rag_anywhere_in_rag_server():
    """MOCK_RAG는 프로덕션 소스에서 완전히 제거되어야 한다.

    테스트 파일 자신(및 다른 test_*.py)은 스캔에서 제외한다 — 이 함수의 실패
    메시지 문자열 자체와 test_retriever.py::test_no_mock_rag_branch_remains의
    `assert "MOCK_RAG" not in source` 표현식이 "MOCK_RAG" 부분 문자열을
    포함하므로, 제외하지 않으면 이 테스트는 항상 자기 자신 때문에 실패한다.
    """
    import glob
    import os

    offenders = []
    for path in glob.glob(os.path.join(os.path.dirname(__file__), "*.py")):
        name = os.path.basename(path)
        if name.startswith("test_"):
            continue
        if "MOCK_RAG" in open(path, encoding="utf-8").read():
            offenders.append(name)

    assert offenders == [], f"MOCK_RAG가 아직 남아있다: {offenders}"
