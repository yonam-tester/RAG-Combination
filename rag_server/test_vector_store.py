"""vector_store 단위 테스트 — Qdrant 로컬 모드(:memory:)에서 실제 코드 경로를 검증한다."""
import pytest
from langchain_core.documents import Document

import vector_store

# qdrant-client 로컬 모드(:memory:)는 payload 인덱스를 지원하지 않아
# ensure_collection의 create_payload_index 호출 시 UserWarning을 낸다.
# 실제 서버 Qdrant에서는 인덱스가 정상 동작하므로 이 경고는 테스트 환경에서만
# 발생하는 무해한 잡음이다.
pytestmark = pytest.mark.filterwarnings(
    "ignore:Payload indexes have no effect in the local Qdrant.*:UserWarning"
)


def _doc(chunk_id, text, file_id="FILE-A", source_type="document"):
    return Document(
        page_content=text,
        metadata={
            "chunk_id": chunk_id,
            "file_id": file_id,
            "file_name": "spec.pdf",
            "section_title": "1. 개요",
            "source_type": source_type,
        },
    )


def test_point_id_is_deterministic_uuid():
    first = vector_store.point_id_for("CHNK-ABC123")
    second = vector_store.point_id_for("CHNK-ABC123")
    other = vector_store.point_id_for("CHNK-XYZ789")

    assert first == second
    assert first != other
    assert len(first) == 36 and first.count("-") == 4


def test_add_documents_is_idempotent_upsert(memory_vector_store):
    vector_store.add_documents([_doc("CHNK-0001", "로그인 실패 시 401을 반환한다")])
    vector_store.add_documents([_doc("CHNK-0001", "로그인 실패 시 401을 반환한다")])

    count = memory_vector_store.client.count(vector_store.COLLECTION_NAME).count
    assert count == 1


def test_search_returns_documents_with_scores(memory_vector_store):
    vector_store.add_documents([
        _doc("CHNK-0001", "로그인 실패 시 401 Unauthorized를 반환해야 한다"),
        _doc("CHNK-0002", "파일 업로드는 20MB로 제한한다"),
    ])

    results = vector_store.search("로그인 실패 시 401 Unauthorized를 반환해야 한다", k=2, score_threshold=0.0)

    assert len(results) == 2
    top_doc, top_score = results[0]
    assert top_doc.metadata["chunk_id"] == "CHNK-0001"
    assert isinstance(top_score, float)
    # 동일 텍스트이므로 결정적 임베딩에서 코사인 유사도가 1.0에 수렴한다
    assert top_score > 0.99


def test_search_applies_score_threshold(memory_vector_store):
    vector_store.add_documents([_doc("CHNK-0001", "로그인 실패 시 401을 반환한다")])

    results = vector_store.search("전혀 무관한 질의", k=5, score_threshold=0.999)

    assert results == []


def test_delete_by_file_id_removes_only_that_file(memory_vector_store):
    vector_store.add_documents([
        _doc("CHNK-0001", "A 파일의 첫 청크", file_id="FILE-A"),
        _doc("CHNK-0002", "B 파일의 첫 청크", file_id="FILE-B"),
    ])

    vector_store.delete_by_file_id("FILE-A")

    # score_threshold=-1.0: DeterministicFakeEmbedding은 문자열 전체의 해시로 시드를
    # 만들어 벡터를 생성하므로, 부분 문자열 질의("청크")와 문서 사이의 코사인 유사도가
    # 음수로 나올 수 있다(무관한 두 랜덤 벡터이므로). 이 테스트의 목적은 threshold
    # 동작이 아니라 delete_by_file_id의 격리를 검증하는 것이므로, 코사인 유사도의
    # 이론적 하한(-1.0)을 threshold로 써서 필터링 없이 전량을 받는다.
    remaining = vector_store.search("청크", k=10, score_threshold=-1.0)
    remaining_ids = {doc.metadata["chunk_id"] for doc, _ in remaining}
    assert remaining_ids == {"CHNK-0002"}


def test_ensure_collection_rejects_dimension_mismatch(memory_vector_store):
    with pytest.raises(RuntimeError, match="벡터 차원 불일치"):
        vector_store.ensure_collection(memory_vector_store.client, dim=768)


def test_ping_returns_true_when_reachable(memory_vector_store):
    assert vector_store.ping() is True
