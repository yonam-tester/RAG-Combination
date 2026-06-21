# LLM vs RAG 생성 품질 유사도 측정 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 토이 프로젝트 요구사항 10개에 대해 OpenAI로 사전 생성한 참조 테스트케이스와 LLM/RAG 생성 결과 간 임베딩 코사인 유사도를 측정하고, Grafana "테스트 커버리지" 섹션을 "생성 품질 유사도" 섹션으로 교체한다.

**Architecture:** `scripts/generate_reference.py`가 OpenAI로 참조 테스트케이스를 생성해 `cases.jsonl`에 영구 저장한다 (일회성). `eval_runner.py`는 매 실행마다 LLM/RAG 생성 결과를 `text-embedding-3-small`로 벡터화하고 참조와 코사인 유사도를 계산해 Pushgateway에 push한다. Grafana 패널 6·7·8을 bargauge 유사도 패널로 교체하고, eval.yml에서 JaCoCo·pytest-cov 단계를 제거한다.

**Tech Stack:** Python 3.10, httpx, OpenAI Embeddings API (text-embedding-3-small), Prometheus Pushgateway, Grafana JSON provisioning, GitHub Actions

## Global Constraints

- 추가 Python 패키지 설치 금지 — httpx(기존)와 math(stdlib)만으로 코사인 유사도 계산
- OpenAI API 키는 기존 `LLM_API_KEY` 환경변수 재사용
- `reference_test_case`는 `cases.jsonl`에 영구 저장 — eval 실행마다 재생성하지 않음
- 기존 메트릭 `eval_accuracy`, `eval_judge_score`, `eval_token_count` 유지
- Grafana 대시보드 패널 ID 6·7·8 재사용 (충돌 방지)
- eval.yml에서 JaCoCo·pytest-cov 단계 및 COVERAGE_* 환경변수 제거
- rag_server/test_basic.py, llm_server/test_basic.py, conftest.py 파일은 삭제하지 않음

## 파일 구조

```
evaluation/
  ground_truth/
    cases.jsonl          ← [수정] reference_test_case 필드 추가, 내용 교체
  eval_runner.py         ← [수정] embedding 함수 추가, push_metrics 변경, main 루프 변경
scripts/
  generate_reference.py  ← [신규] 일회성 참조 테스트케이스 생성 스크립트
grafana/dashboards/
  yeonam-overview.json   ← [수정] 패널 5·6·7·8 교체
.github/workflows/
  eval.yml               ← [수정] coverage 단계 제거
```

---

### Task 1: cases.jsonl — 토이 프로젝트 요구사항으로 교체

**Files:**
- Modify: `evaluation/ground_truth/cases.jsonl`

**Interfaces:**
- Produces: `reference_test_case` 빈 문자열 포함 케이스 10개 — Task 2의 generate_reference.py가 읽음

- [ ] **Step 1: cases.jsonl 전체 교체**

`evaluation/ground_truth/cases.jsonl`을 아래 내용으로 **전체 교체**한다 (기존 내용 삭제):

```jsonl
{"id":"TC-001","input_doc":"사용자는 이메일과 비밀번호로 로그인할 수 있어야 한다. 잘못된 비밀번호 입력 시 오류 메시지를 표시한다.","reference_test_case":"","expected_keywords":["로그인","이메일","비밀번호","오류"]}
{"id":"TC-002","input_doc":"사용자는 회원가입 시 이메일 중복 확인을 거쳐야 한다. 이미 존재하는 이메일이면 가입이 거부된다.","reference_test_case":"","expected_keywords":["회원가입","이메일","중복","거부"]}
{"id":"TC-003","input_doc":"TODO 항목을 추가, 수정, 삭제할 수 있어야 한다. 삭제한 항목은 목록에서 즉시 제거된다.","reference_test_case":"","expected_keywords":["TODO","추가","수정","삭제"]}
{"id":"TC-004","input_doc":"상품 목록을 가격 오름차순 또는 내림차순으로 정렬할 수 있어야 한다.","reference_test_case":"","expected_keywords":["정렬","가격","상품","오름차순","내림차순"]}
{"id":"TC-005","input_doc":"결제 완료 후 사용자에게 이메일 영수증이 자동 발송되어야 한다.","reference_test_case":"","expected_keywords":["결제","이메일","영수증","발송"]}
{"id":"TC-006","input_doc":"관리자는 사용자 계정을 비활성화하거나 삭제할 수 있어야 한다. 삭제된 계정은 복구할 수 없다.","reference_test_case":"","expected_keywords":["관리자","계정","비활성화","삭제"]}
{"id":"TC-007","input_doc":"파일 업로드 기능은 PDF, DOCX 형식만 허용하며 최대 10MB까지 업로드 가능하다.","reference_test_case":"","expected_keywords":["파일","업로드","PDF","DOCX","용량"]}
{"id":"TC-008","input_doc":"사용자는 상품을 장바구니에 담고 수량을 변경할 수 있어야 한다. 수량이 0이 되면 항목이 제거된다.","reference_test_case":"","expected_keywords":["장바구니","상품","수량","제거"]}
{"id":"TC-009","input_doc":"게시물 검색 기능은 제목과 내용을 포함하여 검색하며, 결과가 없으면 '검색 결과 없음' 메시지를 표시한다.","reference_test_case":"","expected_keywords":["검색","제목","내용","결과 없음"]}
{"id":"TC-010","input_doc":"비밀번호 변경 시 현재 비밀번호를 먼저 확인하고, 새 비밀번호는 8자 이상이어야 한다.","reference_test_case":"","expected_keywords":["비밀번호","변경","확인","8자"]}
```

- [ ] **Step 2: 파일 유효성 확인**

```bash
python3 -c "
import json
cases = [json.loads(l) for l in open('evaluation/ground_truth/cases.jsonl') if l.strip()]
assert len(cases) == 10
assert all('reference_test_case' in c for c in cases)
assert all(c['reference_test_case'] == '' for c in cases)
print('OK: 10 cases, all reference_test_case empty')
"
```

Expected: `OK: 10 cases, all reference_test_case empty`

- [ ] **Step 3: 커밋**

```bash
git add evaluation/ground_truth/cases.jsonl
git commit -m "feat: cases.jsonl 토이 프로젝트 요구사항 10개로 교체 (reference_test_case 필드 추가)"
```

---

### Task 2: scripts/generate_reference.py — 참조 테스트케이스 일회성 생성

**Files:**
- Create: `scripts/generate_reference.py`
- Modify: `evaluation/ground_truth/cases.jsonl` (실행 후 reference_test_case 채워짐)

**Interfaces:**
- Consumes: Task 1의 `cases.jsonl` (reference_test_case 빈 문자열)
- Produces: reference_test_case가 채워진 `cases.jsonl` — Task 3의 eval_runner.py가 읽음

- [ ] **Step 1: generate_reference.py 작성**

`scripts/generate_reference.py`를 아래 내용으로 생성:

```python
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
```

- [ ] **Step 2: 문법 확인**

```bash
python3 -c "import ast; ast.parse(open('scripts/generate_reference.py').read()); print('syntax OK')"
```

Expected: `syntax OK`

- [ ] **Step 3: 스크립트 실행 (LLM_API_KEY 필요)**

```bash
LLM_API_KEY=$(grep LLM_API_KEY /home/ubuntu/app/.env 2>/dev/null | cut -d= -f2 || echo $LLM_API_KEY) \
  python3 scripts/generate_reference.py
```

로컬에서 실행할 경우:
```bash
LLM_API_KEY=sk-... python3 scripts/generate_reference.py
```

Expected 출력:
```
Generating reference for TC-001...
  -> 이메일과 비밀번호로 로그인 시 정상 인증 확인. 잘못된 비밀번호 입력 시 오류...
Generating reference for TC-002...
...
cases.jsonl 업데이트 완료.
```

- [ ] **Step 4: reference_test_case 채워짐 확인**

```bash
python3 -c "
import json
cases = [json.loads(l) for l in open('evaluation/ground_truth/cases.jsonl') if l.strip()]
empty = [c['id'] for c in cases if not c.get('reference_test_case','').strip()]
assert not empty, f'비어 있는 케이스: {empty}'
print(f'OK: 10개 케이스 reference_test_case 모두 채워짐')
print(f'TC-001 sample: {cases[0][\"reference_test_case\"][:60]}...')
"
```

Expected: `OK: 10개 케이스 reference_test_case 모두 채워짐`

- [ ] **Step 5: 커밋**

```bash
git add scripts/generate_reference.py evaluation/ground_truth/cases.jsonl
git commit -m "feat: 참조 테스트케이스 생성 스크립트 추가 및 cases.jsonl reference 채움"
```

---

### Task 3: eval_runner.py — embedding 유사도 측정 추가

**Files:**
- Modify: `evaluation/eval_runner.py`

**Interfaces:**
- Consumes: Task 2의 `cases.jsonl` (reference_test_case 필드)
- Produces: Pushgateway에 `eval_similarity{mode="llm"}`, `eval_similarity{mode="rag"}`

현재 `eval_runner.py`의 전체 내용을 아래로 교체한다. 변경 요약:
1. `get_embedding()`, `cosine_similarity()`, `embedding_similarity()` 함수 추가
2. `push_metrics()`: `eval_coverage_*` 3개 제거 → `eval_similarity` 추가
3. `main()`: `COVERAGE_*` 환경변수 읽기 제거, 유사도 측정 루프 추가

- [ ] **Step 1: cosine_similarity 단위 테스트 작성**

`evaluation/test_eval_utils.py` 파일을 생성:

```python
"""eval_runner.py 순수 함수 단위 테스트 (API 호출 없음)."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))


def cosine_similarity(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(x * x for x in b) ** 0.5
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return round(dot / (norm_a * norm_b), 4)


def test_identical_vectors_give_one():
    v = [1.0, 0.5, 0.3]
    assert cosine_similarity(v, v) == 1.0


def test_orthogonal_vectors_give_zero():
    assert cosine_similarity([1, 0], [0, 1]) == 0.0


def test_zero_vector_returns_zero():
    assert cosine_similarity([0, 0], [1, 2]) == 0.0


def test_keyword_accuracy_empty_keywords():
    from eval_runner import keyword_accuracy
    assert keyword_accuracy("anything", []) == 0.0


def test_keyword_accuracy_match():
    from eval_runner import keyword_accuracy
    assert keyword_accuracy("로그인 이메일 테스트", ["로그인", "이메일"]) == 1.0


def test_keyword_accuracy_partial():
    from eval_runner import keyword_accuracy
    assert keyword_accuracy("로그인만 있음", ["로그인", "이메일"]) == 0.5
```

- [ ] **Step 2: 테스트 실행 — 실패 확인 (keyword_accuracy는 아직 import 가능, cosine_similarity는 eval_runner에 없음)**

```bash
cd evaluation && python3 -m pytest test_eval_utils.py -v 2>&1 | head -20
```

Expected: `test_keyword_accuracy_*` 3개는 PASS, `test_identical_vectors_give_one` 등은 eval_runner에 cosine_similarity 없으므로 테스트 파일 내 정의로 PASS. (이 테스트는 eval_runner import 없이 로컬 정의로 동작)

- [ ] **Step 3: eval_runner.py 전체 교체**

`evaluation/eval_runner.py`를 아래 내용으로 전체 교체:

```python
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
            timeout=120.0,
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


def get_embedding(text: str) -> list[float]:
    """OpenAI text-embedding-3-small으로 텍스트 벡터화."""
    resp = httpx.post(
        "https://api.openai.com/v1/embeddings",
        headers={"Authorization": f"Bearer {LLM_API_KEY}"},
        json={"model": "text-embedding-3-small", "input": text[:8000]},
        timeout=30.0,
    )
    resp.raise_for_status()
    return resp.json()["data"][0]["embedding"]


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """numpy 없이 코사인 유사도 계산."""
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(x * x for x in b) ** 0.5
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return round(dot / (norm_a * norm_b), 4)


def embedding_similarity(generated: str, reference: str) -> float:
    """생성 결과와 참조 테스트케이스 간 코사인 유사도 (0~1)."""
    if not generated.strip() or not reference.strip():
        return 0.0
    try:
        vec_gen = get_embedding(generated)
        vec_ref = get_embedding(reference)
        return cosine_similarity(vec_gen, vec_ref)
    except Exception as e:
        print(f"Embedding similarity failed: {e}")
        return 0.0


def push_metrics(
    llm_accuracy: float,
    rag_accuracy: float,
    llm_judge: float,
    rag_judge: float,
    llm_tokens: float,
    rag_tokens: float,
    llm_similarity: float,
    rag_similarity: float,
) -> None:
    registry = CollectorRegistry()

    accuracy_gauge = Gauge("eval_accuracy", "Keyword matching accuracy", ["mode"], registry=registry)
    accuracy_gauge.labels(mode="llm").set(llm_accuracy)
    accuracy_gauge.labels(mode="rag").set(rag_accuracy)

    judge_gauge = Gauge("eval_judge_score", "LLM-as-Judge score (0-10)", ["mode"], registry=registry)
    judge_gauge.labels(mode="llm").set(llm_judge)
    judge_gauge.labels(mode="rag").set(rag_judge)

    token_gauge = Gauge("eval_token_count", "Average token count per call", ["mode"], registry=registry)
    token_gauge.labels(mode="llm").set(llm_tokens)
    token_gauge.labels(mode="rag").set(rag_tokens)

    similarity_gauge = Gauge(
        "eval_similarity", "참조 테스트케이스와의 임베딩 코사인 유사도", ["mode"], registry=registry
    )
    similarity_gauge.labels(mode="llm").set(llm_similarity)
    similarity_gauge.labels(mode="rag").set(rag_similarity)

    push_to_gateway(PUSHGATEWAY_URL, job="eval_runner", registry=registry)
    print(f"Metrics pushed to {PUSHGATEWAY_URL}")


def main():
    cases = load_cases()
    judge_prompt = load_judge_prompt()

    llm_accuracies, rag_accuracies = [], []
    llm_judges, rag_judges = [], []
    llm_tokens, rag_tokens = [], []
    llm_similarities, rag_similarities = [], []

    for case in cases:
        print(f"Evaluating {case['id']}...")
        text = case["input_doc"]
        keywords = case["expected_keywords"]
        reference = case.get("reference_test_case", "")

        time.sleep(5)  # 메모리 제한 EC2에서 서버 GC 대기
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

        llm_sim = embedding_similarity(llm_result["output"], reference)
        rag_sim = embedding_similarity(rag_result["output"], reference)
        llm_similarities.append(llm_sim)
        rag_similarities.append(rag_sim)

        print(f"  LLM accuracy={llm_acc:.2f} judge={llm_j:.1f} sim={llm_sim:.3f} tokens={llm_result['token_count']}")
        print(f"  RAG accuracy={rag_acc:.2f} judge={rag_j:.1f} sim={rag_sim:.3f} tokens={rag_result['token_count']}")

    avg = lambda lst: round(sum(lst) / len(lst), 4) if lst else 0.0

    push_metrics(
        llm_accuracy=avg(llm_accuracies),
        rag_accuracy=avg(rag_accuracies),
        llm_judge=avg(llm_judges),
        rag_judge=avg(rag_judges),
        llm_tokens=avg(llm_tokens),
        rag_tokens=avg(rag_tokens),
        llm_similarity=avg(llm_similarities),
        rag_similarity=avg(rag_similarities),
    )

    print("\n=== 요약 ===")
    print(f"정확도:    LLM {avg(llm_accuracies):.2%} vs RAG {avg(rag_accuracies):.2%}")
    print(f"Judge 점수: LLM {avg(llm_judges):.1f} vs RAG {avg(rag_judges):.1f}")
    print(f"유사도:    LLM {avg(llm_similarities):.3f} vs RAG {avg(rag_similarities):.3f}")
    print(f"토큰 수:   LLM {avg(llm_tokens):.0f} vs RAG {avg(rag_tokens):.0f}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 문법 확인**

```bash
python3 -c "import ast; ast.parse(open('evaluation/eval_runner.py').read()); print('syntax OK')"
```

Expected: `syntax OK`

- [ ] **Step 5: 단위 테스트 통과 확인**

```bash
cd evaluation && python3 -m pytest test_eval_utils.py -v
```

Expected:
```
test_eval_utils.py::test_identical_vectors_give_one PASSED
test_eval_utils.py::test_orthogonal_vectors_give_zero PASSED
test_eval_utils.py::test_zero_vector_returns_zero PASSED
test_eval_utils.py::test_keyword_accuracy_empty_keywords PASSED
test_eval_utils.py::test_keyword_accuracy_match PASSED
test_eval_utils.py::test_keyword_accuracy_partial PASSED
6 passed
```

- [ ] **Step 6: 커밋**

```bash
git add evaluation/eval_runner.py evaluation/test_eval_utils.py
git commit -m "feat: eval_runner embedding 유사도 측정 추가, coverage 메트릭 제거"
```

---

### Task 4: Grafana 대시보드 — 유사도 패널로 교체

**Files:**
- Modify: `grafana/dashboards/yeonam-overview.json`

**Interfaces:**
- Consumes: Task 3의 `eval_similarity{mode="llm"|"rag"}` 메트릭
- Produces: "생성 품질 유사도" 섹션이 담긴 대시보드 JSON

현재 패널 5(row "테스트 커버리지"), 6(Backend 커버리지), 7(RAG Server 커버리지), 8(LLM Server 커버리지) 4개를 교체한다.

- [ ] **Step 1: 패널 5 row title 변경**

`grafana/dashboards/yeonam-overview.json` 에서 아래 블록을 찾아:

```json
    {
      "id": 5,
      "type": "row",
      "title": "테스트 커버리지",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 9 },
      "collapsed": false
    },
```

아래로 교체:

```json
    {
      "id": 5,
      "type": "row",
      "title": "생성 품질 유사도 (참조 테스트케이스 대비)",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 9 },
      "collapsed": false
    },
```

- [ ] **Step 2: 패널 6·7·8 교체**

아래 세 패널 블록을 찾아 (id 6, 7, 8 전체):

```json
    {
      "id": 6,
      "type": "gauge",
      "title": "Backend 커버리지",
      ...
    },
    {
      "id": 7,
      "type": "gauge",
      "title": "RAG Server 커버리지",
      ...
    },
    {
      "id": 8,
      "type": "gauge",
      "title": "LLM Server 커버리지",
      ...
    },
```

아래 세 패널로 교체:

```json
    {
      "id": 6,
      "type": "bargauge",
      "title": "LLM 생성 유사도 (참조 대비)",
      "description": "LLM 생성 테스트케이스와 OpenAI 참조 테스트케이스 간 임베딩 코사인 유사도",
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 10 },
      "options": {
        "orientation": "horizontal",
        "reduceOptions": { "calcs": ["lastNotNull"] }
      },
      "targets": [
        {
          "expr": "eval_similarity{job=\"eval_runner\",mode=\"llm\"}",
          "legendFormat": "LLM"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "min": 0,
          "max": 1,
          "thresholds": {
            "steps": [
              { "color": "red", "value": 0 },
              { "color": "yellow", "value": 0.5 },
              { "color": "green", "value": 0.75 }
            ]
          }
        }
      }
    },
    {
      "id": 7,
      "type": "bargauge",
      "title": "RAG 생성 유사도 (참조 대비)",
      "description": "RAG 생성 테스트케이스와 OpenAI 참조 테스트케이스 간 임베딩 코사인 유사도",
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 10 },
      "options": {
        "orientation": "horizontal",
        "reduceOptions": { "calcs": ["lastNotNull"] }
      },
      "targets": [
        {
          "expr": "eval_similarity{job=\"eval_runner\",mode=\"rag\"}",
          "legendFormat": "RAG"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "min": 0,
          "max": 1,
          "thresholds": {
            "steps": [
              { "color": "red", "value": 0 },
              { "color": "yellow", "value": 0.5 },
              { "color": "green", "value": 0.75 }
            ]
          }
        }
      }
    },
    {
      "id": 8,
      "type": "bargauge",
      "title": "LLM vs RAG 유사도 비교",
      "description": "두 방식의 참조 대비 유사도를 나란히 비교",
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 10 },
      "options": {
        "orientation": "horizontal",
        "reduceOptions": { "calcs": ["lastNotNull"] }
      },
      "targets": [
        {
          "expr": "eval_similarity{job=\"eval_runner\",mode=\"llm\"}",
          "legendFormat": "LLM"
        },
        {
          "expr": "eval_similarity{job=\"eval_runner\",mode=\"rag\"}",
          "legendFormat": "RAG"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "min": 0,
          "max": 1,
          "thresholds": {
            "steps": [
              { "color": "red", "value": 0 },
              { "color": "yellow", "value": 0.5 },
              { "color": "green", "value": 0.75 }
            ]
          }
        }
      }
    },
```

- [ ] **Step 3: JSON 유효성 확인**

```bash
python3 -c "import json; json.load(open('grafana/dashboards/yeonam-overview.json')); print('JSON valid')"
```

Expected: `JSON valid`

- [ ] **Step 4: 커밋**

```bash
git add grafana/dashboards/yeonam-overview.json
git commit -m "feat: Grafana 테스트 커버리지 섹션을 생성 품질 유사도 섹션으로 교체"
```

---

### Task 5: eval.yml — coverage 단계 제거

**Files:**
- Modify: `.github/workflows/eval.yml`

**Interfaces:**
- Produces: 불필요한 JaCoCo·pytest-cov 단계 없는 간소화된 워크플로우

- [ ] **Step 1: eval.yml 전체 교체**

`.github/workflows/eval.yml`을 아래 내용으로 전체 교체 (JaCoCo, pytest-cov, COVERAGE_* 모두 제거):

```yaml
name: Evaluate LLM vs RAG

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  test-and-evaluate:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Run evaluation on EC2
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          envs: GH_TOKEN
          script: |
            set -e
            cd /home/ubuntu/app
            git pull https://x-access-token:$GH_TOKEN@github.com/yonam-tester/RAG-Combination.git main
            python3 -m ensurepip --upgrade 2>/dev/null || sudo apt-get install -y python3-pip -q
            python3 -m pip install -q --break-system-packages -r evaluation/requirements.txt
            LLM_API_KEY="${{ secrets.LLM_API_KEY }}" \
            LLM_SERVER_URL=http://localhost:8001 \
            RAG_SERVER_URL=http://localhost:8000 \
            PUSHGATEWAY_URL=http://localhost:9091 \
            python3 evaluation/eval_runner.py
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

- [ ] **Step 2: YAML 유효성 확인**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/eval.yml')); print('YAML valid')" 2>/dev/null || \
  python3 -c "
data = open('.github/workflows/eval.yml').read()
assert 'JaCoCo' not in data, 'JaCoCo 단계 남아 있음'
assert 'pytest-cov' not in data, 'pytest-cov 단계 남아 있음'
assert 'COVERAGE_BACKEND' not in data, 'COVERAGE_BACKEND 남아 있음'
assert 'COVERAGE_RAG_SERVER' not in data, 'COVERAGE_RAG_SERVER 남아 있음'
assert 'COVERAGE_LLM_SERVER' not in data, 'COVERAGE_LLM_SERVER 남아 있음'
print('OK: 모든 coverage 단계 제거됨')
"
```

Expected: `OK: 모든 coverage 단계 제거됨`

- [ ] **Step 3: 커밋 및 push**

```bash
git add .github/workflows/eval.yml
git commit -m "chore: eval.yml에서 JaCoCo·pytest-cov coverage 단계 제거"
git push origin main
```

---

### Task 6: EC2 배포 및 검증

**Files:** 없음 (로컬 스크립트 실행)

**Interfaces:**
- Consumes: Task 1-5 완료된 코드 (main 브랜치 push됨)
- Produces: Grafana 대시보드에 `eval_similarity` 값 반영

- [ ] **Step 1: EC2에서 최신 코드 pull 및 Grafana 재시작**

```bash
ssh -i ~/Downloads/yonam-test.pem -o StrictHostKeyChecking=no ubuntu@3.35.203.154 \
  "cd /home/ubuntu/app && \
   git pull https://x-access-token:\$(gh auth token)@github.com/yonam-tester/RAG-Combination.git main --quiet && \
   docker compose restart grafana && \
   sleep 5 && curl -sf http://localhost:3000/api/health | grep database"
```

Expected: `"database": "ok"`

- [ ] **Step 2: eval_runner 실행**

```bash
ssh -i ~/Downloads/yonam-test.pem -o StrictHostKeyChecking=no ubuntu@3.35.203.154 \
  "cd /home/ubuntu/app && \
   LLM_API_KEY=\$(grep LLM_API_KEY .env | cut -d= -f2) \
   LLM_SERVER_URL=http://localhost:8001 \
   RAG_SERVER_URL=http://localhost:8000 \
   PUSHGATEWAY_URL=http://localhost:9091 \
   python3 evaluation/eval_runner.py 2>&1"
```

Expected 출력 (마지막 줄):
```
유사도:    LLM 0.xxx vs RAG 0.xxx
```

- [ ] **Step 3: Pushgateway에서 메트릭 확인**

```bash
ssh -i ~/Downloads/yonam-test.pem -o StrictHostKeyChecking=no ubuntu@3.35.203.154 \
  "curl -sf http://localhost:9091/metrics | grep '^eval_similarity'"
```

Expected:
```
eval_similarity{instance="",job="eval_runner",mode="llm"} 0.7XXX
eval_similarity{instance="",job="eval_runner",mode="rag"} 0.7XXX
```

- [ ] **Step 4: Grafana 대시보드 확인**

브라우저에서 `http://3.35.203.154:3000/d/yeonam-overview` 접속.

확인 항목:
- "생성 품질 유사도 (참조 테스트케이스 대비)" row 존재
- 패널 6: "LLM 생성 유사도 (참조 대비)" — 0 이상 값 표시
- 패널 7: "RAG 생성 유사도 (참조 대비)" — 0 이상 값 표시
- 패널 8: "LLM vs RAG 유사도 비교" — 두 bar 나란히 표시
- 기존 "Backend 커버리지", "RAG Server 커버리지", "LLM Server 커버리지" 패널 없음
