"""requirement_extractor 단위 테스트.

핵심 관심사: 실패했을 때 문서와 무관한 하드코딩 요구사항(MOCK_REQUIREMENTS)을
정상 결과로 돌려주지 않는 것. 그렇게 되면 사용자는 자기 문서를 분석한 결과라고
믿으면서 "GitHub URL 검증", "파일 업로드 제한" 테스트케이스를 받는다.
"""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import requirement_extractor
from requirement_extractor import extract_requirements


def _documents():
    return [{"file_name": "spec.pdf", "text": "로그인 실패 시 401을 반환해야 한다."}]


def _litellm_response(content: str):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content))]
    )


@pytest.mark.asyncio
async def test_mock_llm_true_returns_mock_requirements(monkeypatch):
    """명시적으로 켰을 때는 mock을 쓴다 — 로컬 개발/테스트용 경로는 유지한다."""
    monkeypatch.setenv("MOCK_LLM", "true")

    result = await extract_requirements(_documents())

    assert result == requirement_extractor.MOCK_REQUIREMENTS


@pytest.mark.asyncio
async def test_mock_is_disabled_when_env_unset(monkeypatch):
    monkeypatch.delenv("MOCK_LLM", raising=False)
    acompletion = AsyncMock(return_value=_litellm_response(
        '```json\n[{"id":"REQ-001","text":"로그인 실패 시 401을 반환한다"}]\n```'
    ))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    result = await extract_requirements(_documents())

    assert acompletion.await_count == 1
    assert result == [{"id": "REQ-001", "text": "로그인 실패 시 401을 반환한다"}]


@pytest.mark.asyncio
async def test_llm_failure_raises_instead_of_returning_mock(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    monkeypatch.setattr(
        "litellm.acompletion", AsyncMock(side_effect=RuntimeError("upstream 503"))
    )

    with pytest.raises(RuntimeError, match="upstream 503"):
        await extract_requirements(_documents())


@pytest.mark.asyncio
async def test_unparseable_response_raises_instead_of_returning_mock(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    monkeypatch.setattr(
        "litellm.acompletion",
        AsyncMock(return_value=_litellm_response("JSON이 전혀 아닌 응답")),
    )

    with pytest.raises(ValueError, match="요구사항"):
        await extract_requirements(_documents())


@pytest.mark.asyncio
async def test_empty_requirement_list_raises_instead_of_returning_mock(monkeypatch):
    """LLM이 빈 배열을 주면 mock으로 채우지 않는다."""
    monkeypatch.setenv("MOCK_LLM", "false")
    monkeypatch.setattr(
        "litellm.acompletion", AsyncMock(return_value=_litellm_response("```json\n[]\n```"))
    )

    with pytest.raises(ValueError, match="요구사항"):
        await extract_requirements(_documents())


@pytest.mark.asyncio
async def test_no_parsed_documents_returns_empty_not_mock(monkeypatch):
    """파싱된 문서가 없으면 빈 목록을 반환한다 — 가짜 요구사항을 만들어내지 않는다."""
    monkeypatch.setenv("MOCK_LLM", "false")
    acompletion = AsyncMock()
    monkeypatch.setattr("litellm.acompletion", acompletion)

    result = await extract_requirements([])

    assert result == []
    assert acompletion.await_count == 0
