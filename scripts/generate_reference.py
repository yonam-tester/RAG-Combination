#!/usr/bin/env python3
"""일회성 실행: 각 요구사항에 대한 참조 테스트케이스를 OpenAI로 생성하여 cases.jsonl에 저장."""
import json
import os
import httpx

LLM_API_KEY = os.environ["LLM_API_KEY"]
CASES_PATH = os.path.join(os.path.dirname(__file__), "../evaluation/ground_truth/cases.jsonl")

SYSTEM_PROMPT = (
    "당신은 소프트웨어 QA 전문가입니다. "
    "주어진 요구사항에 대해 이상적인 테스트케이스를 간결한 텍스트로 작성하세요. "
    "JSON 형식 없이 자연어로 작성하며, 검증 조건과 예외 케이스를 포함합니다. "
    "200자 이내로 작성하세요."
)


def generate_reference(input_doc: str) -> str:
    resp = httpx.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {LLM_API_KEY}"},
        json={
            "model": "gpt-4o-mini",
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": input_doc},
            ],
            "temperature": 0.2,
            "max_tokens": 300,
        },
        timeout=30.0,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"].strip()


def main():
    cases = []
    with open(CASES_PATH) as f:
        for line in f:
            if line.strip():
                cases.append(json.loads(line))

    updated = False
    for case in cases:
        if not case.get("reference_test_case", "").strip():
            print(f"Generating reference for {case['id']}...")
            case["reference_test_case"] = generate_reference(case["input_doc"])
            updated = True
            print(f"  -> {case['reference_test_case'][:80]}...")

    if updated:
        with open(CASES_PATH, "w") as f:
            for case in cases:
                f.write(json.dumps(case, ensure_ascii=False) + "\n")
        print("cases.jsonl 업데이트 완료.")
    else:
        print("모든 케이스에 reference_test_case가 이미 존재합니다.")


if __name__ == "__main__":
    main()
