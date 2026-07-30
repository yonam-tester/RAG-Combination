"""ingestion 단위 테스트."""
import json
import os

import pytest
from langchain_core.documents import Document

import ingestion
import vector_store

# qdrant-client 로컬 모드(:memory:)는 payload 인덱스를 지원하지 않아
# ensure_collection의 create_payload_index 호출 시 UserWarning을 낸다.
# 실제 서버 Qdrant에서는 인덱스가 정상 동작하므로 이 경고는 테스트 환경에서만
# 발생하는 무해한 잡음이다.
pytestmark = pytest.mark.filterwarnings(
    "ignore:Payload indexes have no effect in the local Qdrant.*:UserWarning"
)


def test_load_documents_from_jsonl_prefers_rag_chunks(tmp_path, monkeypatch):
    jsonl = tmp_path / "rag_chunks.jsonl"
    jsonl.write_text(
        json.dumps({
            "chunk_id": "QA-0001",
            "section_title": "경계값 분석",
            "content": "경계값 분석은 입력 경계에서 결함이 몰린다는 점을 이용한다.",
            "source_file": "istqb_knowledge_cards.json",
            "metadata": {"keywords": ["테스트 설계"]},
        }, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(jsonl))

    docs = ingestion.load_knowledge_documents()

    assert len(docs) == 1
    doc = docs[0]
    assert doc.page_content == "경계값 분석은 입력 경계에서 결함이 몰린다는 점을 이용한다."
    assert doc.metadata["chunk_id"] == "QA-0001"
    assert doc.metadata["section_title"] == "경계값 분석"
    assert doc.metadata["file_name"] == "istqb_knowledge_cards.json"
    assert doc.metadata["source_type"] == "knowledge_card"
    assert doc.metadata["file_id"] == ingestion.KNOWLEDGE_FILE_ID


def test_load_documents_falls_back_to_knowledge_base_json(tmp_path, monkeypatch):
    kb_dir = tmp_path / "knowledge_base"
    kb_dir.mkdir()
    (kb_dir / "sample_knowledge_cards.json").write_text(
        json.dumps([
            {"category": "보안", "title": "SQL Injection 방어", "content": "PreparedStatement를 사용한다."},
            {"category": "성능", "title": "커넥션 풀", "technique": "부하 테스트", "risk_type": "스레드 고갈"},
        ], ensure_ascii=False),
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(tmp_path / "missing.jsonl"))
    monkeypatch.setattr(ingestion, "KNOWLEDGE_BASE_DIR", str(kb_dir))

    docs = ingestion.load_knowledge_documents()

    assert len(docs) == 2
    assert docs[0].page_content == "PreparedStatement를 사용한다."
    # content가 없는 카드는 구조화 필드를 조합해 본문을 만든다
    assert "테스트 기법: 부하 테스트" in docs[1].page_content
    assert "위험 유형: 스레드 고갈" in docs[1].page_content
    assert {d.metadata["chunk_id"] for d in docs} == {
        "QA-SAMPLE-KNOWLEDGE-CARDS-0000",
        "QA-SAMPLE-KNOWLEDGE-CARDS-0001",
    }


def test_every_knowledge_base_source_is_actually_ingested():
    """실제 저장소 데이터 검증: knowledge_base/의 모든 출처가 적재 대상에 포함되어야 한다.

    rag_chunks.jsonl이 knowledge_base/보다 오래되면 일부 출처가 조용히 누락된다.
    (그 경우 load_knowledge_documents는 jsonl만 읽고 디렉터리를 아예 보지 않는다.)
    이 테스트는 그 드리프트를 잡는다.
    """
    expected_sources = {
        name for name in os.listdir(ingestion.KNOWLEDGE_BASE_DIR)
        if name.lower().endswith(".json")
    }

    ingested_sources = {doc.metadata["file_name"] for doc in ingestion.load_knowledge_documents()}

    missing = expected_sources - ingested_sources
    assert not missing, (
        f"knowledge_base/에 있으나 적재되지 않는 출처: {sorted(missing)}. "
        f"embedded/generate_rag_chunks.py로 rag_chunks.jsonl을 재생성해야 한다."
    )


def test_load_documents_falls_back_to_default_kb(tmp_path, monkeypatch):
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(tmp_path / "missing.jsonl"))
    monkeypatch.setattr(ingestion, "KNOWLEDGE_BASE_DIR", str(tmp_path / "missing_dir"))

    docs = ingestion.load_knowledge_documents()

    assert len(docs) == len(ingestion.DEFAULT_KB)
    assert all(d.metadata["source_type"] == "knowledge_card" for d in docs)


def test_ingest_knowledge_base_is_idempotent(memory_vector_store, tmp_path, monkeypatch):
    jsonl = tmp_path / "rag_chunks.jsonl"
    jsonl.write_text(
        json.dumps({
            "chunk_id": "QA-0001",
            "section_title": "경계값 분석",
            "content": "경계값 분석 설명",
            "source_file": "istqb_knowledge_cards.json",
        }, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(jsonl))

    assert ingestion.ingest_knowledge_base() == 1
    assert ingestion.ingest_knowledge_base() == 1

    count = memory_vector_store.client.count(vector_store.COLLECTION_NAME).count
    assert count == 1


def test_ingested_knowledge_survives_document_deletion(memory_vector_store, tmp_path, monkeypatch):
    jsonl = tmp_path / "rag_chunks.jsonl"
    jsonl.write_text(
        json.dumps({
            "chunk_id": "QA-0001",
            "section_title": "경계값 분석",
            "content": "경계값 분석 설명",
            "source_file": "istqb_knowledge_cards.json",
        }, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(jsonl))
    ingestion.ingest_knowledge_base()

    vector_store.add_documents([
        Document(page_content="사용자 문서 청크", metadata={
            "chunk_id": "CHNK-FILE-A-0000", "file_id": "FILE-A",
            "file_name": "spec.pdf", "section_title": "1. 개요", "source_type": "document",
        })
    ])
    vector_store.delete_by_file_id("FILE-A")

    count = memory_vector_store.client.count(vector_store.COLLECTION_NAME).count
    assert count == 1
