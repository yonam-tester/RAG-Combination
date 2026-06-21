# LLM vs RAG 생성 품질 유사도 측정 설계

**Goal:** 토이 프로젝트 요구사항을 기반으로 LLM/RAG가 생성한 테스트케이스와 OpenAI가 사전 생성한 참조 테스트케이스 간의 임베딩 기반 코사인 유사도를 측정하고 Grafana 대시보드에 표시한다.

**Architecture:**
1. 일회성 `scripts/generate_reference.py`가 OpenAI로 각 요구사항의 이상적 테스트케이스를 생성해 `cases.jsonl`의 `reference_test_case` 필드에 저장한다.
2. `eval_runner.py`가 LLM/RAG 생성 결과와 참조를 각각 `text-embedding-3-small`로 벡터화하고 코사인 유사도를 계산해 Pushgateway에 push한다.
3. Grafana의 "테스트 커버리지" 섹션을 "생성 품질 유사도" 섹션으로 교체한다.

**Tech Stack:** Python httpx, OpenAI Embeddings API (text-embedding-3-small), Prometheus Pushgateway, Grafana JSON provisioning

## Global Constraints

- 추가 Python 패키지 설치 금지 — httpx(기존), math(stdlib)만 사용하여 코사인 유사도 계산
- OpenAI API 키는 기존 `LLM_API_KEY` 환경변수 재사용
- `reference_test_case`는 `cases.jsonl`에 영구 저장 — eval 실행마다 재생성하지 않음
- 기존 메트릭(`eval_accuracy`, `eval_judge_score`, `eval_token_count`) 유지
- Grafana 대시보드 패널 ID 충돌 방지 — 교체 패널은 기존 ID(6,7,8) 재사용
- GitHub Actions `eval.yml`에서 JaCoCo/pytest-cov 단계 및 COVERAGE_* 환경변수 제거

---

## Task 1: cases.jsonl 토이 프로젝트 요구사항으로 재작성

**Files:**
- Modify: `evaluation/ground_truth/cases.jsonl`

**Interfaces:**
- Produces: Task 2의 `generate_reference.py`가 읽는 입력 파일

요구사항 10개를 아래 패턴으로 구성한다. `reference_test_case`는 빈 문자열로 초기화 (Task 2에서 채움).

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

---

## Task 2: scripts/generate_reference.py — 참조 테스트케이스 일회성 생성

**Files:**
- Create: `scripts/generate_reference.py`

**Interfaces:**
- Consumes: `evaluation/ground_truth/cases.jsonl` (`reference_test_case`가 빈 케이스)
- Produces: `reference_test_case` 채워진 `cases.jsonl` (in-place 업데이트)

스크립트는 `reference_test_case`가 비어 있는 케이스만 처리한다 (재실행 안전).

```python
#!/usr/bin/env python3
"""일회성 실행: 각 요구사항에 대한 참조 테스트케이스를 OpenAI로 생성하여 cases.jsonl에 저장."""
import json, os, httpx

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

실행:
```bash
LLM_API_KEY=sk-... python3 scripts/generate_reference.py
```

---

## Task 3: eval_runner.py — embedding_similarity() 추가 및 메트릭 교체

**Files:**
- Modify: `evaluation/eval_runner.py`

**Interfaces:**
- Consumes: Task 1의 `cases.jsonl` (`reference_test_case` 필드)
- Produces: Pushgateway에 `eval_similarity{mode="llm"}`, `eval_similarity{mode="rag"}`

### 3-1: embedding_similarity() 함수

```python
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
```

### 3-2: push_metrics() 변경

`eval_coverage_*` Gauge 3개 제거. `eval_similarity` Gauge 추가:

```python
similarity_gauge = Gauge('eval_similarity', '참조 테스트케이스와의 임베딩 코사인 유사도', ['mode'], registry=registry)
similarity_gauge.labels(mode='llm').set(llm_similarity)
similarity_gauge.labels(mode='rag').set(rag_similarity)
```

함수 시그니처 변경:
```python
# 전
def push_metrics(llm_accuracy, rag_accuracy, llm_judge, rag_judge,
                 llm_tokens, rag_tokens, coverage_backend, coverage_rag, coverage_llm)

# 후
def push_metrics(llm_accuracy, rag_accuracy, llm_judge, rag_judge,
                 llm_tokens, rag_tokens, llm_similarity, rag_similarity)
```

### 3-3: main() 루프 변경

```python
for case in cases:
    reference = case.get("reference_test_case", "")
    ...
    llm_sim = embedding_similarity(llm_result["output"], reference)
    rag_sim = embedding_similarity(rag_result["output"], reference)
    llm_similarities.append(llm_sim)
    rag_similarities.append(rag_sim)
    print(f"  LLM sim={llm_sim:.3f}  RAG sim={rag_sim:.3f}")
```

`COVERAGE_*` 환경변수 읽는 코드 제거.

---

## Task 4: Grafana 대시보드 — 유사도 섹션으로 교체

**Files:**
- Modify: `grafana/dashboards/yeonam-overview.json`

패널 ID 6, 7, 8 (기존 커버리지 게이지 3개)을 아래로 교체한다.

**패널 6: LLM 유사도 (bargauge)**
```json
{
  "id": 6,
  "type": "bargauge",
  "title": "LLM 생성 유사도 (참조 대비)",
  "targets": [{"expr": "eval_similarity{job=\"eval_runner\",mode=\"llm\"}", "legendFormat": "llm"}],
  "fieldConfig": {"defaults": {"unit": "percentunit", "min": 0, "max": 1,
    "thresholds": {"steps": [{"color":"red","value":0},{"color":"yellow","value":0.5},{"color":"green","value":0.75}]}}}
}
```

**패널 7: RAG 유사도 (bargauge)**
```json
{
  "id": 7,
  "type": "bargauge",
  "title": "RAG 생성 유사도 (참조 대비)",
  "targets": [{"expr": "eval_similarity{job=\"eval_runner\",mode=\"rag\"}", "legendFormat": "rag"}],
  "fieldConfig": {"defaults": {"unit": "percentunit", "min": 0, "max": 1,
    "thresholds": {"steps": [{"color":"red","value":0},{"color":"yellow","value":0.5},{"color":"green","value":0.75}]}}}
}
```

**패널 8: LLM vs RAG 유사도 비교 (bargauge)**
```json
{
  "id": 8,
  "type": "bargauge",
  "title": "LLM vs RAG 유사도 비교",
  "targets": [
    {"expr": "eval_similarity{job=\"eval_runner\",mode=\"llm\"}", "legendFormat": "LLM"},
    {"expr": "eval_similarity{job=\"eval_runner\",mode=\"rag\"}", "legendFormat": "RAG"}
  ],
  "fieldConfig": {"defaults": {"unit": "percentunit", "min": 0, "max": 1,
    "thresholds": {"steps": [{"color":"red","value":0},{"color":"yellow","value":0.5},{"color":"green","value":0.75}]}}}
}
```

Row 패널 5의 title을 `"테스트 커버리지"` → `"생성 품질 유사도 (참조 테스트케이스 대비)"` 로 변경.

---

## Task 5: eval.yml — coverage 단계 제거

**Files:**
- Modify: `.github/workflows/eval.yml`

아래 단계 전부 제거:
- `Run backend tests with JaCoCo`
- `Parse backend coverage`
- `Run RAG server tests with pytest-cov`
- `Parse RAG server coverage`
- `Run LLM server tests with pytest-cov`
- `Parse LLM server coverage`

`Copy eval script to EC2 and run evaluation` 단계에서 `COVERAGE_*` 환경변수 3개 제거.

rag_server/test_basic.py, llm_server/test_basic.py, conftest.py 파일은 **삭제하지 않음** (코드 커버리지 CI는 제거하지만 smoke test 자체는 유지).

---

## 실행 순서

```bash
# 1. reference 생성 (로컬 1회)
LLM_API_KEY=sk-... python3 scripts/generate_reference.py

# 2. EC2 배포 후 eval 실행
./scripts/demo.sh --run

# 3. Grafana 확인
open http://3.35.203.154:3000/d/yeonam-overview
```
