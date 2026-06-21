#!/usr/bin/env python3
"""LLM vs RAG 비교 평가 스크립트. EC2 cron 및 GitHub Actions에서 실행."""
import json
import os
import time
import httpx
from prometheus_client import CollectorRegistry, Gauge, push_to_gateway

LLM_SERVER_URL = os.getenv("LLM_SERVER_URL", "http://localhost:8001")
RAG_SERVER_URL = os.getenv("RAG_SERVER_URL", "http://localhost:8000")
PUSHGATEWAY_URL = os.getenv("PUSHGATEWAY_URL", "http://localhost:9091")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
CASES_PATH = os.path.join(os.path.dirname(__file__), "ground_truth", "cases.jsonl")
JUDGE_PROMPT_PATH = os.path.join(os.path.dirname(__file__), "llm_judge_prompt.txt")


def load_cases() -> list[dict]:
    with open(CASES_PATH) as f:
        return [json.loads(line) for line in f if line.strip()]


def load_judge_prompt() -> str:
    with open(JUDGE_PROMPT_PATH) as f:
        return f.read()


def call_service(url: str, text: str, perspectives: list[str]) -> dict:
    try:
        response = httpx.post(
            f"{url}/api/eval/generate",
            json={"text": text, "perspectives": perspectives, "llm_api_key": LLM_API_KEY},
            timeout=120.0
        )
        response.raise_for_status()
        return response.json()
    except Exception as e:
        print(f"Service call failed ({url}): {e}")
        return {"output": "", "token_count": 0, "duration_ms": 0}


def keyword_accuracy(output: str, keywords: list[str]) -> float:
    if not keywords:
        return 0.0
    output_lower = output.lower()
    matched = sum(1 for kw in keywords if kw.lower() in output_lower)
    return round(matched / len(keywords), 4)


def judge_score(requirement: str, output: str, judge_prompt_template: str) -> float:
    """LLM-as-Judge: OpenAI API 직접 호출로 채점 (서버 파이프라인 우회)."""
    if not output.strip():
        return 0.0
    prompt = judge_prompt_template.replace("{requirement}", requirement).replace("{output}", output[:2000])
    try:
        response = httpx.post(
            "https://api.openai.com/v1/chat/completions",
            headers={"Authorization": f"Bearer {LLM_API_KEY}"},
            json={
                "model": "gpt-4o-mini",
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.1,
                "max_tokens": 50,
            },
            timeout=30.0,
        )
        response.raise_for_status()
        raw = response.json()["choices"][0]["message"]["content"] or ""
        for line in raw.splitlines():
            if line.startswith("SCORE:"):
                score = float(line.split(":")[1].strip())
                return min(max(score, 0.0), 10.0)
    except Exception as e:
        print(f"Judge scoring failed: {e}")
    return 0.0


def push_metrics(
    llm_accuracy: float,
    rag_accuracy: float,
    llm_judge: float,
    rag_judge: float,
    llm_tokens: float,
    rag_tokens: float,
    coverage_backend: float,
    coverage_rag: float,
    coverage_llm: float,
) -> None:
    registry = CollectorRegistry()

    # 같은 이름의 Gauge는 한 번만 생성 후 labels()로 각 값 설정
    accuracy_gauge = Gauge('eval_accuracy', 'Keyword matching accuracy', ['mode'], registry=registry)
    accuracy_gauge.labels(mode='llm').set(llm_accuracy)
    accuracy_gauge.labels(mode='rag').set(rag_accuracy)

    judge_gauge = Gauge('eval_judge_score', 'LLM-as-Judge score (0-10)', ['mode'], registry=registry)
    judge_gauge.labels(mode='llm').set(llm_judge)
    judge_gauge.labels(mode='rag').set(rag_judge)

    token_gauge = Gauge('eval_token_count', 'Average token count per call', ['mode'], registry=registry)
    token_gauge.labels(mode='llm').set(llm_tokens)
    token_gauge.labels(mode='rag').set(rag_tokens)

    Gauge('eval_coverage_backend', 'Backend test coverage (0-1)', registry=registry).set(coverage_backend)
    Gauge('eval_coverage_rag_server', 'RAG server test coverage (0-1)', registry=registry).set(coverage_rag)
    Gauge('eval_coverage_llm_server', 'LLM server test coverage (0-1)', registry=registry).set(coverage_llm)

    push_to_gateway(PUSHGATEWAY_URL, job='eval_runner', registry=registry)
    print(f"Metrics pushed to {PUSHGATEWAY_URL}")


def main():
    cases = load_cases()
    judge_prompt = load_judge_prompt()

    coverage_backend = float(os.getenv("COVERAGE_BACKEND", "0"))
    coverage_rag = float(os.getenv("COVERAGE_RAG_SERVER", "0"))
    coverage_llm = float(os.getenv("COVERAGE_LLM_SERVER", "0"))

    llm_accuracies, rag_accuracies = [], []
    llm_judges, rag_judges = [], []
    llm_tokens, rag_tokens = [], []

    for case in cases:
        print(f"Evaluating {case['id']}...")
        text = case["input_doc"]
        keywords = case["expected_keywords"]

        llm_result = call_service(LLM_SERVER_URL, text, [])
        rag_result = call_service(RAG_SERVER_URL, text, [])

        llm_acc = keyword_accuracy(llm_result["output"], keywords)
        rag_acc = keyword_accuracy(rag_result["output"], keywords)
        llm_accuracies.append(llm_acc)
        rag_accuracies.append(rag_acc)

        llm_j = judge_score(text, llm_result["output"], judge_prompt)
        rag_j = judge_score(text, rag_result["output"], judge_prompt)
        llm_judges.append(llm_j)
        rag_judges.append(rag_j)

        llm_tokens.append(llm_result["token_count"])
        rag_tokens.append(rag_result["token_count"])

        print(f"  LLM accuracy={llm_acc:.2f} judge={llm_j:.1f} tokens={llm_result['token_count']}")
        print(f"  RAG accuracy={rag_acc:.2f} judge={rag_j:.1f} tokens={rag_result['token_count']}")

    avg = lambda lst: round(sum(lst) / len(lst), 4) if lst else 0.0

    push_metrics(
        llm_accuracy=avg(llm_accuracies),
        rag_accuracy=avg(rag_accuracies),
        llm_judge=avg(llm_judges),
        rag_judge=avg(rag_judges),
        llm_tokens=avg(llm_tokens),
        rag_tokens=avg(rag_tokens),
        coverage_backend=coverage_backend,
        coverage_rag=coverage_rag,
        coverage_llm=coverage_llm,
    )

    print("\n=== 요약 ===")
    print(f"정확도:    LLM {avg(llm_accuracies):.2%} vs RAG {avg(rag_accuracies):.2%}")
    print(f"Judge 점수: LLM {avg(llm_judges):.1f} vs RAG {avg(rag_judges):.1f}")
    print(f"토큰 수:   LLM {avg(llm_tokens):.0f} vs RAG {avg(rag_tokens):.0f}")


if __name__ == "__main__":
    main()
