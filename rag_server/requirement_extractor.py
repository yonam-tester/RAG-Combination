import os
import json
import logging
import re
from typing import List, Dict, Optional
import litellm

logger = logging.getLogger("rag_server.requirement_extractor")

MOCK_REQUIREMENTS = [
    {"id": "REQ-001", "text": "사용자는 유효한 GitHub URL을 입력해야 합니다."},
    {"id": "REQ-002", "text": "파일 업로드 시 20MB 이하, 허용 확장자(pdf/md/txt/docx)만 허용됩니다."}
]

async def extract_requirements(parsed_documents: List[Dict], llm_api_key: Optional[str] = None) -> List[Dict]:
    """
    Extracts testable software requirements from the parsed documents.

    MOCK_LLM=true일 때만 MOCK_REQUIREMENTS를 반환한다. 기본값은 false다.
    추출 실패는 전파한다 — mock으로 폴백하면 사용자가 업로드한 문서와 전혀 무관한
    하드코딩 요구사항으로 테스트케이스가 생성되고, 그것이 정상 결과로 보고된다.
    """
    if os.getenv("MOCK_LLM", "false").lower() == "true":
        logger.info("MOCK_LLM is active. Returning mock requirements.")
        return MOCK_REQUIREMENTS

    if not parsed_documents:
        logger.warning("파싱된 문서가 없어 요구사항을 추출하지 않는다.")
        return []

    # Combine document texts for analysis (limit content length if too long)
    full_text = ""
    for doc in parsed_documents:
        full_text += f"\n\n[문서명: {doc['file_name']}]\n{doc['text']}"
    
    # Truncate text to fit context window safely
    full_text = full_text[:12000]
    
    prompt = f"""
당신은 소프트웨어 명세서 분석 전문가입니다. 다음 소프트웨어 설계/요구사항 문서에서 테스트 및 검증이 가능한 핵심 기능적 요구사항들을 추출하십시오.
각 요구사항은 사용자 작업 흐름이나 시스템의 입력 제약사항 등 '테스트 케이스를 도출해낼 수 있는 구체적인 문장'이어야 합니다.

결과는 반드시 아래의 JSON Array 포맷으로만 응답해야 하며, 다른 텍스트는 포함하지 마십시오.
```json
[
  {{"id": "REQ-001", "text": "요구사항 설명 텍스트"}},
  {{"id": "REQ-002", "text": "요구사항 설명 텍스트"}}
]
```

---
[문서 내용]
{full_text}
"""

    model = os.getenv("LLM_MODEL", "gpt-4o-mini")
    logger.info(f"Extracting requirements using LLM model '{model}'...")
    
    # Use litellm with dynamic api key if provided
    kwargs = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2
    }
    if llm_api_key:
        kwargs["api_key"] = llm_api_key

    try:
        response = await litellm.acompletion(**kwargs)
    except Exception as e:
        logger.error(f"Failed to extract requirements via LLM: {str(e)}", exc_info=True)
        raise

    raw_content = response.choices[0].message.content.strip()

    # Extract JSON block
    json_match = re.search(r'```json\s*(.*?)\s*```', raw_content, re.DOTALL)
    json_str = json_match.group(1) if json_match else raw_content

    try:
        requirements = json.loads(json_str)
    except json.JSONDecodeError as e:
        logger.error(f"요구사항 추출 응답을 JSON으로 파싱할 수 없다: {raw_content[:200]}")
        raise ValueError(f"요구사항 추출 응답이 JSON이 아닙니다: {e}") from e

    if not isinstance(requirements, list) or not requirements:
        logger.error(f"요구사항 추출 결과가 비어 있거나 배열이 아니다: {raw_content[:200]}")
        raise ValueError("요구사항을 한 건도 추출하지 못했습니다. 문서 내용을 확인해 주세요.")

    logger.info(f"Successfully extracted {len(requirements)} requirements from document.")
    return requirements
