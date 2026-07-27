"""요구사항 텍스트에 대한 근거(evidence) 검색.

문서 청크와 QA 지식카드가 같은 Qdrant 컬렉션에 있으므로 단 한 번의
유사도 검색으로 함께 랭킹된다. 예전처럼 두 경로를 따로 태우고 점수를
수동 병합하지 않는다.
"""
import logging
from typing import Dict, List, Optional, Set

import vector_store

logger = logging.getLogger("rag_server.retriever")

DEFAULT_TOP_K = 5


def retrieve_evidences(
    query: str,
    threshold: float = 0.35,
    exclude_chunk_ids: Optional[Set[str]] = None,
) -> List[Dict]:
    """질의와 유사한 근거 청크를 점수 내림차순으로 반환한다.

    Args:
        query: 요구사항 텍스트.
        threshold: 코사인 유사도 하한. 미만은 버린다.
        exclude_chunk_ids: 같은 분석 작업의 앞선 요구사항에서 이미 쓴 chunk_id.
                           요구사항 간 근거 중복을 막는다.

    Returns:
        {"chunk_id", "text", "source_name", "source_section", "score"} dict 목록.
    """
    excluded = exclude_chunk_ids or set()

    # 제외될 만큼 여유를 두고 가져와야 필터 후에도 DEFAULT_TOP_K를 채울 수 있다.
    fetch_k = DEFAULT_TOP_K + len(excluded)
    hits = vector_store.search(query, k=fetch_k, score_threshold=threshold)

    evidences: List[Dict] = []
    for document, score in hits:
        chunk_id = document.metadata.get("chunk_id")
        if chunk_id in excluded:
            continue
        evidences.append({
            "chunk_id": chunk_id,
            "text": document.page_content,
            "source_name": document.metadata.get("file_name", "unknown"),
            "source_section": document.metadata.get("section_title", "General"),
            "score": score,
        })

    evidences.sort(key=lambda item: item["score"], reverse=True)
    evidences = evidences[:DEFAULT_TOP_K]

    logger.info(f"'{query[:40]}...' 질의로 근거 {len(evidences)}건 검색")
    return evidences
