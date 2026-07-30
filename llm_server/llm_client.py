import os
import json
import logging
import asyncio
import litellm
import hashlib
import math
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

try:
    import numpy as np
except ImportError:
    np = None

logger = logging.getLogger("llm_server.llm_client")

@dataclass(frozen=True)
class Requirement:
    requirement_id: str
    text: str

@dataclass(frozen=True)
class SearchResult:
    score: float
    chunk: dict[str, Any]

class HashEmbedding:
    def __init__(self, dimension: int = 2048) -> None:
        self.dimension = dimension

    def embed(self, text: str) -> Any:
        normalized = re.sub(r"\s+", " ", text.strip().lower())
        vector = np.zeros(self.dimension, dtype=np.float32) if np else [0.0] * self.dimension
        tokens = normalized.split()
        features = tokens + [
            normalized[index : index + size]
            for size in (2, 3)
            for index in range(max(0, len(normalized) - size + 1))
        ]
        for feature in features:
            digest = hashlib.blake2b(feature.encode("utf-8"), digest_size=8).digest()
            slot = int.from_bytes(digest, "big") % self.dimension
            vector[slot] += 1.0
        norm = float(np.linalg.norm(vector)) if np else math.sqrt(sum(value * value for value in vector))
        if norm:
            if np:
                vector /= norm
            else:
                vector = [value / norm for value in vector]
        return vector

    def embed_many(self, texts: list[str]) -> Any:
        vectors = [self.embed(text) for text in texts]
        return np.vstack(vectors) if np else vectors

class NumpyChunkStore:
    """FAISS를 쓰지 않는다. 청크 벡터를 numpy 행렬로 들고 내적으로 완전탐색한다.

    numpy가 없으면 순수 파이썬 루프로 떨어진다. llm-server는 RAG 없는 LLM 단독
    생성 경로의 비교 기준이라, 외부 벡터DB 의존 없이 오프라인으로 동작하는 것이
    목적이다. 실제 벡터 검색은 rag-server의 Qdrant가 담당한다.
    """

    def __init__(self, chunks: list[dict[str, Any]], embedder: HashEmbedding) -> None:
        self.chunks = chunks
        self.embedder = embedder
        self.vectors = embedder.embed_many([chunk["content"] for chunk in chunks])
        self.backend = "numpy" if np else "python"
        self.document_frequency = Counter(
            term
            for chunk in chunks
            for term in set(self._terms(chunk["content"]))
        )

    def search(self, query: str, top_k: int) -> list[SearchResult]:
        query_vector = self.embedder.embed(query)
        if np:
            similarities = self.vectors @ query_vector
            indexes = np.argsort(similarities)[::-1].reshape(1, -1)
            scores = similarities[indexes]
        else:
            ranked = sorted(
                (
                    (sum(left * right for left, right in zip(vector, query_vector)), index)
                    for index, vector in enumerate(self.vectors)
                ),
                reverse=True,
            )
            scores = [[score for score, _ in ranked]]
            indexes = [[index for _, index in ranked]]
        reranked = sorted(
            [
                (
                    float(score)
                    + 0.35 * self._lexical_score(query, self.chunks[index]["content"])
                    + self._concept_bonus(query, self.chunks[index]["content"]),
                    index,
                )
                for score, index in zip(scores[0], indexes[0])
                if index >= 0
            ],
            reverse=True,
        )[:top_k]
        return [SearchResult(score=score, chunk=self.chunks[index]) for score, index in reranked]

    @staticmethod
    def _terms(text: str) -> list[str]:
        return [
            term.lower()
            for term in re.findall(r"[0-9A-Za-z가-힣]+", text)
            if len(term) >= 2
        ]

    def _lexical_score(self, query: str, content: str) -> float:
        terms = set(self._terms(query))
        if not terms:
            return 0.0
        lowered_content = content.lower()
        weighted_terms = {
            term: math.log((len(self.chunks) + 1) / (self.document_frequency[term] + 1)) + 1
            for term in terms
        }
        denominator = sum(weighted_terms.values())
        return sum(weight for term, weight in weighted_terms.items() if term in lowered_content) / denominator

    @staticmethod
    def _concept_bonus(query: str, content: str) -> float:
        lowered_query = query.lower()
        lowered_content = content.lower()
        SEARCH_CONCEPTS = (("고정", "대기"), ("sleep", "wait"))
        return sum(
            0.2
            for concept in SEARCH_CONCEPTS
            if all(term in lowered_query for term in concept)
            and all(term in lowered_content for term in concept)
        )

MOCK_RESPONSE = {
  "summary": "업로드된 문서를 분석한 결과, 사용자 인증 및 파일 업로드 기능에 대한 테스트가 필요합니다.",
  "requirements": [
    {"id": "REQ-001", "text": "사용자는 유효한 GitHub URL을 입력해야 합니다."},
    {"id": "REQ-002", "text": "파일 업로드 시 20MB 이하, 허용 확장자(pdf/md/txt/docx)만 허용됩니다."}
  ],
  "testCases": [
    {
      "testCaseId": "TC-001",
      "requirementId": "REQ-001",
      "testCaseName": "유효하지 않은 GitHub URL 입력 시 오류 반환 검증",
      "testScenario": "비공개 리포지토리 URL 입력 시 백엔드 유효성 검사 동작 확인",
      "precondition": "사용자가 프로젝트 셋업 화면에 접근한 상태",
      "testSteps": "1. GitHub URL 필드에 비공개 리포지토리 주소 입력\n2. 제출 버튼 클릭\n3. 응답 상태 코드 확인",
      "expectedResult": "HTTP 400 또는 422 에러와 함께 '유효하지 않은 리포지토리' 메시지 반환",
      "priority": "HIGH",
      "confidenceLevel": "HIGH",
      "riskTags": ["#인증_실패", "#입력값_오류"],
      "evidences": [
        {
          "chunkId": "CHK-001",
          "evidenceText": "사용자는 유효한 GitHub URL을 입력해야 합니다.",
          "sourceName": "요구사항_명세서.md",
          "sourceSection": "1. 프로젝트 셋업"
        }
      ],
      "category": "test_technique",
      "technique": "Negative Testing Philosophy",
      "negativeScenario": "사용자가 임의로 비공개 혹은 비정상 리포지토리 주소를 강제 제출하여 시스템 오류나 유효성 검사 우회를 시도하는 부정 시나리오 검증.",
      "tddHint": "① Invalid Github URL Mock 객체를 주입하고 throw되는 IllegalArgumentException이 Controller 단에서 400 Bad Request로 처리되는지 assert 던질 것."
    },
    {
      "testCaseId": "TC-002",
      "requirementId": "REQ-002",
      "testCaseName": "허용되지 않는 파일 형식 업로드 차단 검증",
      "testScenario": "HWP 파일을 업로드 시도할 경우 프론트엔드 및 백엔드 이중 차단 동작 확인",
      "precondition": "프로젝트가 생성된 상태에서 문서 업로드 화면 접근",
      "testSteps": "1. .hwp 확장자 파일 선택\n2. 드래그 앤 드롭 또는 파일 선택 다이얼로그 사용\n3. 업로드 시도",
      "expectedResult": "프론트엔드에서 즉시 차단 알림 표출, 백엔드 API 도달 전에 필터링됨",
      "priority": "MEDIUM",
      "confidenceLevel": "HIGH",
      "riskTags": ["#입력값_오류", "#파일_유효성"],
      "evidences": [
        {
          "chunkId": "CHK-002",
          "evidenceText": "파일 업로드 시 20MB 이하, 허용 확장자(pdf/md/txt/docx)만 허용됩니다.",
          "sourceName": "요구사항_명세서.md",
          "sourceSection": "2. 문서 업로드"
        }
      ],
      "category": "test_technique",
      "technique": "Negative Testing Philosophy",
      "negativeScenario": "비인가된 확장자 파일(.hwp, .exe 등)을 업로드 시도하여 API 인터페이스 무결성 파괴를 시도하는 부정 시나리오 검증.",
      "tddHint": "① FileService.uploadFile 호출 시 허용되지 않는 확장자인 경우 IllegalArgumentException이 발생하는지 assertThrows로 검증할 것."
    }
  ]
}

def load_atlassian_knowledge() -> str:
    """
    Loads and formatting the Atlassian knowledge cards JSON file.
    """
    try:
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        filepath = os.path.join(base_dir, "md", "atlassian_knowledge_cards_refined.json")
        if os.path.exists(filepath):
            with open(filepath, "r", encoding="utf-8") as f:
                cards = json.load(f)
            
            knowledge_text = ""
            for card in cards:
                knowledge_text += f"- 원칙: {card.get('title')} ({card.get('technique')})\n"
                knowledge_text += f"  QA 관점: {', '.join(card.get('qa_perspective', []))}\n"
                if card.get('tdd_hint'):
                    knowledge_text += f"  TDD 힌트: {card.get('tdd_hint')}\n"
                if card.get('example_scenario'):
                    knowledge_text += f"  예시: {card.get('example_scenario')}\n"
                knowledge_text += "\n"
            return knowledge_text
    except Exception as e:
        logger.error(f"Failed to load Atlassian knowledge base: {str(e)}")
    return "Atlassian QA Standard Principles: Focus on Negative Testing, proper Test Pyramid ratios, and Refactoring Safety."

REQUIREMENT_ID_PATTERN = re.compile(r"\b((?:FR|NFR)-\d+)\b", re.IGNORECASE)

def extract_requirements(text: str) -> list[Requirement]:
    requirements = []
    seen_ids = set()
    for line in text.splitlines():
        match = REQUIREMENT_ID_PATTERN.search(line)
        if match:
            requirement_id = match.group(1).upper()
            description = re.sub(
                rf"^\s*[-*#\d.)\s]*{re.escape(match.group(0))}\s*[:\-]?\s*",
                "",
                line,
                flags=re.IGNORECASE,
            ).strip()
            if requirement_id not in seen_ids:
                requirements.append(Requirement(requirement_id, description or line.strip()))
                seen_ids.add(requirement_id)
    if requirements:
        return requirements

    paragraphs = [
        re.sub(r"^\s*[-*#\d.)\s]+", "", line).strip()
        for line in text.splitlines()
        if line.strip()
    ]
    return [
        Requirement(f"FR-{index:02d}", paragraph)
        for index, paragraph in enumerate(paragraphs, start=1)
    ]

def evidence_payload(result: SearchResult) -> dict[str, Any]:
    chunk = result.chunk
    return {
        "chunkId": chunk["chunk_id"],
        "requirementId": chunk.get("requirement_id"),
        "evidenceText": chunk["content"],
        "sourceName": chunk.get("source_name", "knowledge_base"),
        "sourceSection": chunk.get("source_section", "general")
    }

def retrieve_evidences(
    store: NumpyChunkStore, requirements: list[Requirement], top_k: int
) -> dict[str, list[SearchResult]]:
    return {
        requirement.requirement_id: store.search(requirement.text, top_k)
        for requirement in requirements
    }

def mock_generate(
    requirements: list[Requirement],
    evidence_map: dict[str, list[SearchResult]],
    backend: str = "unknown",
) -> dict[str, Any]:
    test_cases = []
    for index, requirement in enumerate(requirements, start=1):
        evidences = [evidence_payload(result) for result in evidence_map[requirement.requirement_id]]
        # Extract first line/sentence of the requirement text to make the title dynamic and realistic
        short_desc = requirement.text.strip().split("\n")[0]
        if len(short_desc) > 30:
            short_desc = short_desc[:27] + "..."
            
        test_cases.append(
            {
                "testCaseId": f"TC-{index:03d}",
                "requirementId": requirement.requirement_id,
                "testCaseName": f"[{requirement.requirement_id}] {short_desc} 검증",
                "testScenario": f"요구사항({requirement.requirement_id}): '{requirement.text}' 기능에 대한 설계 정합성 및 예외 시나리오 검증",
                "precondition": "테스트 대상 기능에 접근할 수 있고 필요한 기본 데이터가 준비되어 있다.",
                "testSteps": f"1. {requirement.requirement_id} 기능을 실행할 수 있는 화면 또는 API에 접근한다.\n2. 요구사항에 맞는 입력과 동작을 수행한다: {requirement.text}\n3. 처리 결과와 시스템 상태를 확인한다.",
                "expectedResult": f"시스템이 요구사항을 만족하여 정상 처리 완료된다: {requirement.text}",
                "priority": "HIGH",
                "confidenceLevel": "HIGH",
                "riskTags": ["#보안_검증", "#입력값_유효성"],
                "evidences": evidences,
                "category": "test_technique",
                "technique": "Negative Testing Philosophy",
                "negativeScenario": f"사용자가 요구사항({requirement.requirement_id})을 만족하지 않는 유효하지 않은 경계값 입력을 주입해 예외 처리 무결성을 확인하는 부정 검증.",
                "tddHint": f"① {requirement.requirement_id} 위반값에 대한 Exception Handling이 올바르게 설계되었는지 JUnit AssertThrows로 로직 검증."
            }
        )
    return {
        "summary": f"총 {len(requirements)}개 추출된 요구사항에 대해 로컬 지식베이스({backend}) 검색 근거를 매핑한 동적 mock 테스트 케이스입니다.",
        "requirements": [
            {"id": req.requirement_id, "text": req.text} for req in requirements
        ],
        "testCases": test_cases,
    }

global_chunk_store = None

def init_chunk_store():
    global global_chunk_store
    if global_chunk_store is not None:
        return
    try:
        chunks_path = Path(__file__).resolve().parent / "rag_chunks.jsonl"
        if chunks_path.exists():
            chunks = []
            with chunks_path.open(encoding="utf-8") as file:
                for line in file:
                    if line.strip():
                        chunks.append(json.loads(line))
            global_chunk_store = NumpyChunkStore(chunks, HashEmbedding())
            logger.info("Successfully initialized global NumpyChunkStore for local RAG mock mode.")
        else:
            logger.warning("rag_chunks.jsonl not found. Local RAG mock fallback won't perform RAG search.")
    except Exception as e:
        logger.error(f"Failed to initialize local RAG chunk store: {str(e)}", exc_info=True)

async def call_llm(document_text: str, perspectives: list, custom_prompt: str, llm_api_key: str = None) -> str:
    """
    Calls the LLM via LiteLLM. If MOCK_LLM=true, returns mock data after a short sleep.

    기본값은 false다. 기본값이 mock이면 환경변수가 빠진 환경에서 LLM vs RAG
    품질 비교가 mock 응답끼리의 비교가 되어버린다.
    """
    mock_llm = os.getenv("MOCK_LLM", "false").lower() == "true"
    
    if mock_llm:
        logger.info("MOCK_LLM is enabled. Simulating LLM call...")
        await asyncio.sleep(2.0)  # Simulate API latency
        
        if document_text and document_text.strip():
            init_chunk_store()
            if global_chunk_store:
                try:
                    requirements = extract_requirements(document_text)
                    if requirements:
                        evidence_map = retrieve_evidences(global_chunk_store, requirements, top_k=3)
                        mock_res = mock_generate(requirements, evidence_map, global_chunk_store.backend)
                        return json.dumps(mock_res, ensure_ascii=False)
                except Exception as err:
                    logger.error(f"Local RAG mock generation failed: {str(err)}", exc_info=True)
                    
        return json.dumps(MOCK_RESPONSE, ensure_ascii=False)
        
    model = os.getenv("LLM_MODEL", "gpt-4o-mini")
    logger.info(f"Calling real LLM model: {model}")
    
    atlassian_guide = load_atlassian_knowledge()
    
    system_prompt = (
        "당신은 소프트웨어 테스트 및 QA 엔지니어입니다. 입력된 소프트웨어 요구사항 명세서 본문을 바탕으로 TDD/QA 검증을 위한 테스트 케이스 목록을 생성하시오.\n\n"
        "분석 시 반드시 아래의 Atlassian QA 설계 원칙 지식을 준수하고 참고하여 분석하십시오:\n"
        f"{atlassian_guide}\n\n"
        "반드시 아래 JSON 형식으로 응답하여야 하며, 백틱(```json ... ```) 블록으로 감싸주십시오. 필수 키는 다음과 같습니다:\n"
        "{\n"
        "  \"summary\": \"전체 문서 분석 요약문\",\n"
        "  \"requirements\": [ {\"id\": \"REQ-001\", \"text\": \"요구사항 텍스트\"} ],\n"
        "  \"testCases\": [\n"
        "    {\n"
        "      \"testCaseId\": \"TC-001\",\n"
        "      \"requirementId\": \"REQ-001\",\n"
        "      \"testCaseName\": \"테스트 케이스 명칭\",\n"
        "      \"testScenario\": \"상세 검증 시나리오\",\n"
        "      \"precondition\": \"사전 조건\",\n"
        "      \"testSteps\": \"1. 단계1\\n2. 단계2\\n3. 단계3\",\n"
        "      \"expectedResult\": \"기대 결과\",\n"
        "      \"priority\": \"HIGH | MEDIUM | LOW\",\n"
        "      \"confidenceLevel\": \"HIGH | MEDIUM | LOW\",\n"
        "      \"riskTags\": [\"#태그1\", \"#태그2\"],\n"
        "      \"category\": \"test_level | test_technique | non_functional | qa_concept\",\n"
        "      \"technique\": \"적용한 Atlassian 설계 기법 명칭\",\n"
        "      \"tddHint\": \"TDD 설계 흐름 및 assert 비교 팁\",\n"
        "      \"negativeScenario\": \"Happy Path 너머의 의도적 파괴/예외 검증 시나리오 상세\",\n"
        "      \"evidences\": [\n"
        "        {\n"
          "          \"chunkId\": \"근거 식별 고유키 (예: CHK-001)\",\n"
        "          \"evidenceText\": \"요구사항 문서에서 인용한 핵심 텍스트 구절 원문\",\n"
        "          \"sourceName\": \"원본 문서 파일명 (예: 요구사항_명세서.md)\",\n"
        "          \"sourceSection\": \"인용한 장/절 이름 (예: 3.2 로그인 기능)\"\n"
        "        }\n"
        "      ]\n"
        "    }\n"
        "  ]\n"
        "}\n"
    )
    
    user_prompt = f"### 요구사항 문서 텍스트:\n{document_text}\n\n"
    if perspectives:
        user_prompt += f"### 중점 검증 관점(QA Perspectives):\n{', '.join(perspectives)}\n\n"
    if custom_prompt:
        user_prompt += f"### 사용자 추가 요청 사항(Custom Prompt):\n{custom_prompt}\n\n"
        
    user_prompt += "위 정보에 최적화하여 완벽한 JSON 구조로 응답해주세요."
 
    try:
        kwargs = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            "temperature": 0.2,
        }
        if llm_api_key and llm_api_key.strip():
            kwargs["api_key"] = llm_api_key.strip()
            logger.info("Using dynamic user-provided LLM API key.")
        else:
            logger.info("Using system-configured default LLM API key.")

        response = await litellm.acompletion(**kwargs)
        result_text = response.choices[0].message.content
        logger.info("Successfully received response from LiteLLM.")
        return result_text
    except Exception as e:
        logger.error(f"LiteLLM call failed: {str(e)}")
        raise e
