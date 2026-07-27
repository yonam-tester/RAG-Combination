import logging
import re
from typing import List, Optional

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

logger = logging.getLogger("rag_server.text_chunker")

CHUNK_SIZE = 1000
CHUNK_OVERLAP = 150

# 한국어 문서를 문단 -> 줄 -> 문장 -> 어절 순으로 자연스럽게 자르기 위한 구분자 우선순위.
_SPLITTER = RecursiveCharacterTextSplitter(
    chunk_size=CHUNK_SIZE,
    chunk_overlap=CHUNK_OVERLAP,
    separators=["\n\n", "\n", ". ", "。", "! ", "? ", " ", ""],
    keep_separator=False,
)


def clean_text(text: str) -> str:
    """
    Cleans document text by removing noise, extra whitespaces,
    and merging single/double character items with surrounding text.
    """
    # Remove continuous empty lines or whitespaces
    text = re.sub(r'\n\s*\n', '\n\n', text)

    # Remove page boundary markers introduced by parser if we don't need them
    text = re.sub(r'--- Page \d+ ---\n?', '', text)

    lines = text.split('\n')
    cleaned_lines = []

    for line in lines:
        stripped = line.strip()
        # Filter out header/footer patterns like page numbers at the bottom (e.g. "Page 1 of 5", "1 / 5", or lone numbers)
        if re.match(r'^\d+\s*/\s*\d+$', stripped) or re.match(r'^Page \d+$', stripped) or re.match(r'^\d+$', stripped):
            continue
        # Merge very short standalone line items (1-2 characters) to avoid fragmenting
        if len(stripped) > 0:
            cleaned_lines.append(stripped)

    return "\n".join(cleaned_lines)


def detect_section_title(line: str) -> Optional[str]:
    """
    Detects if a line is a section or chapter header.
    """
    # Markdown headers, numbered list headers (e.g. 1. Intro, 제 1 장, 1.1 개요)
    line = line.strip()
    if line.startswith('#') or re.match(r'^(제\s*\d+\s*[장절]|I+|V|X|\d+(\.\d+)*)\b', line):
        # Limit title length to prevent capturing long lines
        if len(line) < 100:
            return line
    return None


def build_section_documents(raw_text: str, file_id: str, file_name: str) -> List[Document]:
    """한국어 전처리 단계.

    노이즈를 제거하고 문단 단위로 순회하며 현재 섹션 제목을 추적해,
    각 문단을 섹션 메타데이터가 붙은 Document로 만든다.
    길이 기반 분할은 여기서 하지 않는다 — chunk_document가 splitter로 처리한다.

    주의: clean_text()는 빈 줄을 전부 제거하고 단일 "\\n"으로 다시 이어붙이므로
    그 출력에는 문단 구분자("\\n\\n")가 남지 않는다. 따라서 문단 경계는 원본
    raw_text에서 먼저 나눈 뒤, 각 문단을 개별적으로 clean_text에 통과시킨다.
    """
    documents: List[Document] = []
    current_section = "General"

    for raw_paragraph in re.split(r'\n\s*\n', raw_text):
        paragraph = clean_text(raw_paragraph).strip()
        if not paragraph:
            continue

        detected = detect_section_title(paragraph.split("\n")[0])
        if detected:
            current_section = detected

        documents.append(
            Document(
                page_content=paragraph,
                metadata={
                    "file_id": file_id,
                    "file_name": file_name,
                    "section_title": current_section,
                    "source_type": "document",
                },
            )
        )

    return documents


def chunk_document(raw_text: str, file_id: str, file_name: str) -> List[Document]:
    """전처리된 섹션 Document를 길이 기반으로 분할하고 결정적 chunk_id를 부여한다.

    chunk_id가 (file_id, 순번)만으로 결정되므로 같은 파일을 재처리하면
    Qdrant에서 같은 point를 덮어쓴다.
    """
    sections = build_section_documents(raw_text, file_id, file_name)
    chunks = _SPLITTER.split_documents(sections)

    for index, chunk in enumerate(chunks):
        chunk.metadata["chunk_id"] = f"CHNK-{file_id}-{index:04d}"

    logger.info(f"Generated {len(chunks)} chunks for file {file_name} ({file_id})")
    return chunks
