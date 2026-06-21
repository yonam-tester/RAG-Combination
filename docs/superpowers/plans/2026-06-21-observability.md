# Observability & LLM vs RAG 비교 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EC2에서 Prometheus + Grafana를 운영하며, LLM 단독 vs RAG+LLM 정확도/토큰/커버리지를 수치로 비교 시각화하고, GitHub Actions + EC2 cron으로 자동 평가를 실행한다.

**Architecture:** docker-compose에 prometheus / grafana / cadvisor / pushgateway / llm-server 5개 서비스를 추가한다. FastAPI 서비스에는 `prometheus_client`로 `/metrics`와 `/api/eval/generate` 엔드포인트를 추가하고, Spring Boot에는 Actuator + Micrometer를 추가한다. eval_runner.py가 두 서비스를 동시에 호출해 정확도·토큰을 비교하고 Pushgateway로 push한다.

**Tech Stack:** Python 3.10, prometheus_client 0.20, FastAPI, Spring Boot 3.3 + Actuator + micrometer-registry-prometheus, Prometheus 2.x, Grafana 10.x, cAdvisor, Pushgateway, GitHub Actions, pytest-cov, JaCoCo

## Global Constraints

- Python: 3.10 이상
- Spring Boot: 3.3.0 (pom.xml 기준)
- docker-compose 서비스 이름: `llm-server`, `prometheus`, `grafana`, `cadvisor`, `pushgateway`
- 모든 컨테이너 레이블: `app: yeonam`, `component: <서비스명>`, `phase: "1"` (k8s 마이그레이션 준비)
- Prometheus 메트릭 이름: 스펙 섹션 4의 목록을 정확히 사용 (`eval_accuracy`, `eval_judge_score`, `eval_token_count`, `eval_coverage_backend`, `eval_coverage_rag_server`, `eval_coverage_llm_server`)
- eval_runner.py는 외부 HTTP 호출만 수행하는 독립 스크립트 (import 의존성 없음)
- EC2 보안 그룹: TCP 3000 (Grafana) 인바운드 추가 필요 (수동 작업)

---

## 파일 맵

### 신규 생성
| 파일 | 역할 |
|------|------|
| `prometheus/prometheus.yml` | scrape config (모든 서비스 대상) |
| `grafana/provisioning/datasources/prometheus.yml` | Grafana Prometheus 데이터소스 자동 등록 |
| `grafana/provisioning/dashboards/dashboards.yml` | Grafana 대시보드 파일 경로 프로비저닝 |
| `grafana/dashboards/yeonam-overview.json` | 메인 대시보드 JSON (3개 Row) |
| `evaluation/ground_truth/cases.jsonl` | 수동 정답셋 10개 |
| `evaluation/llm_judge_prompt.txt` | LLM-as-Judge 채점 프롬프트 |
| `evaluation/eval_runner.py` | LLM vs RAG 비교 평가 스크립트 |
| `evaluation/requirements.txt` | eval_runner 의존성 |
| `.github/workflows/eval.yml` | CI 평가 워크플로우 |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `docker-compose.yml` | llm-server, prometheus, grafana, cadvisor, pushgateway 추가; rag-server / llm-server 포트 노출 |
| `llm_server/requirements.txt` | `prometheus_client>=0.20` 추가 |
| `llm_server/main.py` | `/metrics` 엔드포인트 + `/api/eval/generate` + Counter/Histogram 계측 |
| `rag_server/requirements.txt` | `prometheus_client>=0.20` 추가 |
| `rag_server/main.py` | `/metrics` 엔드포인트 + `/api/eval/generate` + Counter/Histogram 계측 |
| `backend/pom.xml` | `spring-boot-starter-actuator` + `micrometer-registry-prometheus` 추가 |
| `backend/src/main/resources/application.yml` | `management.endpoints` 노출 설정 추가 |

---

## Task 1: 관측성 인프라 추가 (docker-compose + Prometheus + Grafana 프로비저닝)

**Files:**
- Modify: `docker-compose.yml`
- Create: `prometheus/prometheus.yml`
- Create: `grafana/provisioning/datasources/prometheus.yml`
- Create: `grafana/provisioning/dashboards/dashboards.yml`

**Interfaces:**
- Produces: Prometheus가 `http://prometheus:9090`에서 실행되고 `http://grafana:3000`에서 대시보드 접근 가능. Task 5의 대시보드 JSON은 `grafana/dashboards/` 디렉토리에서 자동 로드됨.

- [ ] **Step 1: docker-compose.yml에 5개 서비스 추가**

기존 `docker-compose.yml`의 `services:` 블록 끝(minio 다음)에 추가:

```yaml
  llm-server:
    build:
      context: ./llm_server
      dockerfile: Dockerfile
    container_name: yeonam-llm
    ports:
      - "8001:8000"
    expose:
      - "8000"
    env_file:
      - .env
    networks:
      - yeonam-network
    restart: always
    labels:
      app: yeonam
      component: llm-server
      phase: "1"

  prometheus:
    image: prom/prometheus:v2.51.0
    container_name: yeonam-prometheus
    expose:
      - "9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=30d'
    networks:
      - yeonam-network
    restart: always
    labels:
      app: yeonam
      component: prometheus
      phase: "1"

  grafana:
    image: grafana/grafana:10.4.0
    container_name: yeonam-grafana
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-admin}
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana_data:/var/lib/grafana
    networks:
      - yeonam-network
    restart: always
    labels:
      app: yeonam
      component: grafana
      phase: "1"

  cadvisor:
    image: gcr.io/cadvisor/cadvisor:v0.49.1
    container_name: yeonam-cadvisor
    expose:
      - "8080"
    volumes:
      - /:/rootfs:ro
      - /var/run:/var/run:ro
      - /sys:/sys:ro
      - /var/lib/docker/:/var/lib/docker:ro
    networks:
      - yeonam-network
    restart: always
    labels:
      app: yeonam
      component: cadvisor
      phase: "1"

  pushgateway:
    image: prom/pushgateway:v1.8.0
    container_name: yeonam-pushgateway
    ports:
      - "9091:9091"
    networks:
      - yeonam-network
    restart: always
    labels:
      app: yeonam
      component: pushgateway
      phase: "1"
```

기존 `volumes:` 블록에 추가:
```yaml
  prometheus_data:
  grafana_data:
```

- [ ] **Step 2: prometheus/prometheus.yml 작성**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets: ['localhost:9090']

  - job_name: cadvisor
    static_configs:
      - targets: ['cadvisor:8080']

  - job_name: pushgateway
    honor_labels: true
    static_configs:
      - targets: ['pushgateway:9091']

  - job_name: llm-server
    static_configs:
      - targets: ['llm-server:8000']

  - job_name: rag-server
    static_configs:
      - targets: ['rag-server:8000']

  - job_name: backend
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['backend:8080']
```

- [ ] **Step 3: Grafana datasource 프로비저닝 설정 작성**

`grafana/provisioning/datasources/prometheus.yml`:

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

- [ ] **Step 4: Grafana dashboard 프로비저닝 설정 작성**

`grafana/provisioning/dashboards/dashboards.yml`:

```yaml
apiVersion: 1

providers:
  - name: yeonam
    folder: ''
    type: file
    disableDeletion: false
    editable: true
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 5: 로컬에서 스택 기동 확인**

```bash
docker compose up -d prometheus grafana cadvisor pushgateway
docker compose ps
```

Expected: `prometheus`, `grafana`, `cadvisor`, `pushgateway` 모두 `running` 상태

- [ ] **Step 6: 서비스 헬스 확인**

```bash
curl -s http://localhost:9090/-/healthy   # Prometheus OK
curl -s http://localhost:3000/api/health  # {"database": "ok",...}
curl -s http://localhost:9091/-/healthy   # Pushgateway OK
```

- [ ] **Step 7: 커밋**

```bash
git add docker-compose.yml prometheus/ grafana/
git commit -m "feat: Prometheus + Grafana + cAdvisor + Pushgateway 관측성 스택 추가"
```

---

## Task 2: FastAPI 서비스 Prometheus 계측 + 평가 엔드포인트

**Files:**
- Modify: `llm_server/requirements.txt`
- Modify: `llm_server/main.py`
- Modify: `rag_server/requirements.txt`
- Modify: `rag_server/main.py`

**Interfaces:**
- Produces:
  - `GET http://localhost:8001/metrics` → Prometheus text format
  - `GET http://localhost:8000/metrics` → Prometheus text format
  - `POST http://localhost:8001/api/eval/generate` → `{"output": str, "token_count": int, "duration_ms": int}`
  - `POST http://localhost:8000/api/eval/generate` → `{"output": str, "token_count": int, "duration_ms": int}`
- Consumes: Task 1의 Prometheus scrape config가 이 엔드포인트를 수집함

- [ ] **Step 1: llm_server/requirements.txt에 prometheus_client 추가**

기존 파일 끝에 추가:
```
prometheus_client>=0.20.0
```

- [ ] **Step 2: llm_server/main.py에 Prometheus 계측 추가**

파일 상단 import 섹션에 추가:
```python
import time
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from fastapi import Response
```

`app = FastAPI(...)` 선언 바로 아래에 메트릭 정의 추가:
```python
LLM_TOKEN_COUNTER = Counter(
    'llm_tokens_total',
    'Total tokens processed by llm-server',
    ['service']
)
LLM_REQUEST_DURATION = Histogram(
    'llm_request_duration_seconds',
    'LLM request duration in seconds',
    ['service'],
    buckets=[1, 5, 10, 30, 60, 120]
)
```

기존 `@app.get("/health")` 엔드포인트 위에 추가:
```python
@app.get("/metrics")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


class EvalRequest(BaseModel):
    text: str
    perspectives: List[str] = []
    llm_api_key: Optional[str] = None


@app.post("/api/eval/generate")
async def eval_generate(req: EvalRequest):
    start = time.time()
    raw_output = await call_llm(req.text, req.perspectives, "", req.llm_api_key)
    duration_s = time.time() - start
    token_count = len(req.text.split()) + len(str(raw_output).split())

    LLM_TOKEN_COUNTER.labels(service="llm").inc(token_count)
    LLM_REQUEST_DURATION.labels(service="llm").observe(duration_s)

    return {
        "output": raw_output,
        "token_count": token_count,
        "duration_ms": int(duration_s * 1000)
    }
```

- [ ] **Step 3: rag_server/requirements.txt에 prometheus_client 추가**

기존 파일 끝에 추가:
```
prometheus_client>=0.20.0
```

- [ ] **Step 4: rag_server/main.py에 Prometheus 계측 추가**

파일 상단 import 섹션에 추가:
```python
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from fastapi import Response
```

`app = FastAPI(...)` 선언 바로 아래에 메트릭 정의 추가:
```python
RAG_TOKEN_COUNTER = Counter(
    'rag_tokens_total',
    'Total tokens processed by rag-server',
    ['service']
)
RAG_REQUEST_DURATION = Histogram(
    'rag_request_duration_seconds',
    'RAG request duration in seconds',
    ['service'],
    buckets=[1, 5, 10, 30, 60, 120]
)
```

기존 `@app.get("/health")` 엔드포인트 위에 추가:
```python
@app.get("/metrics")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


class EvalRequest(BaseModel):
    text: str
    perspectives: List[str] = []
    llm_api_key: Optional[str] = None


@app.post("/api/eval/generate")
async def eval_generate_rag(req: EvalRequest):
    start = time.time()

    parsed_documents = [{"text": req.text, "file_id": "eval-doc", "file_name": "eval.txt"}]
    chunks = chunk_document(req.text, "eval-doc", "eval.txt")
    vector_db_manager.add_chunks(chunks)

    requirements = await extract_requirements(parsed_documents, req.llm_api_key)

    all_test_cases = []
    for requirement in requirements:
        evidences = retrieve_evidences(requirement["text"], exclude_chunk_ids=set())
        prompt = build_prompt(requirement["text"], evidences[:3], "", req.perspectives)
        raw_tcs = await call_llm_with_key(prompt, req.llm_api_key)
        all_test_cases.extend(raw_tcs)

    duration_s = time.time() - start
    token_count = len(req.text.split())

    RAG_TOKEN_COUNTER.labels(service="rag").inc(token_count)
    RAG_REQUEST_DURATION.labels(service="rag").observe(duration_s)

    return {
        "output": str(all_test_cases),
        "token_count": token_count,
        "duration_ms": int(duration_s * 1000)
    }
```

- [ ] **Step 5: 서비스 재빌드 및 /metrics 확인**

```bash
docker compose up -d --build llm-server rag-server
curl -s http://localhost:8001/metrics | grep "llm_tokens"
curl -s http://localhost:8000/metrics | grep "rag_tokens"
```

Expected: 두 출력 모두 `# HELP llm_tokens_total ...` 형태의 Prometheus 텍스트 포함

- [ ] **Step 6: eval 엔드포인트 확인**

```bash
curl -s -X POST http://localhost:8001/api/eval/generate \
  -H "Content-Type: application/json" \
  -d '{"text": "사용자는 이메일로 로그인할 수 있어야 한다.", "perspectives": ["보안"], "llm_api_key": "YOUR_KEY"}' | python3 -m json.tool
```

Expected: `{"output": "...", "token_count": <int>, "duration_ms": <int>}` 형태

- [ ] **Step 7: 커밋**

```bash
git add llm_server/requirements.txt llm_server/main.py rag_server/requirements.txt rag_server/main.py
git commit -m "feat: FastAPI 서비스에 Prometheus 계측 및 평가 엔드포인트 추가"
```

---

## Task 3: Spring Boot Actuator + Micrometer Prometheus 계측

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: `GET http://localhost:8080/actuator/prometheus` → Prometheus text format
- Consumes: Task 1의 Prometheus가 이 엔드포인트를 scrape함

- [ ] **Step 1: pom.xml에 의존성 추가**

`<dependencies>` 블록 안 기존 `spring-boot-starter-test` 바로 아래에 추가:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- [ ] **Step 2: application.yml에 Actuator 설정 추가**

`backend/src/main/resources/application.yml` 파일 끝에 추가:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  endpoint:
    prometheus:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
```

- [ ] **Step 3: 로컬에서 backend 재시작 후 확인**

```bash
docker compose up -d --build backend
curl -s http://localhost:8080/actuator/prometheus | head -20
```

Expected: `# HELP jvm_memory_used_bytes ...` 등 JVM 메트릭 포함된 Prometheus 텍스트

- [ ] **Step 4: Prometheus 타겟 확인**

브라우저에서 `http://localhost:9090/targets` 접속 → `backend (1/1 up)` 확인

- [ ] **Step 5: 커밋**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml
git commit -m "feat: Spring Boot Actuator + Micrometer Prometheus 메트릭 노출"
```

---

## Task 4: Ground Truth 정답셋 + eval_runner.py 구현

**Files:**
- Create: `evaluation/ground_truth/cases.jsonl`
- Create: `evaluation/llm_judge_prompt.txt`
- Create: `evaluation/eval_runner.py`
- Create: `evaluation/requirements.txt`

**Interfaces:**
- Consumes:
  - `POST http://localhost:8001/api/eval/generate` (Task 2)
  - `POST http://localhost:8000/api/eval/generate` (Task 2)
  - `POST http://localhost:9091/metrics/job/eval_runner` (Task 1 Pushgateway)
- Produces: Pushgateway에 `eval_accuracy`, `eval_judge_score`, `eval_token_count` 메트릭 push. 환경변수 `COVERAGE_BACKEND`, `COVERAGE_RAG_SERVER`, `COVERAGE_LLM_SERVER`로 커버리지 메트릭도 push.

- [ ] **Step 1: evaluation/requirements.txt 작성**

```
httpx>=0.27.0
prometheus_client>=0.20.0
python-dotenv>=1.0.0
```

- [ ] **Step 2: evaluation/ground_truth/cases.jsonl 작성 (10개 케이스)**

각 줄이 독립 JSON 객체:

```jsonl
{"id": "TC-001", "input_doc": "사용자는 이메일과 비밀번호로 로그인할 수 있어야 한다. 잘못된 비밀번호 입력 시 오류 메시지를 표시한다.", "expected_keywords": ["로그인", "이메일", "비밀번호", "인증", "오류"], "expected_test_case_count": 2}
{"id": "TC-002", "input_doc": "사용자는 상품 목록을 가격 오름차순 또는 내림차순으로 정렬할 수 있어야 한다.", "expected_keywords": ["정렬", "가격", "상품", "오름차순", "내림차순"], "expected_test_case_count": 2}
{"id": "TC-003", "input_doc": "관리자는 사용자 계정을 비활성화하거나 삭제할 수 있어야 한다. 삭제된 계정은 복구할 수 없다.", "expected_keywords": ["관리자", "계정", "비활성화", "삭제"], "expected_test_case_count": 2}
{"id": "TC-004", "input_doc": "파일 업로드 기능은 PDF, DOCX 형식만 허용하며 최대 10MB까지 업로드 가능하다.", "expected_keywords": ["파일", "업로드", "PDF", "DOCX", "용량"], "expected_test_case_count": 3}
{"id": "TC-005", "input_doc": "결제 완료 후 사용자에게 이메일 영수증이 자동 발송되어야 한다.", "expected_keywords": ["결제", "이메일", "영수증", "발송"], "expected_test_case_count": 2}
{"id": "TC-006", "input_doc": "시스템은 동시에 최대 1000명의 사용자 요청을 처리할 수 있어야 한다. 처리 시간은 3초 이내여야 한다.", "expected_keywords": ["동시", "성능", "응답시간", "부하"], "expected_test_case_count": 2}
{"id": "TC-007", "input_doc": "사용자는 비밀번호를 변경할 수 있으며, 현재 비밀번호 확인 후 새 비밀번호를 설정한다. 새 비밀번호는 8자 이상이어야 한다.", "expected_keywords": ["비밀번호", "변경", "확인", "보안"], "expected_test_case_count": 3}
{"id": "TC-008", "input_doc": "검색 기능은 상품명, 카테고리, 브랜드로 필터링이 가능하며 실시간 검색 결과를 반환한다.", "expected_keywords": ["검색", "필터", "카테고리", "브랜드"], "expected_test_case_count": 3}
{"id": "TC-009", "input_doc": "장바구니에 담긴 상품의 수량을 변경하거나 삭제할 수 있다. 수량이 0이 되면 자동으로 삭제된다.", "expected_keywords": ["장바구니", "수량", "삭제", "변경"], "expected_test_case_count": 2}
{"id": "TC-010", "input_doc": "API 응답은 항상 JSON 형식이어야 하며, 오류 발생 시 HTTP 상태 코드와 오류 메시지를 포함해야 한다.", "expected_keywords": ["API", "JSON", "HTTP", "오류", "상태코드"], "expected_test_case_count": 2}
```

- [ ] **Step 3: evaluation/llm_judge_prompt.txt 작성**

```
당신은 소프트웨어 테스트 케이스 품질 평가 전문가입니다.

아래의 요구사항과 그에 대해 생성된 테스트 케이스를 보고, 테스트 케이스의 품질을 0점부터 10점으로 평가하세요.

[요구사항]
{requirement}

[생성된 테스트 케이스]
{output}

평가 기준:
- 요구사항을 충분히 커버하는가 (0~4점)
- 테스트 단계가 구체적이고 실행 가능한가 (0~3점)
- 경계값, 예외 케이스를 다루는가 (0~3점)

반드시 아래 형식으로만 답변하세요:
SCORE: <0~10 사이의 정수>
REASON: <한 문장 이유>
```

- [ ] **Step 4: evaluation/eval_runner.py 작성**

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
    """LLM-as-Judge: rag_server 엔드포인트를 채점 모델로 재사용."""
    if not output.strip():
        return 0.0
    prompt = judge_prompt_template.replace("{requirement}", requirement).replace("{output}", output[:2000])
    try:
        response = httpx.post(
            f"{RAG_SERVER_URL}/api/eval/generate",
            json={"text": prompt, "perspectives": [], "llm_api_key": LLM_API_KEY},
            timeout=120.0
        )
        response.raise_for_status()
        raw = response.json().get("output", "")
        for line in str(raw).splitlines():
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
```

- [ ] **Step 5: eval_runner 로컬 테스트 (서비스가 실행 중인 상태에서)**

```bash
cd /path/to/RAG-Combination
pip install -r evaluation/requirements.txt
LLM_API_KEY=your_key python evaluation/eval_runner.py
```

Expected: 각 케이스 평가 로그 출력 후 `Metrics pushed to http://localhost:9091` 메시지 및 `=== 요약 ===` 출력

- [ ] **Step 6: Pushgateway에서 메트릭 확인**

```bash
curl -s http://localhost:9091/metrics | grep eval_accuracy
```

Expected: `eval_accuracy{job="eval_runner",mode="llm"} 0.XXXX` 형태의 줄 2개 (llm, rag)

- [ ] **Step 7: 커밋**

```bash
git add evaluation/
git commit -m "feat: Ground Truth 정답셋 + eval_runner.py 평가 스크립트 구현"
```

---

## Task 5: Grafana 대시보드 JSON 작성

**Files:**
- Create: `grafana/dashboards/yeonam-overview.json`

**Interfaces:**
- Consumes: Task 1의 Grafana 프로비저닝 설정이 이 파일을 자동 로드함
- Consumes: Task 4의 Pushgateway 메트릭 (`eval_accuracy`, `eval_judge_score` 등)
- Consumes: Task 2의 FastAPI 메트릭, Task 3의 Spring Boot 메트릭

- [ ] **Step 1: grafana/dashboards/yeonam-overview.json 작성**

```json
{
  "uid": "yeonam-overview",
  "title": "연암 테스터 관측성",
  "schemaVersion": 38,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-7d", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "row",
      "title": "LLM vs RAG 비교",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 },
      "collapsed": false
    },
    {
      "id": 2,
      "type": "bargauge",
      "title": "정확도 비교 (키워드 매칭 %)",
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 1 },
      "options": {
        "orientation": "horizontal",
        "reduceOptions": { "calcs": ["lastNotNull"] }
      },
      "targets": [
        {
          "expr": "eval_accuracy{job=\"eval_runner\"}",
          "legendFormat": "{{mode}}"
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
      "id": 3,
      "type": "bargauge",
      "title": "LLM-as-Judge 점수 (0~10)",
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 1 },
      "options": {
        "orientation": "horizontal",
        "reduceOptions": { "calcs": ["lastNotNull"] }
      },
      "targets": [
        {
          "expr": "eval_judge_score{job=\"eval_runner\"}",
          "legendFormat": "{{mode}}"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "min": 0,
          "max": 10,
          "thresholds": {
            "steps": [
              { "color": "red", "value": 0 },
              { "color": "yellow", "value": 5 },
              { "color": "green", "value": 7.5 }
            ]
          }
        }
      }
    },
    {
      "id": 4,
      "type": "bargauge",
      "title": "평균 토큰 사용량",
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 1 },
      "options": {
        "orientation": "horizontal",
        "reduceOptions": { "calcs": ["lastNotNull"] }
      },
      "targets": [
        {
          "expr": "eval_token_count{job=\"eval_runner\"}",
          "legendFormat": "{{mode}}"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "min": 0,
          "thresholds": {
            "steps": [
              { "color": "green", "value": 0 },
              { "color": "yellow", "value": 1000 },
              { "color": "red", "value": 3000 }
            ]
          }
        }
      }
    },
    {
      "id": 5,
      "type": "row",
      "title": "테스트 커버리지",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 9 },
      "collapsed": false
    },
    {
      "id": 6,
      "type": "gauge",
      "title": "Backend 커버리지",
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 10 },
      "options": { "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [
        {
          "expr": "eval_coverage_backend{job=\"eval_runner\"}",
          "legendFormat": "backend"
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
              { "color": "green", "value": 0.8 }
            ]
          }
        }
      }
    },
    {
      "id": 7,
      "type": "gauge",
      "title": "RAG Server 커버리지",
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 10 },
      "options": { "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [
        {
          "expr": "eval_coverage_rag_server{job=\"eval_runner\"}",
          "legendFormat": "rag-server"
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
              { "color": "green", "value": 0.8 }
            ]
          }
        }
      }
    },
    {
      "id": 8,
      "type": "gauge",
      "title": "LLM Server 커버리지",
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 10 },
      "options": { "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [
        {
          "expr": "eval_coverage_llm_server{job=\"eval_runner\"}",
          "legendFormat": "llm-server"
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
              { "color": "green", "value": 0.8 }
            ]
          }
        }
      }
    },
    {
      "id": 9,
      "type": "row",
      "title": "인프라 리소스",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 18 },
      "collapsed": false
    },
    {
      "id": 10,
      "type": "timeseries",
      "title": "컨테이너 CPU 사용률",
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 19 },
      "targets": [
        {
          "expr": "rate(container_cpu_usage_seconds_total{image!=\"\",name=~\"yeonam.*\"}[2m])",
          "legendFormat": "{{name}}"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "percentunit" } }
    },
    {
      "id": 11,
      "type": "timeseries",
      "title": "컨테이너 메모리 사용량",
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 19 },
      "targets": [
        {
          "expr": "container_memory_usage_bytes{image!=\"\",name=~\"yeonam.*\"}",
          "legendFormat": "{{name}}"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "bytes" } }
    },
    {
      "id": 12,
      "type": "timeseries",
      "title": "LLM/RAG 요청 처리 시간 (p50)",
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 19 },
      "targets": [
        {
          "expr": "histogram_quantile(0.5, rate(llm_request_duration_seconds_bucket[5m]))",
          "legendFormat": "llm p50"
        },
        {
          "expr": "histogram_quantile(0.5, rate(rag_request_duration_seconds_bucket[5m]))",
          "legendFormat": "rag p50"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "s" } }
    }
  ]
}
```

- [ ] **Step 2: Grafana 재시작 후 대시보드 확인**

```bash
docker compose restart grafana
```

브라우저에서 `http://localhost:3000` 접속 → "연암 테스터 관측성" 대시보드 확인 (로그인 없이 읽기 전용 접근 가능)

- [ ] **Step 3: 커밋**

```bash
git add grafana/dashboards/yeonam-overview.json
git commit -m "feat: Grafana 연암 테스터 관측성 대시보드 JSON 추가"
```

---

## Task 6: GitHub Actions eval.yml + EC2 crontab 등록

**Files:**
- Create: `.github/workflows/eval.yml`

**Interfaces:**
- Consumes: EC2에 배포된 llm-server, rag-server (localhost:8001, localhost:8000)
- Consumes: EC2의 Pushgateway (localhost:9091)
- Consumes: GitHub Secrets: `EC2_HOST`, `EC2_SSH_KEY`, `LLM_API_KEY`

- [ ] **Step 1: GitHub Secrets 확인**

기존 deploy.yml이 `EC2_HOST`와 `EC2_SSH_KEY`를 사용하므로 이미 설정되어 있음.
GitHub 레포 → Settings → Secrets → `LLM_API_KEY` 추가 필요.

- [ ] **Step 2: .github/workflows/eval.yml 작성**

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

      - name: Setup Java 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Run backend tests with JaCoCo
        working-directory: backend
        run: mvn test jacoco:report --no-transfer-progress -q
        continue-on-error: true

      - name: Parse backend coverage
        id: backend_cov
        run: |
          COV=$(python3 -c "
          import xml.etree.ElementTree as ET
          try:
              tree = ET.parse('backend/target/site/jacoco/jacoco.xml')
              root = tree.getroot()
              counter = next(c for c in root.findall('counter') if c.get('type') == 'INSTRUCTION')
              missed = int(counter.get('missed'))
              covered = int(counter.get('covered'))
              total = missed + covered
              print(round(covered / total, 4) if total else 0)
          except Exception:
              print(0)
          ")
          echo "value=$COV" >> $GITHUB_OUTPUT

      - name: Setup Python 3.10
        uses: actions/setup-python@v5
        with:
          python-version: '3.10'

      - name: Run RAG server tests with pytest-cov
        working-directory: rag_server
        run: |
          pip install -q pytest pytest-cov httpx python-dotenv
          pip install -q -r requirements.txt
          pytest --cov=. --cov-report=xml -q || true

      - name: Parse RAG server coverage
        id: rag_cov
        run: |
          COV=$(python3 -c "
          import xml.etree.ElementTree as ET
          try:
              tree = ET.parse('rag_server/coverage.xml')
              root = tree.getroot()
              rate = float(root.get('line-rate', 0))
              print(round(rate, 4))
          except Exception:
              print(0)
          ")
          echo "value=$COV" >> $GITHUB_OUTPUT

      - name: Run LLM server tests with pytest-cov
        working-directory: llm_server
        run: |
          pip install -q pytest pytest-cov httpx python-dotenv
          pip install -q -r requirements.txt
          pytest --cov=. --cov-report=xml -q || true

      - name: Parse LLM server coverage
        id: llm_cov
        run: |
          COV=$(python3 -c "
          import xml.etree.ElementTree as ET
          try:
              tree = ET.parse('llm_server/coverage.xml')
              root = tree.getroot()
              rate = float(root.get('line-rate', 0))
              print(round(rate, 4))
          except Exception:
              print(0)
          ")
          echo "value=$COV" >> $GITHUB_OUTPUT

      - name: Copy eval script to EC2 and run evaluation
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            set -e
            cd /home/ubuntu/app
            pip install -q -r evaluation/requirements.txt
            COVERAGE_BACKEND=${{ steps.backend_cov.outputs.value }} \
            COVERAGE_RAG_SERVER=${{ steps.rag_cov.outputs.value }} \
            COVERAGE_LLM_SERVER=${{ steps.llm_cov.outputs.value }} \
            LLM_API_KEY=${{ secrets.LLM_API_KEY }} \
            LLM_SERVER_URL=http://localhost:8001 \
            RAG_SERVER_URL=http://localhost:8000 \
            PUSHGATEWAY_URL=http://localhost:9091 \
            python3 evaluation/eval_runner.py
```

- [ ] **Step 3: EC2에 crontab 등록 (EC2에 SSH 접속 후 실행)**

```bash
# EC2 SSH 접속 후
crontab -e

# 아래 줄 추가 (매일 오전 9시 KST = UTC 0시)
0 0 * * * cd /home/ubuntu/app && LLM_API_KEY=<YOUR_KEY> LLM_SERVER_URL=http://localhost:8001 RAG_SERVER_URL=http://localhost:8000 PUSHGATEWAY_URL=http://localhost:9091 python3 evaluation/eval_runner.py >> /home/ubuntu/eval_cron.log 2>&1
```

- [ ] **Step 4: EC2 보안 그룹에 Grafana 포트 추가 (AWS 콘솔 또는 CLI)**

AWS 콘솔 → EC2 → 보안 그룹 → 인바운드 규칙 편집 → TCP 3000 추가 (소스: 0.0.0.0/0 또는 발표 시 접속할 IP)

- [ ] **Step 5: 기존 deploy.yml에 llm-server 배포 추가**

`.github/workflows/deploy-ec2.yml`의 `docker compose up -d --build backend rag-server` 줄을 수정:

```bash
docker compose up -d --build backend rag-server llm-server
docker compose up -d prometheus grafana cadvisor pushgateway
```

- [ ] **Step 6: main 브랜치에 push하여 eval.yml 동작 확인**

```bash
git add .github/workflows/eval.yml
git commit -m "feat: GitHub Actions LLM vs RAG 자동 평가 파이프라인 추가"
git push origin main
```

GitHub Actions 탭에서 "Evaluate LLM vs RAG" 워크플로우 실행 확인.

- [ ] **Step 7: Grafana에서 메트릭 수신 확인**

`http://<EC2-PUBLIC-IP>:3000` 접속 → "연암 테스터 관측성" 대시보드 → ROW 1 패널에 데이터 표시 확인

---

## 검증 체크리스트

모든 Task 완료 후 아래를 확인:

- [ ] `http://<EC2-IP>:3000` → 로그인 없이 대시보드 접근 가능
- [ ] ROW 1: LLM vs RAG 정확도 바 차트에 두 개의 바 표시
- [ ] ROW 2: 커버리지 게이지 3개에 0이 아닌 값 표시
- [ ] ROW 3: cAdvisor CPU/메모리 시계열에 `yeonam-*` 컨테이너 표시
- [ ] `http://<EC2-IP>:9091/metrics` → `eval_accuracy` 포함 확인
- [ ] GitHub Actions "Evaluate LLM vs RAG" 워크플로우 green
- [ ] EC2 crontab: `crontab -l`로 등록 확인
