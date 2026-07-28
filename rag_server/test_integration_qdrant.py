"""실제 Qdrant 서버를 대상으로 하는 통합 테스트.

QDRANT_URL이 ':memory:'가 아닐 때만 실행된다(CI의 서비스 컨테이너 / 로컬 docker compose).
실행: QDRANT_URL=http://localhost:6333 pytest test_integration_qdrant.py -m integration -v
"""
import os
import uuid

import pytest
from langchain_core.documents import Document
from langchain_core.embeddings import DeterministicFakeEmbedding

pytestmark = pytest.mark.integration

_QDRANT_URL = os.getenv("QDRANT_URL", ":memory:")

skip_without_server = pytest.mark.skipif(
    _QDRANT_URL == ":memory:",
    reason="실제 Qdrant 서버가 필요하다 (QDRANT_URL 환경변수 설정 필요)",
)


@pytest.fixture
def live_store(monkeypatch):
    """테스트마다 고유 컬렉션을 써서 서로 간섭하지 않게 한다."""
    collection = f"itest_{uuid.uuid4().hex[:8]}"
    monkeypatch.setenv("QDRANT_COLLECTION", collection)

    import importlib
    import vector_store as vs
    importlib.reload(vs)
    monkeypatch.setattr(vs, "build_embeddings", lambda: DeterministicFakeEmbedding(size=384))
    vs.reset_vector_store()

    store = vs.get_vector_store()
    yield vs
    store.client.delete_collection(collection)
    vs.reset_vector_store()
    importlib.reload(vs)


@skip_without_server
def test_upsert_search_delete_roundtrip_against_live_qdrant(live_store):
    live_store.add_documents([
        Document(page_content="로그인 실패 시 401 Unauthorized를 반환해야 한다", metadata={
            "chunk_id": "CHNK-IT-0000", "file_id": "FILE-IT",
            "file_name": "spec.pdf", "section_title": "1. 인증", "source_type": "document",
        }),
        Document(page_content="인증 실패 응답 코드 검증은 부정 시나리오의 기본이다", metadata={
            "chunk_id": "QA-IT-0000", "file_id": "__knowledge_base__",
            "file_name": "istqb.json", "section_title": "부정 테스트", "source_type": "knowledge_card",
        }),
    ])

    # score_threshold=-1.0(코사인 유사도의 이론적 최솟값)을 써서 사실상 threshold 없이 전부 반환받는다.
    # DeterministicFakeEmbedding은 문자열 해시 기반이라 의미적 유사도가 없고, 무관한 텍스트끼리는
    # 코사인 유사도가 음수가 될 수 있다(실측: 아래 두 질의 모두 QA-IT-0000에 대해 음수 점수).
    # score_threshold=0.0을 쓰면 그 음수 점수 문서가 걸러져 두 청크가 모두 들어있는지 검증하려는
    # 이 테스트의 의도(실서버 upsert/search/delete 왕복 검증)와 어긋나므로 -1.0으로 보정한다.
    hits = live_store.search("로그인 실패 시 401 Unauthorized를 반환해야 한다", k=5, score_threshold=-1.0)
    assert {doc.metadata["chunk_id"] for doc, _ in hits} == {"CHNK-IT-0000", "QA-IT-0000"}

    live_store.delete_by_file_id("FILE-IT")

    remaining = live_store.search("인증", k=5, score_threshold=-1.0)
    assert {doc.metadata["chunk_id"] for doc, _ in remaining} == {"QA-IT-0000"}


@skip_without_server
def test_ping_succeeds_against_live_qdrant(live_store):
    assert live_store.ping() is True


@skip_without_server
def test_dimension_mismatch_is_detected(live_store):
    with pytest.raises(RuntimeError, match="벡터 차원 불일치"):
        live_store.ensure_collection(live_store.get_vector_store().client, dim=768)
