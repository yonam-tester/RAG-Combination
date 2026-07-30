"""prompt_builder 단위 테스트."""
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from langchain_core.exceptions import OutputParserException

import prompt_builder
from prompt_builder import build_prompt, call_llm_with_key


def _evidence(chunk_id="CHNK-0001", score=0.9):
    return {
        "chunk_id": chunk_id,
        "text": "로그인 실패 시 401을 반환한다",
        "source_name": "spec.pdf",
        "source_section": "1. 인증",
        "score": score,
    }


def _litellm_response(content: str):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content))]
    )


def test_build_prompt_embeds_evidence_metadata():
    prompt = build_prompt("로그인 요구사항", [_evidence()], "", [])

    assert '<evidence score="0.90" file="spec.pdf" section="1. 인증">' in prompt
    assert "<chunk_id>CHNK-0001</chunk_id>" in prompt
    assert "<target_requirement>\n로그인 요구사항\n</target_requirement>" in prompt


def test_build_prompt_appends_perspectives_and_custom_prompt():
    prompt = build_prompt("요구사항", [], "보안 위주로", ["보안", "성능"])

    assert "<qa_perspectives>\n보안, 성능\n</qa_perspectives>" in prompt
    assert "<custom_prompt>\n보안 위주로\n</custom_prompt>" in prompt


def test_apply_field_fallbacks_fills_missing_fields():
    result = prompt_builder._apply_field_fallbacks([{"testCaseName": "이름만 있는 케이스"}])

    tc = result[0]
    assert tc["precondition"] == "추가 검토 필요 (기본값 설정됨)"
    assert tc["testSteps"] == "1. 추가 검토 필요 (단계 자동 보정)"
    assert tc["expectedResult"] == "추가 검토 필요 (결과 자동 보정)"
    assert tc["testCaseId"] == "TC-AUTOGEN"
    assert tc["priority"] == "MEDIUM"
    assert tc["confidenceLevel"] == "MEDIUM"
    assert tc["riskTags"] == ["#추가_검토"]
    assert tc["caution"].startswith("추가 검토 필요")


def test_apply_field_fallbacks_preserves_provided_values():
    result = prompt_builder._apply_field_fallbacks([{"testCaseId": "TC-777", "priority": "HIGH"}])

    assert result[0]["testCaseId"] == "TC-777"
    assert result[0]["priority"] == "HIGH"


def test_apply_field_fallbacks_rejects_non_list():
    with pytest.raises(ValueError, match="JSON 배열이 아닙니다"):
        prompt_builder._apply_field_fallbacks({"testCaseId": "TC-1"})


@pytest.mark.asyncio
async def test_mock_llm_returns_mock_test_cases(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "true")

    result = await call_llm_with_key(build_prompt("URL 유효성 요구사항", [], "", []))

    assert result == prompt_builder.MOCK_TEST_CASES["REQ-001"]


@pytest.mark.asyncio
async def test_chain_parses_json_fenced_llm_response(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    payload = [{"testCaseId": "TC-001", "testCaseName": "로그인 실패 검증"}]
    acompletion = AsyncMock(return_value=_litellm_response(
        "설명 문장\n```json\n" + json.dumps(payload, ensure_ascii=False) + "\n```"
    ))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    result = await call_llm_with_key("프롬프트")

    assert result[0]["testCaseId"] == "TC-001"
    assert result[0]["testCaseName"] == "로그인 실패 검증"
    # JsonOutputParser 통과 후 필드 보정이 적용된다
    assert result[0]["priority"] == "MEDIUM"


@pytest.mark.asyncio
async def test_dynamic_api_key_reaches_litellm(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    acompletion = AsyncMock(return_value=_litellm_response('```json\n[{"testCaseId":"TC-1"}]\n```'))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    await call_llm_with_key("프롬프트", llm_api_key="sk-dynamic-key")

    assert acompletion.await_count == 1
    assert acompletion.await_args.kwargs.get("api_key") == "sk-dynamic-key"


@pytest.mark.asyncio
async def test_unparseable_response_raises_instead_of_returning_mock(monkeypatch):
    """LLM 응답을 파싱할 수 없으면 실패로 전파해야 한다.

    mock 테스트케이스를 조용히 돌려주면 사용자는 실제 문서와 무관한 가짜 결과를
    정상 분석 결과로 받게 된다. 실패는 실패로 드러나야 한다.
    """
    monkeypatch.setenv("MOCK_LLM", "false")
    acompletion = AsyncMock(return_value=_litellm_response("JSON이 전혀 아닌 응답"))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    with pytest.raises(OutputParserException):
        await call_llm_with_key("프롬프트")


@pytest.mark.asyncio
async def test_upstream_error_raises_instead_of_returning_mock(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    monkeypatch.setattr(
        "litellm.acompletion", AsyncMock(side_effect=RuntimeError("upstream 503"))
    )

    with pytest.raises(RuntimeError, match="upstream 503"):
        await call_llm_with_key("프롬프트")


@pytest.mark.asyncio
async def test_mock_is_disabled_when_env_unset(monkeypatch):
    """MOCK_LLM 미설정 시 기본값은 실제 LLM 호출이어야 한다.

    기본값이 mock이면 운영 환경에서 환경변수 하나가 빠졌을 때 가짜 결과를
    정상 응답으로 내보낸다.
    """
    monkeypatch.delenv("MOCK_LLM", raising=False)
    acompletion = AsyncMock(return_value=_litellm_response('```json\n[{"testCaseId":"TC-1"}]\n```'))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    result = await call_llm_with_key("프롬프트")

    assert acompletion.await_count == 1
    assert result != prompt_builder.MOCK_TEST_CASES["REQ-001"]


@pytest.mark.asyncio
async def test_authentication_error_is_reraised(monkeypatch):
    import litellm

    monkeypatch.setenv("MOCK_LLM", "false")
    error = litellm.exceptions.AuthenticationError(
        message="invalid key", llm_provider="openai", model="gpt-4o-mini"
    )
    monkeypatch.setattr("litellm.acompletion", AsyncMock(side_effect=error))

    with pytest.raises(litellm.exceptions.AuthenticationError):
        await call_llm_with_key("프롬프트")
