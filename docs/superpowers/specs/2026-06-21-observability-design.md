# Observability & LLM vs RAG 비교 시각화 설계

**날짜:** 2026-06-21
**대상 프로젝트:** 연암 테스터 (Yeonam Tester)
**목적:** LLM 단독 vs RAG+LLM 파이프라인을 수치로 비교하고 Grafana로 시각화. CI/CD + 모니터링 운영 경험을 포트폴리오 스펙으로 확보.

---

## 1. 목표

- 비전공자 / 주니어 개발자에게 RAG의 필요성을 **수치로** 설득
- Grafana 대시보드 URL 하나로 발표 시 즉시 공유 가능
- GitHub Actions + EC2 cron으로 CI/CD + 모니터링 경험 동시 확보
- Phase 2 쿠버네티스 마이그레이션을 염두에 둔 구조로 설계

---

## 2. 전체 아키텍처

### Phase 1: EC2 docker-compose (현재 설계)

```
기존 서비스
├── nginx          (:80)
├── backend        (:8080)
├── rag-server     (:8000)
└── minio          (:9000/:9001)

이번에 docker-compose에 함께 추가
├── llm-server     (:8001)   ← 현재 디렉토리는 있으나 compose에 미등록 → 이번에 추가
├── prometheus     (:9090)   ← 메트릭 수집/저장
├── grafana        (:3000)   ← 시각화 대시보드 (외부 공개)
├── cadvisor       (:8081)   ← 컨테이너 CPU/메모리 자동 수집
└── pushgateway    (:9091)   ← CI/CD & cron 평가 결과 수신
```

**EC2 보안 그룹:** Grafana 접근을 위해 인바운드 규칙에 TCP 3000 포트 추가 필요. Pushgateway(9091), Prometheus(9090)는 외부 노출 불필요 — 내부 docker network로만 통신.

**데이터 흐름:**

```
cAdvisor ──────────────────────────────────┐
FastAPI /metrics (llm/rag server) ─────────┤──▶ Prometheus ──▶ Grafana (:3000)
Spring Boot /actuator/prometheus ──────────┘
                                            ▲
GitHub Actions eval_runner.py ──────────────┤
EC2 cron eval_runner.py ────────────────────┘
                    (via Pushgateway :9091)
```

### Phase 2: Kubernetes 마이그레이션 (향후)

| Phase 1 컴포넌트 | Phase 2 전환 대상 |
|-----------------|------------------|
| docker-compose 서비스 | Deployment + Service |
| EC2 cron | k8s CronJob |
| prometheus scrape config | Prometheus Operator ServiceMonitor |
| grafana/dashboards/*.json | ConfigMap 마운트 |

**k8s 준비 조건 (Phase 1에서 미리 적용):**
- 모든 컨테이너에 `app`, `component`, `phase` 레이블 통일
- Prometheus scrape config를 ServiceMonitor와 1:1 대응 구조로 작성
- eval_runner.py는 외부 HTTP 호출만 하는 독립 스크립트로 유지

---

## 3. 메트릭 수집 계층

| 메트릭 | 출처 | 수집 방법 |
|--------|------|-----------|
| 컨테이너 CPU / 메모리 | 모든 서비스 | cAdvisor 자동 수집 (코드 수정 없음) |
| LLM 호출 토큰 수 | llm_server, rag_server | `prometheus_client` `/metrics` 엔드포인트 |
| 요청 처리 시간 | llm_server, rag_server | `prometheus_client` Histogram |
| Spring Boot JVM / HTTP | backend | `spring-boot-actuator` + `micrometer-registry-prometheus` → `/actuator/prometheus` |
| LLM vs RAG 정확도 점수 | eval_runner.py | Pushgateway push |
| LLM-as-Judge 점수 | eval_runner.py | Pushgateway push |
| 테스트 커버리지 % | GitHub Actions | JaCoCo(backend) + pytest-cov(python) 파싱 후 Pushgateway push |

---

## 4. 평가 프레임워크

### 디렉토리 구조

```
evaluation/
├── ground_truth/
│   └── cases.jsonl          # 수동 작성 정답셋
├── eval_runner.py           # 평가 실행 스크립트
└── llm_judge_prompt.txt     # LLM-as-Judge 채점 프롬프트 템플릿
```

### 정답셋 포맷 (cases.jsonl)

```json
{
  "id": "TC-001",
  "input_doc": "사용자는 로그인할 수 있어야 한다.",
  "expected_keywords": ["로그인", "인증", "세션"],
  "expected_test_case_count": 3
}
```

### eval_runner.py 실행 흐름

```
cases.jsonl 로드
    │
    ├─▶ llm_server 호출 (RAG 없이) ──▶ 출력 저장
    └─▶ rag_server 호출 (RAG 포함) ──▶ 출력 저장
           │
           ├─▶ 키워드 매칭 정확도 계산 (수동 정답셋 기준)
           ├─▶ LLM-as-Judge 보조 채점 (0~10점)
           │     └─ 채점 모델: rag_server와 동일한 LLM 엔드포인트 재사용
           │        (별도 API 키 불필요, llm_judge_prompt.txt 프롬프트 적용)
           └─▶ 토큰 수 기록
                    │
                    └─▶ Pushgateway로 메트릭 push
```

### Pushgateway 메트릭 목록

```
eval_accuracy{mode="llm"}      # 키워드 매칭 정확도 (0~1)
eval_accuracy{mode="rag"}
eval_judge_score{mode="llm"}   # LLM-as-Judge 점수 (0~10)
eval_judge_score{mode="rag"}
eval_token_count{mode="llm"}   # 호출당 평균 토큰 수
eval_token_count{mode="rag"}
eval_coverage_backend          # JaCoCo 커버리지 (0~1)
eval_coverage_rag_server       # pytest-cov 커버리지 (0~1)
eval_coverage_llm_server       # pytest-cov 커버리지 (0~1)
```

---

## 5. CI/CD + Cron 실행 흐름

### GitHub Actions (.github/workflows/eval.yml)

트리거: `main` 브랜치 push

```
1. Python 환경 설정
2. backend 테스트 실행 → JaCoCo 커버리지 리포트 생성
3. python 서비스 테스트 실행 → pytest-cov 리포트 생성
4. eval_runner.py 실행 (llm_server, rag_server 호출)
5. 커버리지 % 파싱 → Pushgateway push
6. 평가 정확도, 토큰 수 → Pushgateway push
```

push마다 Grafana에 데이터 포인트가 찍혀 "코드 변경에 따른 RAG 품질 추이" 추적 가능.

### EC2 Cron

```bash
# crontab -e
0 9 * * * cd /app && python evaluation/eval_runner.py
```

매일 오전 9시 실행 → 장기 운영 시 시계열 데이터 누적. 발표 시 "지난 30일간 RAG 정확도 추이" 시각화 가능.

---

## 6. Grafana 대시보드 구성

### 레이아웃 (1개 대시보드, 3개 Row)

**ROW 1: LLM vs RAG 비교 (핵심 — 발표 첫 화면)**

| 패널 | 타입 | 메트릭 |
|------|------|--------|
| 정확도 비교 | Bar chart | `eval_accuracy{mode="llm/rag"}` |
| LLM-as-Judge 점수 | Bar chart | `eval_judge_score{mode="llm/rag"}` |
| 토큰 사용량 비교 | Bar chart | `eval_token_count{mode="llm/rag"}` |

**ROW 2: 테스트 커버리지**

| 패널 | 타입 | 메트릭 |
|------|------|--------|
| backend 커버리지 | Gauge | `eval_coverage_backend` |
| rag_server 커버리지 | Gauge | `eval_coverage_rag_server` |
| llm_server 커버리지 | Gauge | `eval_coverage_llm_server` |

**ROW 3: 인프라 리소스 (운영 모니터링)**

| 패널 | 타입 | 메트릭 |
|------|------|--------|
| 컨테이너 CPU | Time series | cAdvisor `container_cpu_usage_seconds_total` |
| 컨테이너 메모리 | Time series | cAdvisor `container_memory_usage_bytes` |
| 요청 처리 시간 | Time series | FastAPI Histogram |

### 접근 방법

- URL: `http://<EC2-PUBLIC-IP>:3000`
- 익명 열람(read-only) 허용 설정 → 로그인 없이 발표 시 바로 공유
- 관리자 계정 별도 (대시보드 편집용)

### 대시보드 코드화

- 대시보드 JSON export → `grafana/dashboards/yeonam-overview.json`으로 커밋
- Grafana 시작 시 자동 프로비저닝 (provisioning config 포함)
- Phase 2에서 k8s ConfigMap으로 1:1 전환

---

## 7. 구현 순서 (Phase 1)

1. `docker-compose.yml`에 Prometheus, Grafana, cAdvisor, Pushgateway 추가
2. `prometheus/prometheus.yml` scrape config 작성
3. `grafana/provisioning/` datasource + dashboard 자동 프로비저닝 설정
4. `evaluation/ground_truth/cases.jsonl` 정답셋 작성 (최소 10개 케이스)
5. `evaluation/eval_runner.py` 구현
6. FastAPI 서비스에 `prometheus_client` 메트릭 추가
7. Spring Boot에 Actuator + Micrometer 의존성 추가
8. `.github/workflows/eval.yml` CI 파이프라인 작성
9. EC2 crontab 등록
10. Grafana 대시보드 JSON 커밋

---

## 8. 향후 Phase 2 체크리스트 (참고용)

- [ ] 각 서비스를 k8s Deployment + Service yaml로 변환
- [ ] Prometheus Operator (kube-prometheus-stack) Helm 차트 설치
- [ ] eval_runner.py → k8s CronJob으로 전환
- [ ] Grafana 대시보드 JSON → ConfigMap으로 마운트
- [ ] EC2 → EKS 또는 kubeadm 클러스터로 이전
