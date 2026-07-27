"""text_chunker 단위 테스트."""
from langchain_core.documents import Document

from text_chunker import build_section_documents, chunk_document, clean_text, detect_section_title


def test_clean_text_removes_page_markers_and_page_numbers():
    raw = "--- Page 1 ---\n서론 내용\n1 / 5\n본문 내용\n42\n"

    assert clean_text(raw) == "서론 내용\n본문 내용"


def test_detect_section_title_recognises_korean_chapter():
    assert detect_section_title("제 2 장 시스템 요구사항") == "제 2 장 시스템 요구사항"
    assert detect_section_title("## 개요") == "## 개요"
    assert detect_section_title("1.1 로그인") == "1.1 로그인"
    assert detect_section_title("그냥 평범한 본문 문장입니다") is None


def test_build_section_documents_tags_each_paragraph_with_current_section():
    raw = "1. 로그인\n\n로그인 기능 설명\n\n2. 회원가입\n\n회원가입 기능 설명"

    docs = build_section_documents(raw, "FILE-A", "spec.pdf")

    sections = [d.metadata["section_title"] for d in docs]
    assert sections == ["1. 로그인", "1. 로그인", "2. 회원가입", "2. 회원가입"]
    assert all(d.metadata["file_id"] == "FILE-A" for d in docs)
    assert all(d.metadata["file_name"] == "spec.pdf" for d in docs)


def test_chunk_document_returns_documents_with_required_metadata():
    raw = "1. 개요\n\n" + ("요구사항 문장입니다. " * 200)

    chunks = chunk_document(raw, "FILE-A", "spec.pdf")

    assert len(chunks) > 1
    for chunk in chunks:
        assert isinstance(chunk, Document)
        assert set(chunk.metadata) >= {"chunk_id", "file_id", "file_name", "section_title", "source_type"}
        assert chunk.metadata["source_type"] == "document"
        assert chunk.metadata["file_id"] == "FILE-A"


def test_chunk_document_respects_max_chunk_size():
    raw = "요구사항 문장입니다. " * 500

    chunks = chunk_document(raw, "FILE-A", "spec.pdf")

    assert all(len(c.page_content) <= 1000 for c in chunks)


def test_chunk_ids_are_deterministic_across_runs():
    raw = "1. 개요\n\n" + ("요구사항 문장입니다. " * 200)

    first = [c.metadata["chunk_id"] for c in chunk_document(raw, "FILE-A", "spec.pdf")]
    second = [c.metadata["chunk_id"] for c in chunk_document(raw, "FILE-A", "spec.pdf")]

    assert first == second
    assert first[0] == "CHNK-FILE-A-0000"


def test_chunk_document_on_empty_text_returns_empty_list():
    assert chunk_document("   \n\n  ", "FILE-A", "spec.pdf") == []
