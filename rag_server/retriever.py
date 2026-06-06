import os
import json
import logging
from typing import List, Dict
from vector_db_manager import vector_db_manager

logger = logging.getLogger("rag_server.retriever")

# Fallback static QA knowledge base in case JSON file is missing
DEFAULT_KB = [
    {
        "category": "보안",
        "title": "사용자 입력값 검증 및 SQL Injection 방어",
        "content": "사용자가 입력한 모든 파라미터는 백엔드 진입 시 즉시 유효성 검사(@Valid)를 거쳐야 하며, SQL 쿼리 빌드 시 PreparedStatement 또는 JPA Criteria API를 사용하여 파라미터를 바인딩해야 합니다."
    },
    {
        "category": "성능 및 가용성",
        "title": "데이터베이스 커넥션 풀 최적화 및 타임아웃",
        "content": "트래픽 폭주 시 데드락을 방지하기 위해 HikariCP 커넥션 풀 크기는 적절히 셋업되어야 하며, 장시간 수행 쿼리는 Query Timeout(예: 3초)을 명시적으로 부여하여 스레드 고갈을 차단해야 합니다."
    },
    {
        "category": "API 설계 및 예외 처리",
        "title": "일관된 글로벌 API 에러 응답 체계",
        "content": "백엔드 API는 어떠한 내부 서버 오류가 발생하더라도 사용자에게 Raw Stack Trace를 노출하지 않아야 하며, @RestControllerAdvice를 가동해 사전에 약속된 JSON 형태의 에러 응답 포맷(status, code, message)으로 통일하여 응답해야 합니다."
    },
    {
        "category": "파일 업로드 및 유효성",
        "title": "파일 업로드 용량 제한 및 확장자 필터링",
        "content": "업로드 파일은 프론트엔드와 백엔드 양측에서 이중 검증을 수행해야 합니다. 최대 용량 20MB 제한을 지키고, 허용된 안전한 확장자(pdf, md, txt, docx)만 통과시키며, 파일 MIME 타입을 직접 검증하여 실행 파일 업로드를 차단해야 합니다."
    }
]

def load_knowledge_base() -> List[Dict]:
    """
    Loads all QA knowledge cards from all JSON files in the rag_server/knowledge_base folder.
    Falls back to static templates if folder is empty or files are missing.
    """
    kb_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "knowledge_base")
    
    # Auto-create directory if it doesn't exist
    if not os.path.exists(kb_dir):
        try:
            os.makedirs(kb_dir)
            logger.info(f"Created knowledge base directory: {kb_dir}")
        except Exception as e:
            logger.error(f"Failed to create knowledge base directory {kb_dir}: {str(e)}")
            return DEFAULT_KB

    cards = []
    try:
        if not os.path.exists(kb_dir):
            return DEFAULT_KB
            
        json_files = [f for f in os.listdir(kb_dir) if f.lower().endswith(".json")]
        if not json_files:
            logger.warning(f"No JSON files found in {kb_dir}. Using default QA templates.")
            return DEFAULT_KB

        for file_name in json_files:
            file_path = os.path.join(kb_dir, file_name)
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    
                    file_cards_count = 0
                    if isinstance(data, list):
                        for item in data:
                            content = item.get("content") or item.get("description")
                            if not content:
                                parts = []
                                if item.get("technique"):
                                    parts.append(f"테스트 기법: {item.get('technique')}")
                                if item.get("apply_when"):
                                    apply_str = " / ".join(item.get("apply_when")) if isinstance(item.get("apply_when"), list) else item.get("apply_when")
                                    parts.append(f"적용 조건: {apply_str}")
                                if item.get("qa_perspective"):
                                    p_str = " / ".join(item.get("qa_perspective")) if isinstance(item.get("qa_perspective"), list) else item.get("qa_perspective")
                                    parts.append(f"QA 관점: {p_str}")
                                if item.get("risk_type"):
                                    parts.append(f"위험 유형: {item.get('risk_type')}")
                                if item.get("tdd_hint"):
                                    parts.append(f"TDD 힌트: {item.get('tdd_hint')}")
                                if item.get("example_scenario"):
                                    parts.append(f"예시 시나리오: {item.get('example_scenario')}")
                                if item.get("evidence"):
                                    parts.append(f"근거: {item.get('evidence')}")
                                content = " ".join(parts)
                            cards.append({
                                "category": item.get("category", "General"),
                                "title": item.get("title", ""),
                                "content": content
                            })
                            file_cards_count += 1
                    elif isinstance(data, dict) and "cards" in data:
                        for item in data["cards"]:
                            content = item.get("content") or item.get("description")
                            if not content:
                                parts = []
                                if item.get("technique"):
                                    parts.append(f"테스트 기법: {item.get('technique')}")
                                if item.get("apply_when"):
                                    apply_str = " / ".join(item.get("apply_when")) if isinstance(item.get("apply_when"), list) else item.get("apply_when")
                                    parts.append(f"적용 조건: {apply_str}")
                                if item.get("qa_perspective"):
                                    p_str = " / ".join(item.get("qa_perspective")) if isinstance(item.get("qa_perspective"), list) else item.get("qa_perspective")
                                    parts.append(f"QA 관점: {p_str}")
                                if item.get("risk_type"):
                                    parts.append(f"위험 유형: {item.get('risk_type')}")
                                if item.get("tdd_hint"):
                                    parts.append(f"TDD 힌트: {item.get('tdd_hint')}")
                                if item.get("example_scenario"):
                                    parts.append(f"예시 시나리오: {item.get('example_scenario')}")
                                if item.get("evidence"):
                                    parts.append(f"근거: {item.get('evidence')}")
                                content = " ".join(parts)
                            cards.append({
                                "category": item.get("category", "General"),
                                "title": item.get("title", ""),
                                "content": content
                            })
                            file_cards_count += 1
                    logger.info(f"Loaded {file_cards_count} cards from {file_name}")
            except Exception as e:
                logger.error(f"Failed to read knowledge cards from {file_name}: {str(e)}")
                
    except Exception as e:
        logger.error(f"Error accessing knowledge base directory: {str(e)}")
        
    return cards if cards else DEFAULT_KB

# Load knowledge cards once
knowledge_cards = load_knowledge_base()

def retrieve_evidences(query: str, threshold: float = 0.35) -> List[Dict]:
    """
    Retrieves evidence chunks from local vector store and performs hybrid search 
    with the QA knowledge base, sorting and merging by score.
    """
    mock_rag = os.getenv("MOCK_RAG", "true").lower() == "true"
    
    if mock_rag:
        logger.info(f"MOCK_RAG enabled. Returning mock evidence chunks for query: '{query}'")
        # Return realistic mockup evidence matching the query context
        return [
            {
                "chunk_id": "CHNK-MOCK001",
                "text": f"요구사항 검증 근거: 시스템은 입력 파라미터를 검사하여 유효하지 않을 경우 즉시 에러를 반환해야 합니다. {query}",
                "source_name": "specification_document.pdf",
                "source_section": "1.1 입력값 유효성 검증",
                "score": 0.85
            },
            {
                "chunk_id": "CHNK-MOCK002",
                "text": "QA 표준 지침: API 요청 시 Content-Type과 데이터 규격이 맞지 않으면 HTTP 400 Bad Request 에러 코드를 필수 반환한다.",
                "source_name": "atlassian_knowledge_cards_refined.json",
                "source_section": "API 설계 및 예외 처리",
                "score": 0.72
            },
            {
                "chunk_id": "CHNK-MOCK003",
                "text": "예외 처리 구현 지침: 컨트롤러 내 ExceptionHandler를 사용해 에러 응답 객체를 일관된 규격으로 조립한다.",
                "source_name": "atlassian_knowledge_cards_refined.json",
                "source_section": "API 설계 및 예외 처리",
                "score": 0.65
            }
        ]

    logger.info(f"Retrieving evidences for query: '{query}'")
    
    # 1. Vector Search in Document Chunks
    doc_results = vector_db_manager.search(query, top_k=5)
    
    # Filter by similarity threshold
    filtered_docs = []
    for doc in doc_results:
        score = doc.get("score", 0.0)
        if score >= threshold:
            filtered_docs.append({
                "chunk_id": doc["chunk_id"],
                "text": doc["text"],
                "source_name": doc["file_name"],
                "source_section": doc["section_title"],
                "score": score
            })
        else:
            logger.info(f"Chunk {doc['chunk_id']} similarity score {score:.4f} is below threshold {threshold}. Filtered out.")
            
    # 2. Cross-Search in QA Knowledge Cards
    query_words = set(query.lower().split())
    kb_results = []
    
    for idx, card in enumerate(knowledge_cards):
        card_content = card["content"].lower()
        card_title = card["title"].lower()
        
        # Calculate simple overlap score
        matches = sum(1 for word in query_words if word in card_content or word in card_title)
        overlap_score = matches / max(len(query_words), 1)
        
        if overlap_score > 0.1: # Small threshold for card match
            score = 0.4 + (overlap_score * 0.5) # Rescale score
            kb_results.append({
                "chunk_id": f"CHNK-KB{idx:03d}",
                "text": f"[{card['title']}] {card['content']}",
                "source_name": "atlassian_knowledge_cards_refined.json",
                "source_section": card["category"],
                "score": score
            })
            
    # Sort KB results and limit to top 3
    kb_results.sort(key=lambda x: x["score"], reverse=True)
    kb_results = kb_results[:3]
    
    # 3. Merge and Sort
    merged_results = filtered_docs + kb_results
    merged_results.sort(key=lambda x: x["score"], reverse=True)
    
    logger.info(f"Retrieved {len(merged_results)} merged evidence chunks.")
    return merged_results
