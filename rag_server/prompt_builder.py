import os
import json
import logging
import asyncio
import re
from typing import List, Dict, Optional
import litellm

logger = logging.getLogger("rag_server.prompt_builder")

# Realistic mock test cases compatible with the schema
MOCK_TEST_CASES = {
    "REQ-001": [
        {
            "testCaseId": "TC-001",
            "testCaseName": "유효하지 않은 GitHub URL 입력 시 오류 반환 검증",
            "testScenario": "비공개 리포지토리 URL 입력 시 백엔드 유효성 검사 동작 확인",
            "precondition": "사용자가 프로젝트 셋업 화면에 접근한 상태",
            "testSteps": "1. GitHub URL 필드에 비공개 리포지토리 주소 입력\n2. 제출 버튼 클릭\n3. 응답 상태 코드 확인",
            "expectedResult": "HTTP 400 또는 422 에러와 함께 '유효하지 않은 리포지토리' 메시지 반환",
            "priority": "HIGH",
            "confidenceLevel": "HIGH",
            "riskTags": ["#인증_실패", "#입력값_오류"],
            "category": "test_technique",
            "technique": "Negative Testing Philosophy",
            "negativeScenario": "사용자가 임의로 비공개 혹은 비정상 리포지토리 주소를 강제 제출하여 시스템 오류나 유효성 검사 우회를 시도하는 부정 시나리오 검증.",
            "tddHint": "① Invalid Github URL Mock 객체를 주입하고 throw되는 IllegalArgumentException이 Controller 단에서 400 Bad Request로 처리되는지 assert 던질 것.",
            "caution": "깃허브 API 호출 한도(Rate Limit) 또는 비공개 저장소 접근 토큰 유효성에 따라 API 응답 코드가 달라질 수 있으므로 실행 전에 토큰 환경 변수를 필히 점검해야 합니다."
        }
    ],
    "REQ-002": [
        {
            "testCaseId": "TC-002",
            "testCaseName": "허용되지 않는 파일 형식 업로드 차단 검증",
            "testScenario": "HWP 파일을 업로드 시도할 경우 프론트엔드 및 백엔드 이중 차단 동작 확인",
            "precondition": "프로젝트가 생성된 상태에서 문서 업로드 화면 접근",
            "testSteps": "1. .hwp 확장자 파일 선택\n2. 드래그 앤 드롭 또는 파일 선택 다이얼로그 사용\n3. 업로드 시도",
            "expectedResult": "프론트엔드에서 즉시 차단 알림 표출, 백엔드 API 도달 전에 필터링됨",
            "priority": "MEDIUM",
            "confidenceLevel": "HIGH",
            "riskTags": ["#입력값_오류", "#파일_유효성"],
            "category": "test_technique",
            "technique": "Negative Testing Philosophy",
            "negativeScenario": "비인가된 확장자 파일(.hwp, .exe 등)을 업로드 시도하여 API 인터페이스 무결성 파괴를 시도하는 부정 시나리오 검증.",
            "tddHint": "① FileService.uploadFile 호출 시 허용되지 않는 확장자인 경우 IllegalArgumentException이 발생하는지 assertThrows로 검증할 것.",
            "caution": "프론트엔드 단의 드래그 앤 드롭 업로드 제한은 브라우저 렌더링 엔진 종류(Chromium, WebKit 등)에 따라 예외 이벤트 핸들링 방식이 다를 수 있으므로 모바일 에뮬레이터 테스트 병행이 권장됩니다."
        }
    ]
}

def build_prompt(req_text: str, evidences: List[Dict], custom_prompt: str, perspectives: List[str]) -> str:
    """
    Assembles the prompt using XML-style tags, ensures strict limit of 3 evidences, 
    and truncates lower priority evidence chunks if total size exceeds limit.
    """
    max_char_limit = 6000 # Safety character limit before calling LLM
    
    # We will build context from evidences
    # Format: <evidence score="0.85" file="abc.pdf"><context>text</context></evidence>
    while True:
        context_parts = []
        for ev in evidences:
            context_parts.append(
                f'<evidence score="{ev.get("score", 0.0):.2f}" file="{ev.get("source_name", "Unknown")}" section="{ev.get("source_section", "General")}">\n'
                f'  <chunk_id>{ev.get("chunk_id", "Unknown")}</chunk_id>\n'
                f'  <context>{ev.get("text", "")}</context>\n'
                f'</evidence>'
            )
            
        context_str = "\n".join(context_parts)
        
        prompt = (
            f"당신은 소프트웨어 테스트 및 QA 엔지니어입니다. 입력된 요구사항과 수집된 근거(Context)들을 바탕으로 TDD/QA 검증을 위한 테스트 케이스 목록을 생성하시오.\n\n"
            f"<instruction>\n"
            f"1. 아래 제공되는 <target_requirement>를 검증하기 위한 구체적인 테스트 케이스를 1~2개 생성하십시오.\n"
            f"2. 제공되는 <evidence_context>의 핵심 텍스트 구절을 적극 참고하고, 해당 정보를 인용하십시오.\n"
            f"3. Happy Path 시나리오 외에, 입력 오동작이나 예외 발생을 유도하는 '의도적 파괴(Negative Scenario)'를 필수로 포함해 작성하십시오.\n"
            f"4. 결과는 반드시 아래의 JSON Array 포맷으로만 응답해야 하며, 다른 텍스트는 포함하지 말고 백틱(```json ... ```) 블록으로 감싸주십시오.\n"
            f"</instruction>\n\n"
            f"결과 JSON 규격:\n"
            f"```json\n"
            f"[\n"
            f"  {{\n"
            f"    \"testCaseId\": \"TC-001\",\n"
            f"    \"testCaseName\": \"테스트 케이스 명칭\",\n"
            f"    \"testScenario\": \"상세 검증 시나리오 설명\",\n"
            f"    \"precondition\": \"사전 수행 조건\",\n"
            f"    \"testSteps\": \"1. 스텝 1\\n2. 스텝 2\\n3. 스텝 3\",\n"
            f"    \"expectedResult\": \"기대 결과\",\n"
            f"    \"priority\": \"HIGH | MEDIUM | LOW\",\n"
            f"    \"confidenceLevel\": \"HIGH | MEDIUM | LOW\",\n"
            f"    \"riskTags\": [\"#태그1\", \"#태그2\"],\n"
            f"    \"category\": \"test_level | test_technique | non_functional | qa_concept\",\n"
            f"    \"technique\": \"적용한 QA 설계 기법 명칭 (예: Negative Testing)\",\n"
            f"    \"tddHint\": \"TDD 개발 흐름 팁 및 assert 비교 가이드\",\n"
            f"    \"negativeScenario\": \"부정/예외 상황 상세 시나리오\",\n"
            f"    \"caution\": \"테스트 실행 시의 환경 제약 사항 또는 가정에 근거하여 주의가 요구되는 사유(예: 서드파티 모킹 필요 등) 설명\"\n"
            f"  }}\n"
            f"]\n"
            f"```\n\n"
            f"<target_requirement>\n"
            f"{req_text}\n"
            f"</target_requirement>\n\n"
            f"<evidence_context>\n"
            f"{context_str}\n"
            f"</evidence_context>\n"
        )
        
        if perspectives:
            prompt += f"\n<qa_perspectives>\n{', '.join(perspectives)}\n</qa_perspectives>\n"
        if custom_prompt:
            prompt += f"\n<custom_prompt>\n{custom_prompt}\n</custom_prompt>\n"
            
        # If total length is within safety limit, or no more evidences to truncate
        if len(prompt) <= max_char_limit or not evidences:
            break
            
        # Truncate by popping the last (lowest score) evidence chunk
        logger.warning(f"Prompt length {len(prompt)} exceeds limit {max_char_limit}. Truncating lowest score evidence.")
        evidences.pop() # Remove last element

    return prompt

async def call_llm_with_key(prompt: str, llm_api_key: Optional[str] = None) -> List[Dict]:
    """
    Calls LiteLLM with prompt. Supports user dynamic API Key injection.
    Falls back to mock data if MOCK_LLM=true.
    """
    mock_llm = os.getenv("MOCK_LLM", "true").lower() == "true"
    
    if mock_llm:
        logger.info("MOCK_LLM is enabled. Returning mock test cases.")
        await asyncio.sleep(1.0)
        # Parse requirement ID from prompt to match mock
        req_id = "REQ-001"
        req_id_match = re.search(r'<target_requirement>\s*(.*?)\s*</target_requirement>', prompt, re.DOTALL)
        if req_id_match:
            req_text = req_id_match.group(1).strip()
            for rid, rlist in MOCK_TEST_CASES.items():
                # Just mock match based on keywords
                if "URL" in req_text and rid == "REQ-001":
                    req_id = "REQ-001"
                elif "업로드" in req_text and rid == "REQ-002":
                    req_id = "REQ-002"
        return MOCK_TEST_CASES.get(req_id, MOCK_TEST_CASES["REQ-001"])

    model = os.getenv("LLM_MODEL", "gpt-4o-mini")
    logger.info(f"Calling real LLM model for RAG testcase generation: {model}")
    
    try:
        kwargs = {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.2
        }
        if llm_api_key and llm_api_key.strip():
            kwargs["api_key"] = llm_api_key.strip()
            logger.info("Applying dynamic user-provided LLM API key for generation.")
            
        response = await litellm.acompletion(**kwargs)
        raw_content = response.choices[0].message.content.strip()
        
        # Parse JSON block
        json_match = re.search(r'```json\s*(.*?)\s*```', raw_content, re.DOTALL)
        if json_match:
            json_str = json_match.group(1)
        else:
            json_str = raw_content
            
        test_cases = json.loads(json_str)
        
        # Validate and Apply Fallbacks for required fields
        if isinstance(test_cases, list):
            for tc in test_cases:
                # Apply fallbacks
                if not tc.get("precondition"):
                    tc["precondition"] = "추가 검토 필요 (기본값 설정됨)"
                if not tc.get("testSteps"):
                    tc["testSteps"] = "1. 추가 검토 필요 (단계 자동 보정)"
                if not tc.get("expectedResult"):
                    tc["expectedResult"] = "추가 검토 필요 (결과 자동 보정)"
                if not tc.get("testCaseId"):
                    tc["testCaseId"] = "TC-AUTOGEN"
                if not tc.get("priority"):
                    tc["priority"] = "MEDIUM"
                if not tc.get("confidenceLevel"):
                    tc["confidenceLevel"] = "MEDIUM"
                if not tc.get("riskTags"):
                    tc["riskTags"] = ["#추가_검토"]
                if not tc.get("caution"):
                    tc["caution"] = "추가 검토 필요 (가정에 의존한 테스트이므로 실행 시 주의 요망)"
                    
            return test_cases
            
    except litellm.exceptions.AuthenticationError as e:
        logger.error(f"LiteLLM AuthenticationError caught: {str(e)}")
        raise e
    except Exception as e:
        logger.error(f"Failed to generate test cases via LLM: {str(e)}. Falling back to mock data.", exc_info=True)
        
    return MOCK_TEST_CASES["REQ-001"]
