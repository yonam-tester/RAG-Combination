#!/usr/bin/env python3
import json
import re
from collections import Counter
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent
OUTPUT_PATH = BASE_DIR.parent / "rag_chunks.jsonl"
SUMMARY_PATH = BASE_DIR.parent / "parsing_summary.md"

# 지식카드의 단일 출처는 knowledge_base/다. ingestion.py의 폴백 경로도 같은 곳을 읽는다.
# 이전에는 embedded/ 안의 복사본을 "*_knowledge_cards.json"으로 globbing했는데,
# 그 결과 (1) embedded/에 복사되지 않은 ms_playbook과
# (2) 파일명이 패턴에 안 맞는 atlassian_..._refined.json이 조용히 누락됐다.
INPUT_DIR = BASE_DIR.parent / "knowledge_base"
INPUT_PATHS = sorted(INPUT_DIR.glob("*.json"))
MIN_CONTENT_LENGTH = 300
MAX_CONTENT_LENGTH = 800

FIELD_LABELS = [
    ("title", "제목"),
    ("category", "카테고리"),
    ("technique", "테스트 기법"),
    ("testing_type", "테스트 유형"),
    ("quality_attribute", "품질 속성"),
    ("definition", "설명"),
    ("condition", "조건"),
    ("description", "상세 설명"),
    ("apply_when", "적용 조건"),
    ("qa_perspective", "QA 관점"),
    ("expected_result", "예상 결과"),
    ("risk_type", "위험 유형"),
    ("tdd_hint", "TDD 힌트"),
    ("example", "예시"),
    ("example_scenario", "예시 시나리오"),
    ("evidence", "근거"),
    ("source_name", "출처"),
    ("source_url", "출처 URL"),
]


def render_value(label, value):
    if value is None or value == "" or value == []:
        return []
    if isinstance(value, list):
        return [f"{label}: {item}" for item in value if item not in (None, "")]
    return [f"{label}: {value}"]


def split_long_segment(segment):
    if len(segment) <= MAX_CONTENT_LENGTH:
        return [segment]
    parts = re.split(r"(?<=[.!?。])\s+", segment)
    if len(parts) == 1:
        return [
            segment[index:index + MAX_CONTENT_LENGTH]
            for index in range(0, len(segment), MAX_CONTENT_LENGTH)
        ]
    return pack_segments(parts)


def pack_segments(segments):
    chunks = []
    current = []
    for segment in segments:
        for part in split_long_segment(segment):
            candidate = " ".join([*current, part])
            if current and len(candidate) > MAX_CONTENT_LENGTH:
                chunks.append(current)
                current = [part]
            else:
                current.append(part)
    if current:
        chunks.append(current)

    for index in range(len(chunks) - 1, 0, -1):
        while len(" ".join(chunks[index])) < MIN_CONTENT_LENGTH and len(chunks[index - 1]) > 1:
            moved = chunks[index - 1][-1]
            shorter_previous = " ".join(chunks[index - 1][:-1])
            longer_current = " ".join([moved, *chunks[index]])
            if len(shorter_previous) < MIN_CONTENT_LENGTH or len(longer_current) > MAX_CONTENT_LENGTH:
                break
            chunks[index - 1].pop()
            chunks[index].insert(0, moved)

    return [" ".join(chunk) for chunk in chunks]


def extract_requirement_id(card):
    serialized = json.dumps(card, ensure_ascii=False)
    match = re.search(r"\b(?:FR|NFR)-\d+\b", serialized, re.IGNORECASE)
    return match.group(0).upper() if match else None


def extract_keywords(card):
    keywords = []
    for field in ("category", "technique", "testing_type", "quality_attribute"):
        value = card.get(field)
        values = value if isinstance(value, list) else [value]
        for item in values:
            if item not in (None, "") and item not in keywords:
                keywords.append(str(item))
    return keywords


def make_content_chunks(card):
    segments = []
    for field, label in FIELD_LABELS:
        segments.extend(render_value(label, card.get(field)))
    return pack_segments(segments)


def validate(chunks):
    serialized = [json.dumps(chunk, ensure_ascii=False) for chunk in chunks]
    parsed = [json.loads(line) for line in serialized]
    ids = [chunk["chunk_id"] for chunk in parsed]
    lengths = [len(chunk["content"]) for chunk in parsed]
    return serialized, {
        "line_count": len(parsed),
        "json_parsing_success": len(parsed) == len(serialized),
        "empty_content_count": sum(not chunk["content"].strip() for chunk in parsed),
        "duplicate_chunk_id_count": len(ids) - len(set(ids)),
        "average_content_length": round(sum(lengths) / len(lengths), 2) if lengths else 0,
        "minimum_content_length": min(lengths, default=0),
        "maximum_content_length": max(lengths, default=0),
    }


def main():
    chunks = []
    source_counts = Counter()
    card_count = 0

    for input_path in INPUT_PATHS:
        cards = json.loads(input_path.read_text(encoding="utf-8"))
        if not isinstance(cards, list):
            raise ValueError(f"{input_path.name}: top-level JSON value must be an array")
        card_count += len(cards)
        for card in cards:
            if not isinstance(card, dict):
                raise ValueError(f"{input_path.name}: every array item must be an object")
            content_chunks = make_content_chunks(card)
            if not content_chunks:
                raise ValueError(f"{input_path.name}: card has no embeddable content")
            for content in content_chunks:
                chunk_order = len(chunks) + 1
                chunks.append({
                    "chunk_id": f"QA-{chunk_order:04d}",
                    "document_type": "QA_KNOWLEDGE",
                    "source_file": input_path.name,
                    "requirement_id": extract_requirement_id(card),
                    "section_title": str(card.get("title") or card.get("technique") or card.get("category") or "제목 없음"),
                    "chunk_type": "qa_knowledge",
                    "content": content,
                    "chunk_order": chunk_order,
                    "metadata": {
                        "page": None,
                        "keywords": extract_keywords(card),
                        "created_for": "rag_embedding",
                    },
                })
                source_counts[input_path.name] += 1

    serialized, result = validate(chunks)
    OUTPUT_PATH.write_text("\n".join(serialized) + "\n", encoding="utf-8")

    source_lines = "\n".join(
        f"- `{source}`: {source_counts[source]} chunks"
        for source in sorted(source_counts)
    )
    SUMMARY_PATH.write_text(
        "# Parsing Summary\n\n"
        "## Input\n"
        f"- Input files: {len(INPUT_PATHS)}\n"
        f"- JSON objects: {card_count}\n"
        "- Document type: `QA_KNOWLEDGE`\n\n"
        "## Output\n"
        f"- File: `{OUTPUT_PATH.name}`\n"
        f"- Total lines: {result['line_count']}\n"
        f"- JSON parsing success: `{str(result['json_parsing_success']).lower()}`\n"
        f"- Empty content count: {result['empty_content_count']}\n"
        f"- Duplicate chunk_id count: {result['duplicate_chunk_id_count']}\n"
        f"- Average content length: {result['average_content_length']}\n"
        f"- Minimum content length: {result['minimum_content_length']}\n"
        f"- Maximum content length: {result['maximum_content_length']}\n\n"
        "## Chunks By Source\n"
        f"{source_lines}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
