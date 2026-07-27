"""정적 QA 지식(지식카드)을 Qdrant에 적재한다.

우선순위:
  1) rag_chunks.jsonl  (embedded/generate_rag_chunks.py 산출물)
  2) knowledge_base/*.json
  3) DEFAULT_KB (하드코딩 최소 세트)

chunk_id가 결정적이라 서버를 여러 번 재기동해도 중복 삽입되지 않는다.
"""
import json
import logging
import os
from typing import Dict, List

from langchain_core.documents import Document

import vector_store

logger = logging.getLogger("rag_server.ingestion")

_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RAG_CHUNKS_PATH = os.path.join(_BASE_DIR, "rag_chunks.jsonl")
KNOWLEDGE_BASE_DIR = os.path.join(_BASE_DIR, "knowledge_base")

# 지식카드 전용 고정 file_id. 사용자 문서 삭제(delete_by_file_id)가 지식카드를 건드리지 않게 한다.
KNOWLEDGE_FILE_ID = "__knowledge_base__"

# content 필드가 없는 카드에서 본문을 조합할 때 쓰는 (필드명, 한국어 라벨) 목록.
_COMPOSED_FIELDS = [
    ("technique", "테스트 기법"),
    ("apply_when", "적용 조건"),
    ("qa_perspective", "QA 관점"),
    ("risk_type", "위험 유형"),
    ("tdd_hint", "TDD 힌트"),
    ("example_scenario", "예시 시나리오"),
    ("evidence", "근거"),
]

DEFAULT_KB: List[Dict] = [
    {
        "category": "보안",
        "title": "사용자 입력값 검증 및 SQL Injection 방어",
        "content": "사용자가 입력한 모든 파라미터는 백엔드 진입 시 즉시 유효성 검사(@Valid)를 거쳐야 하며, SQL 쿼리 빌드 시 PreparedStatement 또는 JPA Criteria API를 사용하여 파라미터를 바인딩해야 합니다.",
    },
    {
        "category": "성능 및 가용성",
        "title": "데이터베이스 커넥션 풀 최적화 및 타임아웃",
        "content": "트래픽 폭주 시 데드락을 방지하기 위해 HikariCP 커넥션 풀 크기는 적절히 셋업되어야 하며, 장시간 수행 쿼리는 Query Timeout(예: 3초)을 명시적으로 부여하여 스레드 고갈을 차단해야 합니다.",
    },
    {
        "category": "API 설계 및 예외 처리",
        "title": "일관된 글로벌 API 에러 응답 체계",
        "content": "백엔드 API는 어떠한 내부 서버 오류가 발생하더라도 사용자에게 Raw Stack Trace를 노출하지 않아야 하며, @RestControllerAdvice를 가동해 사전에 약속된 JSON 형태의 에러 응답 포맷(status, code, message)으로 통일하여 응답해야 합니다.",
    },
    {
        "category": "파일 업로드 및 유효성",
        "title": "파일 업로드 용량 제한 및 확장자 필터링",
        "content": "업로드 파일은 프론트엔드와 백엔드 양측에서 이중 검증을 수행해야 합니다. 최대 용량 20MB 제한을 지키고, 허용된 안전한 확장자(pdf, md, txt, docx)만 통과시키며, 파일 MIME 타입을 직접 검증하여 실행 파일 업로드를 차단해야 합니다.",
    },
]


def _card_document(chunk_id: str, title: str, content: str, source_name: str) -> Document:
    return Document(
        page_content=content,
        metadata={
            "chunk_id": chunk_id,
            "file_id": KNOWLEDGE_FILE_ID,
            "file_name": source_name,
            "section_title": title,
            "source_type": "knowledge_card",
        },
    )


def _compose_content(card: Dict) -> str:
    """content/description이 없는 카드에서 구조화 필드를 한국어 라벨과 함께 이어붙인다."""
    direct = card.get("content") or card.get("description")
    if direct:
        return direct

    parts = []
    for field, label in _COMPOSED_FIELDS:
        value = card.get(field)
        if not value:
            continue
        if isinstance(value, list):
            value = " / ".join(str(item) for item in value)
        parts.append(f"{label}: {value}")
    return " ".join(parts)


def _load_from_jsonl() -> List[Document]:
    documents = []
    with open(RAG_CHUNKS_PATH, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            item = json.loads(line)
            content = item.get("content", "").strip()
            if not content:
                continue
            documents.append(
                _card_document(
                    chunk_id=item.get("chunk_id", "QA-0000"),
                    title=item.get("section_title", "General"),
                    content=content,
                    source_name=item.get("source_file", "unknown_cards.json"),
                )
            )
    logger.info(f"{RAG_CHUNKS_PATH}에서 지식 청크 {len(documents)}건 로드")
    return documents


def _load_from_knowledge_base_dir() -> List[Document]:
    documents = []
    json_files = sorted(f for f in os.listdir(KNOWLEDGE_BASE_DIR) if f.lower().endswith(".json"))

    for file_name in json_files:
        path = os.path.join(KNOWLEDGE_BASE_DIR, file_name)
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            logger.error(f"{file_name} 읽기 실패: {e}")
            continue

        cards = data if isinstance(data, list) else data.get("cards", [])
        # chunk_id 안정성을 위해 파일명 stem을 접두어로 쓴다(확장자/구분자 제거).
        stem = file_name.rsplit(".", 1)[0].replace("_", "-").upper()
        for index, card in enumerate(cards):
            content = _compose_content(card)
            if not content:
                continue
            documents.append(
                _card_document(
                    chunk_id=f"QA-{stem}-{index:04d}",
                    title=card.get("title") or card.get("category", "General"),
                    content=content,
                    source_name=file_name,
                )
            )
        logger.info(f"{file_name}에서 카드 {len(cards)}건 처리")

    return documents


def load_knowledge_documents() -> List[Document]:
    """지식카드를 Document 목록으로 로드한다."""
    if os.path.exists(RAG_CHUNKS_PATH):
        documents = _load_from_jsonl()
        if documents:
            return documents

    if os.path.isdir(KNOWLEDGE_BASE_DIR):
        documents = _load_from_knowledge_base_dir()
        if documents:
            return documents

    logger.warning("지식카드 파일을 찾지 못해 DEFAULT_KB로 대체한다.")
    return [
        _card_document(
            chunk_id=f"QA-DEFAULT-{index:04d}",
            title=card["title"],
            content=card["content"],
            source_name="default_knowledge_base",
        )
        for index, card in enumerate(DEFAULT_KB)
    ]


def ingest_knowledge_base() -> int:
    """지식카드를 Qdrant에 upsert한다. 실패는 전파해 기동을 실패시킨다."""
    documents = load_knowledge_documents()
    vector_store.add_documents(documents)
    logger.info(f"지식카드 {len(documents)}건을 '{vector_store.COLLECTION_NAME}'에 적재")
    return len(documents)
