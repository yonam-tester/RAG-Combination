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
    If MOCK_LLM=true or MOCK_RAG=true, returns MOCK_REQUIREMENTS.
    """
    mock_llm = os.getenv("MOCK_LLM", "true").lower() == "true"
    mock_rag = os.getenv("MOCK_RAG", "true").lower() == "true"
    
    if mock_llm or mock_rag or not parsed_documents:
        logger.info("MOCK_LLM or MOCK_RAG is active, or parsed documents are empty. Returning mock requirements.")
        return MOCK_REQUIREMENTS

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
    
    try:
        # Use litellm with dynamic api key if provided
        kwargs = {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.2
        }
        if llm_api_key:
            kwargs["api_key"] = llm_api_key
            
        response = await litellm.acompletion(**kwargs)
        raw_content = response.choices[0].message.content.strip()
        
        # Extract JSON block
        json_match = re.search(r'```json\s*(.*?)\s*```', raw_content, re.DOTALL)
        if json_match:
            json_str = json_match.group(1)
        else:
            json_str = raw_content
            
        requirements = json.loads(json_str)
        if isinstance(requirements, list) and len(requirements) > 0:
            logger.info(f"Successfully extracted {len(requirements)} requirements from document.")
            return requirements
            
    except Exception as e:
        logger.error(f"Failed to extract requirements via LLM: {str(e)}. Falling back to mocks.", exc_info=True)
        
    return MOCK_REQUIREMENTS
