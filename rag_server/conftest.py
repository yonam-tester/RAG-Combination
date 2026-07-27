"""테스트 공통 픽스처.

Qdrant는 qdrant-client의 로컬 모드(:memory:)로, 임베딩은 결정적 가짜 임베딩으로
대체해 외부 컨테이너나 모델 다운로드 없이 실제 코드 경로를 검증한다.
"""
import os
import sys
from unittest.mock import AsyncMock, MagicMock

import pytest
from langchain_core.embeddings import DeterministicFakeEmbedding

os.environ["QDRANT_URL"] = ":memory:"
os.environ["QDRANT_COLLECTION"] = "yeonam_knowledge_test"
os.environ["MOCK_LLM"] = "true"

# asyncio 큐 워커는 테스트 대상이 아니므로 모듈 단위로 대체한다.
_qm = MagicMock()
_qm.queue_manager.queue.qsize.return_value = 0
_qm.queue_manager.start_worker = MagicMock()
_qm.queue_manager.stop_worker = AsyncMock()
sys.modules.setdefault("queue_manager", _qm)


@pytest.fixture
def fake_embeddings():
    return DeterministicFakeEmbedding(size=384)


@pytest.fixture
def memory_vector_store(monkeypatch, fake_embeddings):
    """vector_store 싱글턴을 로컬 모드 Qdrant + 가짜 임베딩으로 교체한다."""
    import vector_store

    monkeypatch.setattr(vector_store, "build_embeddings", lambda: fake_embeddings)
    vector_store.reset_vector_store()
    store = vector_store.get_vector_store()
    yield store
    vector_store.reset_vector_store()
