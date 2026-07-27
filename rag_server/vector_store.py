"""Qdrant 벡터 스토어 접근 계층.

이 모듈이 벡터DB에 대한 유일한 접근 지점이다. 다른 모듈은 qdrant_client나
QdrantVectorStore를 직접 다루지 않는다.
"""
import logging
import os
import uuid
from typing import List, Optional, Tuple

from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_qdrant import QdrantVectorStore
from qdrant_client import QdrantClient, models

logger = logging.getLogger("rag_server.vector_store")

EMBEDDING_DIM = 384
COLLECTION_NAME = os.getenv("QDRANT_COLLECTION", "yeonam_knowledge")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "all-MiniLM-L6-v2")
QDRANT_URL = os.getenv("QDRANT_URL", "http://qdrant:6333")

# chunk_id(사람이 읽는 문자열)를 Qdrant point ID(UUID)로 결정적 변환하기 위한 네임스페이스.
# 값 자체에 의미는 없으나 절대 바꾸면 안 된다 — 바꾸면 기존 포인트가 전부 중복 삽입된다.
CHUNK_NAMESPACE = uuid.UUID("1b671a64-40d5-491e-99b0-da01ff1f3341")

_vector_store: Optional[QdrantVectorStore] = None


def point_id_for(chunk_id: str) -> str:
    """chunk_id를 결정적 UUID로 변환한다. 동일 chunk_id는 항상 동일 point ID가 되어 upsert가 멱등해진다."""
    return str(uuid.uuid5(CHUNK_NAMESPACE, chunk_id))


def build_embeddings() -> Embeddings:
    """로컬 임베딩 모델을 로드한다. 실패는 전파한다 — 조용한 폴백을 두지 않는다."""
    from langchain_huggingface import HuggingFaceEmbeddings

    logger.info(f"임베딩 모델 로드 중: {EMBEDDING_MODEL}")
    embeddings = HuggingFaceEmbeddings(model_name=EMBEDDING_MODEL)
    logger.info("임베딩 모델 로드 완료")
    return embeddings


def build_client() -> QdrantClient:
    """QDRANT_URL이 ':memory:'이면 서버 없는 로컬 모드로 뜬다(테스트용)."""
    if QDRANT_URL == ":memory:":
        logger.info("Qdrant 로컬 모드(:memory:)로 기동")
        return QdrantClient(location=":memory:")
    logger.info(f"Qdrant 연결: {QDRANT_URL}")
    return QdrantClient(url=QDRANT_URL)


def ensure_collection(client: QdrantClient, dim: int) -> None:
    """컬렉션이 없으면 만들고, 있으면 벡터 차원이 일치하는지 검증한다."""
    if client.collection_exists(COLLECTION_NAME):
        existing_dim = client.get_collection(COLLECTION_NAME).config.params.vectors.size
        if existing_dim != dim:
            raise RuntimeError(
                f"벡터 차원 불일치: 컬렉션 '{COLLECTION_NAME}'은 {existing_dim}차원인데 "
                f"현재 임베딩 모델은 {dim}차원이다. 컬렉션을 삭제하고 수동 재적재가 필요하다."
            )
        return

    logger.info(f"컬렉션 생성: {COLLECTION_NAME} (dim={dim}, COSINE)")
    client.create_collection(
        collection_name=COLLECTION_NAME,
        vectors_config=models.VectorParams(size=dim, distance=models.Distance.COSINE),
    )
    # file_id 기반 삭제 필터를 위한 payload 인덱스.
    client.create_payload_index(
        collection_name=COLLECTION_NAME,
        field_name="metadata.file_id",
        field_schema=models.PayloadSchemaType.KEYWORD,
    )


def get_vector_store() -> QdrantVectorStore:
    """프로세스 수명 동안 재사용되는 벡터 스토어 싱글턴."""
    global _vector_store
    if _vector_store is None:
        embeddings = build_embeddings()
        dim = len(embeddings.embed_query("차원 확인용 질의"))
        client = build_client()
        ensure_collection(client, dim)
        _vector_store = QdrantVectorStore(
            client=client,
            collection_name=COLLECTION_NAME,
            embedding=embeddings,
        )
    return _vector_store


def reset_vector_store() -> None:
    """싱글턴을 초기화한다. 테스트에서만 사용한다."""
    global _vector_store
    _vector_store = None


def add_documents(documents: List[Document]) -> List[str]:
    """문서를 upsert한다. metadata['chunk_id']에서 결정적 point ID를 만든다."""
    if not documents:
        return []
    ids = [point_id_for(doc.metadata["chunk_id"]) for doc in documents]
    get_vector_store().add_documents(documents, ids=ids)
    logger.info(f"{len(documents)}개 문서를 '{COLLECTION_NAME}'에 upsert")
    return ids


def delete_by_file_id(file_id: str) -> None:
    """특정 파일에서 유래한 모든 청크를 payload 필터로 즉시 삭제한다(전체 재빌드 불필요)."""
    store = get_vector_store()
    store.client.delete(
        collection_name=COLLECTION_NAME,
        points_selector=models.FilterSelector(
            filter=models.Filter(
                must=[
                    models.FieldCondition(
                        key="metadata.file_id",
                        match=models.MatchValue(value=file_id),
                    )
                ]
            )
        ),
    )
    logger.info(f"file_id={file_id} 청크 삭제 완료")


def search(query: str, k: int = 5, score_threshold: float = 0.35) -> List[Tuple[Document, float]]:
    """유사도 검색 후 threshold 미만을 걸러낸다. score는 코사인 유사도이며 호출자가 그대로 노출한다."""
    hits = get_vector_store().similarity_search_with_score(query, k=k)
    kept = [(doc, float(score)) for doc, score in hits if score >= score_threshold]
    dropped = len(hits) - len(kept)
    if dropped:
        logger.info(f"유사도 {score_threshold} 미만 {dropped}건 제외")
    return kept


def ping() -> bool:
    """Qdrant 연결 상태를 확인한다. /health가 이 결과를 그대로 반영한다."""
    try:
        get_vector_store().client.get_collections()
        return True
    except Exception as e:
        logger.error(f"Qdrant ping 실패: {e}")
        return False
