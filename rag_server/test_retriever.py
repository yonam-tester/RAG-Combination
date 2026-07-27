"""retriever 단위 테스트."""
import pytest
from langchain_core.documents import Document

import vector_store
from retriever import retrieve_evidences

# qdrant-client 로컬 모드(:memory:)는 payload 인덱스를 지원하지 않아
# ensure_collection의 create_payload_index 호출 시 UserWarning을 낸다.
# 실제 서버 Qdrant에서는 인덱스가 정상 동작하므로 이 경고는 테스트 환경에서만
# 발생하는 무해한 잡음이다.
pytestmark = pytest.mark.filterwarnings(
    "ignore:Payload indexes have no effect in the local Qdrant.*:UserWarning"
)


def _index(memory_vector_store, *specs):
    vector_store.add_documents([
        Document(page_content=text, metadata={
            "chunk_id": chunk_id,
            "file_id": "FILE-A",
            "file_name": source_name,
            "section_title": section,
            "source_type": source_type,
        })
        for chunk_id, text, source_name, section, source_type in specs
    ])


def test_returns_evidence_dicts_with_legacy_schema(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"))

    evidences = retrieve_evidences("로그인 실패 시 401을 반환한다", threshold=0.0)

    assert len(evidences) == 1
    assert evidences[0] == {
        "chunk_id": "CHNK-0001",
        "text": "로그인 실패 시 401을 반환한다",
        "source_name": "spec.pdf",
        "source_section": "1. 인증",
        "score": evidences[0]["score"],
    }
    assert isinstance(evidences[0]["score"], float)


def test_documents_and_knowledge_cards_rank_together(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"),
           ("QA-0001", "인증 실패 응답 코드 검증은 부정 시나리오의 기본이다", "istqb.json", "부정 테스트", "knowledge_card"))

    evidences = retrieve_evidences("인증 실패", threshold=0.0)

    assert {e["chunk_id"] for e in evidences} == {"CHNK-0001", "QA-0001"}


def test_results_are_sorted_by_score_descending(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"),
           ("CHNK-0002", "파일 업로드는 20MB로 제한한다", "spec.pdf", "2. 업로드", "document"),
           ("CHNK-0003", "리포트는 PDF로 내보낸다", "spec.pdf", "3. 리포트", "document"))

    evidences = retrieve_evidences("로그인 실패 시 401을 반환한다", threshold=0.0)

    scores = [e["score"] for e in evidences]
    assert scores == sorted(scores, reverse=True)
    assert evidences[0]["chunk_id"] == "CHNK-0001"


def test_exclude_chunk_ids_filters_already_used_evidence(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"),
           ("CHNK-0002", "파일 업로드는 20MB로 제한한다", "spec.pdf", "2. 업로드", "document"))

    evidences = retrieve_evidences("로그인 실패 시 401을 반환한다",
                                   threshold=0.0,
                                   exclude_chunk_ids={"CHNK-0001"})

    assert all(e["chunk_id"] != "CHNK-0001" for e in evidences)


def test_threshold_filters_low_similarity(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"))

    assert retrieve_evidences("완전히 다른 질의", threshold=0.999) == []


def test_empty_collection_returns_empty_list(memory_vector_store):
    assert retrieve_evidences("아무 질의", threshold=0.0) == []


def test_no_mock_rag_branch_remains():
    import retriever
    source = open(retriever.__file__, encoding="utf-8").read()
    assert "MOCK_RAG" not in source
    assert "CHNK-MOCK" not in source
