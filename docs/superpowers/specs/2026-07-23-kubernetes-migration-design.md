# Kubernetes 전환 리팩토링 설계 — 연암 테스터 (Yeonam Tester)

> 작성일: 2026-07-23
> 목표: "Kubernetes 기반 서비스 운영 경험"을 **필수 역량**으로 내세울 수 있는 수준까지, 현재 Docker Compose 스택을 실제 코드/설정 리팩토링을 동반하여 K8s로 전환한다.
> 선행 문서: `2026-07-02-devops-portfolio-design.md`(로드맵 Phase 2). 본 문서는 그 로드맵을 **이 프로젝트에 실제로 적용할 때 반드시 풀어야 하는 blocker와 그 리팩토링 방법**까지 내려간 상세 설계다.

---

## 0. 이 설계의 전제 — "Deployment YAML 복붙"으로는 필수 역량이 안 된다

면접에서 K8s를 필수 역량으로 방어하려면 매니페스트 작성이 아니라 **"왜 이 워크로드는 그냥 replicas만 늘리면 깨지는가, 그래서 무엇을 고쳤는가"** 를 말할 수 있어야 한다. 이 프로젝트는 겉보기엔 stateless한 마이크로서비스 10개지만, 실제로는 **naive한 K8s 이식을 깨뜨리는 상태(state) 4종**을 품고 있다. 본 설계의 핵심은 이 4종을 식별하고 리팩토링하는 것이다.

| # | 숨은 상태(state) | 위치 | naive 이식 시 증상 | 본질적 해결 |
|---|------------------|------|--------------------|-------------|
| B1 | **H2 파일 DB** `jdbc:h2:file:./data/...;AUTO_SERVER=TRUE` | backend `/app/data` | `replicas:2` 순간 두 Pod가 같은 파일 DB를 놓고 경합 → 락/정합성 붕괴 | PostgreSQL 외부화(우선) 또는 StatefulSet replicas=1 |
| B2 | **in-memory `asyncio.Queue` 싱글턴 워커** | rag/llm-server | Pod 재시작·롤링업데이트·evict 시 **큐에 쌓인/처리 중 잡 유실**. `202 Accepted`만 주고 결과는 webhook 콜백 | API/Worker 분리 + 외부 큐(Redis/RabbitMQ) 또는 replicas=1 + graceful drain |
| B3 | **MinIO 단일 노드** `minio_data:/data` | minio | Pod 재스케줄 시 데이터 유실, 다중 replica 불가 | StatefulSet+PVC(로컬) / S3 외부화(prod) |
| B4 | **Qdrant 벡터DB** `qdrant_data:/qdrant/storage` | qdrant | Deployment로 이식하면 Pod 재스케줄 시 **색인된 벡터 전량 소실**. 지식카드 184건은 기동 시 재적재되지만 사용자 문서 청크는 원본 재업로드 없이 복구 불가 | StatefulSet + PVC(단일 노드) / Qdrant Cloud·다중 노드(prod) |

> 로드맵 문서(`2026-07-02`)의 `backend.yaml replicas: 2`는 **B1 때문에 그대로 적용하면 즉시 깨진다.** 이 설계는 그 함정을 명시적으로 교정한다. 바로 이 지점을 설명할 수 있는 게 "경험 있음"과 "해봤다고 적음"의 차이다.

---

## 1. 현재 아키텍처 스냅샷 (Docker Compose, 10 컨테이너)

`docker-compose.yml` / `.env.example` / 각 서비스 소스 기준 실측.

```
                         ┌──────────────── nginx :80 ────────────────┐
                         │  / → frontend/dist (Vite React SPA, Hash)  │
                         │  /api/ → backend:8080 (proxy, 120s, 20M)   │
                         └───────────────┬───────────────────────────┘
                                         │
                     ┌───────────────────┴────────────────────┐
                     ▼                                         ▼
        backend (Spring Boot 3.3 / Java17)          rag-server / llm-server (FastAPI)
        - H2 file DB  /app/data  (backend_data)     - asyncio.Queue 싱글턴 워커 (lifespan)
        - Actuator /actuator/health, /prometheus    - POST /analyze → 202 → webhook 콜백
        - AWS SDK S3(MinIO) + Bedrock               - /health(queue_size, qdrant), /metrics
        - non-root uid 1001                         - prod 이미지에 LangChain+torch 포함(실벡터 검색)
                     │                                         │
                     │                                         ▼
                     │                            Qdrant :6333/6334 (expose only)
                     │                            (qdrant_data) COSINE, dim 384
                     │                            문서 청크 + 지식카드 184건 단일 컬렉션
                     │                                         │
                     └──────────────► MinIO :9000/9001 ◄───────┘
                                      (minio_data)  S3 호환 스토리지

        관측: prometheus(:9090, prometheus_data, static_configs, 30d)
              grafana(:3000, grafana_data, provisioning) / cadvisor(host mounts) / pushgateway(:9091)
              └ evaluation/eval_runner.py 가 pushgateway로 배치 지표 push
```

핵심 사실(설계 결정의 근거):
- **backend**는 이미 `USER appuser`(uid 1001) 비루트, Actuator로 `health`/`prometheus` 노출 → 프로브·스크레이프 준비 양호. 단 H2 파일 DB가 scale 차단.
- **rag/llm-server**는 `python:3.10-slim`에서 **root로 구동**, 잡 상태가 프로세스 메모리(`asyncio.Queue`)에만 존재.
- **`/health` 품질이 두 서버에서 다르다.** `rag-server`는 Qdrant에 실제로 ping해 실패 시 **503**을 반환하므로 readiness probe로 바로 쓸 수 있다. 반면 `llm-server`는 여전히 항상 `healthy`를 반환(의존성 미검증)하므로 readiness로 쓰기엔 부족하고, 보정이 필요하다.
- **rag-server는 기동 시 지식카드를 Qdrant에 적재하고 실패하면 예외를 전파해 기동을 실패시킨다(fail-fast).** K8s에서는 이 동작이 CrashLoopBackOff로 나타나므로, Qdrant보다 rag-server가 먼저 뜨는 순서 문제를 initContainer 또는 재시도로 흡수해야 한다.
- **임베딩 모델을 프로세스 내부에서 로드한다**(`all-MiniLM-L6-v2`, 384차원). `langchain-huggingface` → `sentence-transformers` → `torch` 전이 의존이 있어 이미지와 런타임 메모리가 모두 무겁다. 리소스 산정 시 이 점이 지배적 변수다.
- **prometheus**는 `static_configs`로 DNS 이름(`backend:8080`, `rag-server:8000`...)을 직접 지정 → K8s에선 Pod IP가 유동적이라 **서비스 디스커버리로 교체 필요**.
- **cadvisor**는 `/`, `/var/run`, `/sys`, `/var/lib/docker` 호스트 마운트 → K8s에선 노드별 실행(DaemonSet) 또는 kubelet 내장 cAdvisor로 대체.
- **nginx**는 리버스 프록시 + SPA 정적 호스팅 이중 역할 → K8s에선 **Ingress(라우팅)** 와 **정적 서빙(별도 배포/오브젝트 스토리지)** 로 분해.

---

## 2. 타깃 아키텍처 (K8s)

네임스페이스 `yeonam` 하나로 통합. 로컬은 minikube/kind, prod는 EKS(또는 kubeadm) 동일 매니페스트 + overlay.

```
                    Ingress (ingress-nginx)
        host: yeonam.local / <ALB DNS>
        ├─ /            → svc/frontend  (nginx:alpine, SPA 정적, replicas 2, stateless)
        ├─ /api/        → svc/backend   (:8080)
        └─ (선택) /rag  → svc/rag-api   (:8000)   # 직접 트리거 노출이 필요할 때만
                 │
   ┌─────────────┼──────────────────────────────────────────────┐
   ▼             ▼                        ▼                       ▼
 Deployment    Deployment            Deployment              Deployment
 frontend      backend(*)            rag-api / llm-api       rag-worker / llm-worker
 (stateless)   ClusterIP :8080       (stateless, HPA)        (큐 소비, 별도 스케일)
               probes: actuator      probes: /health(startup/live/ready)
                 │                        │                       │
                 ▼                        ▼                       ▼
        StatefulSet postgres      ┌── Service redis (ClusterIP) ──┐   # B2 해결(외부 큐)
        (PVC 5Gi) 또는 RDS         잡 enqueue ↔ worker dequeue (durable)
                 │
        StatefulSet minio (PVC 20Gi)  또는  외부 S3   # B3 해결
                 │
        StatefulSet qdrant (PVC 5Gi, ClusterIP only)  # B4 해결
        6333 HTTP / 6334 gRPC — Ingress 비노출, rag-* 만 접근
                 │
   관측 스택(별도 namespace `monitoring` 권장):
     kube-prometheus-stack (Prometheus Operator)
       - ServiceMonitor: backend(/actuator/prometheus), rag/llm(/metrics)
       - node-exporter + kube-state-metrics (cadvisor 대체는 kubelet 내장)
       - Pushgateway(배치 eval), Grafana(provisioning 그대로 이관)

 (*) backend는 B1 해결 방식에 따라 Deployment(Postgres 외부화) 또는 StatefulSet replicas=1(H2 유지) 택1
```

---

## 3. 매니페스트 레이아웃 (Kustomize base/overlay)

환경별 차이(이미지 태그, replicas, 스토리지 클래스, 도메인, secret 소스)를 overlay로 분리해 **"같은 매니페스트, 변수만 교체"** 를 증명한다.

```
k8s/
├── base/
│   ├── namespace.yaml
│   ├── config/
│   │   ├── app-config.yaml          # ConfigMap: 비밀 아닌 환경변수
│   │   └── app-secret.yaml          # Secret 템플릿(값은 overlay/외부주입)
│   ├── backend/
│   │   ├── deployment.yaml          # or statefulset.yaml (B1 선택)
│   │   ├── service.yaml
│   │   ├── pdb.yaml
│   │   └── serviceaccount.yaml
│   ├── rag/
│   │   ├── api-deployment.yaml       # stateless, HPA 대상
│   │   ├── worker-deployment.yaml    # 큐 소비, graceful drain
│   │   ├── service.yaml
│   │   ├── hpa.yaml
│   │   └── pdb.yaml
│   ├── llm/                          # rag와 동형
│   ├── frontend/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── data/
│   │   ├── postgres-statefulset.yaml # B1 (prod는 overlay에서 제거→RDS)
│   │   ├── redis.yaml                # B2
│   │   ├── minio-statefulset.yaml    # B3 (prod는 overlay에서 제거→S3)
│   │   └── qdrant-statefulset.yaml   # B4 (PVC 5Gi, ClusterIP only)
│   ├── ingress.yaml
│   └── kustomization.yaml
└── overlays/
    ├── local/                        # minikube: hostpath SC, replicas 1, minio+postgres 포함
    │   ├── kustomization.yaml
    │   └── patches/
    └── prod/                         # EKS: gp3 SC, replicas↑, RDS/S3 외부화, ExternalSecrets
        ├── kustomization.yaml
        └── patches/
```

관측 스택은 `kube-prometheus-stack` Helm chart를 `monitoring` 네임스페이스에 설치하고, 앱은 ServiceMonitor만 base에 둔다(운영 성숙도 신호).

---

## 4. 서비스별 오브젝트 선택과 리팩토링 상세

### 4.1 backend — B1(H2) 해결이 핵심

두 트랙을 제시하되, **포트폴리오 방어력은 트랙 A(PostgreSQL 외부화)가 압도적**이다. "왜 StatefulSet 대신 DB를 뺐나"를 말할 수 있어야 하기 때문.

**트랙 A (권장) — PostgreSQL 외부화 → backend를 진짜 stateless Deployment로**
- `pom.xml`: `org.postgresql:postgresql` 런타임 의존성 추가, H2는 test scope로 강등.
- `application.yml` datasource를 env 주입으로:
  ```yaml
  spring:
    datasource:
      url: ${DB_URL:jdbc:postgresql://postgres:5432/yeonam}
      username: ${DB_USER:yeonam}
      password: ${DB_PASSWORD}
      driver-class-name: org.postgresql.Driver
    jpa:
      properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
    sql.init.mode: never          # 스키마는 Flyway/Liquibase로 승격 권장
  ```
- `schema.sql`(H2 방언) → **Flyway 마이그레이션**(`V1__init.sql`, Postgres 방언)으로 전환. `ddl-auto: none` 유지.
- H2 콘솔/`web-allow-others: true`/`AUTO_SERVER` 제거(운영 노출 위험).
- 결과: backend `Deployment replicas: 2+`, `RollingUpdate maxSurge:1 maxUnavailable:0`, PVC 불필요. **B1 소멸 → 로드맵의 replicas:2가 비로소 성립.**
- Postgres 자체는 로컬 overlay에서 `StatefulSet(PVC 5Gi)`, prod overlay에서 리소스 제거 후 RDS 엔드포인트를 `DB_URL` Secret으로 주입.

**트랙 B (최소 변경) — H2 유지**
- backend를 `StatefulSet replicas: 1` + `volumeClaimTemplates`(PVC `/app/data`), `updateStrategy: RollingUpdate`.
- `AUTO_SERVER=TRUE` 제거(단일 Pod이므로 불필요, 오히려 위험). scale 불가임을 명시적으로 문서화.
- 한계: 수평 확장·무중단 배포(재시작 중 짧은 다운) 제약 → "그래서 다음 단계가 트랙 A"라는 서사로 연결.

**프로브(공통, 이미 Actuator 보유):** Spring Boot health group으로 liveness/readiness 분리.
```yaml
# application.yml
management.endpoint.health.probes.enabled: true
management.health.readiness-state.enabled: true
management.endpoint.health.group.readiness.include: db,diskSpace,readinessState
```
```yaml
# deployment.yaml
startupProbe:   { httpGet: { path: /actuator/health/liveness,  port: 8080 }, failureThreshold: 30, periodSeconds: 5 }
livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8080 }, periodSeconds: 10 }
readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 }, periodSeconds: 5 }
```
- `resources`: requests `cpu:250m mem:512Mi` / limits `cpu:1 mem:1Gi`(Tika 파싱·JVM 고려). `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75`로 컨테이너 인지.
- `PodDisruptionBudget minAvailable: 1`.

### 4.2 rag-server / llm-server — B2(in-memory 큐) 해결이 핵심

현재 흐름: `POST /analyze` → `queue_manager.add_job()`(메모리 큐) → `202` 즉시 응답 → lifespan 워커가 `process_job` 실행 → 완료 시 backend로 webhook 콜백. **API 응답과 실제 처리가 프로세스 메모리로만 연결**되어 있어, Pod가 롤링업데이트/HPA scale-in/OOM으로 죽으면 그 안의 잡이 조용히 증발한다.

**트랙 A (권장) — API/Worker 분리 + 외부 큐(Redis)**
1. `queue_manager.py`를 Redis 기반으로 교체(예: `redis` 리스트 `BRPOPLPUSH` 또는 Redis Streams로 at-least-once + ack).
   - enqueue: `POST /analyze` 핸들러가 job JSON을 `LPUSH yeonam:rag:jobs`.
   - dequeue: 워커 프로세스가 `BRPOPLPUSH jobs → processing`, 완료 후 `LREM processing`. 실패/타임아웃 잡은 처리 리스트에 남아 재처리 가능.
2. **컨테이너 역할 분리(동일 이미지, 다른 command)**:
   - `rag-api` Deployment: `uvicorn main:app`(엔드포인트만, 워커 미기동) → **stateless → HPA/롤링업데이트 안전**.
   - `rag-worker` Deployment: `python worker.py`(큐 소비 루프) → 처리량에 맞춰 독립 스케일.
   - lifespan에서 무조건 워커를 띄우던 코드를 `RUN_WORKER` 플래그로 분기(같은 이미지 유지).
3. 효과: API는 몇 개든 띄워도 안전, 잡은 Redis에 durable하게 남아 워커 재시작에도 생존. **B2 소멸.** 면접에서 "202 fire-and-forget의 유실 위험을 어떻게 제거했나"에 정면 답변 가능.

**트랙 B (최소 변경) — 단일 워커 + graceful drain**
- rag/llm 각각 `Deployment replicas: 1`, `strategy: Recreate`(동시 두 워커가 같은 잡 소스를 나눠 갖는 혼란 방지).
- **우아한 종료**로 유실 최소화:
  ```yaml
  terminationGracePeriodSeconds: 120
  lifecycle: { preStop: { exec: { command: ["sh","-c","sleep 5"] } } }   # LB 디등록 유예
  ```
  코드 측: SIGTERM 수신 시 lifespan shutdown에서 `queue.join()`으로 **잔여 잡 소진 후 종료**(현재 `stop_worker`는 `cancel()`이라 잔여 잡 폐기 → drain 로직 추가 필요).
- 한계: 여전히 메모리 큐라 SIGKILL(강제 OOM)엔 유실. "그래서 트랙 A가 목표"로 서사 연결.

**프로브 리팩토링(공통, 현재 `/health` 부실):**
- **rag-server**: `/health`가 이미 Qdrant ping 결과를 반영해 실패 시 503을 주므로 **readiness probe로 그대로 사용 가능**. 단 liveness에 같은 경로를 쓰면 Qdrant 일시 장애로 rag-server Pod까지 재시작되는 연쇄가 생기므로, **liveness는 의존성을 보지 않는 경로**로 분리해야 한다.
- **llm-server**: `/health`가 항상 `healthy` → **liveness 전용**으로만 사용. readiness로 쓰려면 S3 도달성 등 의존성 검증을 추가해야 한다.
- **readiness 신설**(llm-server 및 트랙 A): 시작 시 지연 로드(모델 로드, S3 도달성)를 반영. 예 `/ready`가 모델 로드 완료 + (트랙 A면) Redis PING 성공 시 200.
- **startupProbe**로 느린 초기화(litellm/pymupdf import, 임베딩 모델 로드, 지식카드 184건 적재) 흡수. rag-server는 기동 시 임베딩 모델을 로드하고 지식카드를 적재하므로 초기화가 특히 느리다. `failureThreshold`를 넉넉히 잡지 않으면 정상 기동 중에 죽는다:
  ```yaml
  startupProbe:  { httpGet: { path: /health, port: 8000 }, failureThreshold: 30, periodSeconds: 5 }
  livenessProbe: { httpGet: { path: /health, port: 8000 }, periodSeconds: 15 }
  readinessProbe:{ httpGet: { path: /ready,  port: 8000 }, periodSeconds: 5 }
  ```
- **HPA**(rag-api): CPU 임베딩/파싱 부하 반영, `min:2 max:5 averageUtilization:70`. 워커는 큐 depth 기반 KEDA(선택, 우대) 또는 수동 replicas.
- **비루트화**: Dockerfile에 `useradd -u 1001 app && USER 1001`, `securityContext.runAsNonRoot: true`, `readOnlyRootFilesystem: true`(+ `/tmp` emptyDir). 현재 root 구동은 보안 감점.
- `resources`: **초안의 requests `mem:512Mi` / limits `mem:1Gi`는 "prod 이미지에 벡터 검색 라이브러리 미포함"이라는 이미 무효한 전제로 산정된 값이다.** 현재 `requirements.prod.txt`는 LangChain + `sentence-transformers` + `torch`(CPU 휠)를 포함하고, rag-server는 프로세스 내에서 `all-MiniLM-L6-v2`를 로드한다. torch 런타임과 모델 상주 메모리를 고려하면 **limits `mem:1Gi`는 OOMKilled 위험이 높다.**
  - **반드시 실측 후 확정할 것.** `docker stats yeonam-rag`로 기동 직후(모델 로드 완료) 및 분석 처리 중 RSS를 측정하는 것이 선행 과제다.
  - 실측 전 임시 출발점: requests `cpu:500m mem:1Gi` / limits `cpu:1 mem:2Gi`.
  - `llm-server`는 임베딩 모델을 로드하지 않고 해시 기반 `NumpyChunkStore`만 쓰므로 훨씬 가볍다. **rag/llm에 같은 리소스 값을 쓰지 말고 분리 산정할 것.**
  - 워커와 API를 같은 이미지로 분리 배포하면 **양쪽 모두 모델을 로드**하므로 메모리가 두 배로 든다. 임베딩이 실제로 필요한 쪽(워커)만 로드하도록 분기하는 것이 후속 과제다.

### 4.3 frontend (nginx SPA) — 역할 분해

- 현재 nginx 컨테이너가 (a) SPA 정적 서빙 + (b) `/api/` 프록시 이중 역할.
- K8s에선:
  - (b) 프록시/라우팅 → **Ingress**(`ingress-nginx`)가 담당. `/ → frontend`, `/api → backend`.
  - (a) 정적 서빙 → 작은 `frontend` Deployment(`nginx:stable-alpine` + 빌드된 `dist` COPY한 이미지). SPA fallback(`try_files ... /index.html`)은 이미지 내부 nginx.conf로 유지.
- Ingress 어노테이션으로 기존 nginx 설정 이관:
  ```yaml
  nginx.ingress.kubernetes.io/proxy-body-size: "20m"        # client_max_body_size 20M
  nginx.ingress.kubernetes.io/proxy-read-timeout: "120"     # proxy_read_timeout 120s
  ```
- 프론트는 완전 stateless → `replicas: 2`, HPA 불필요(정적).
- **중요 변경**: 프론트를 더 이상 compose처럼 host volume mount로 서빙하지 않고 **이미지에 빌드 산출물을 굽는다**(CI에서 `npm run build` → 이미지 태그). 이게 재현성의 핵심.

### 4.4 데이터 계층 — postgres / redis / minio / qdrant

- **postgres**(트랙 A): `StatefulSet replicas:1`, `volumeClaimTemplates`(PVC 5Gi), `Service`(headless), 프로브 `pg_isready`. prod overlay에서 매니페스트 제거 후 RDS 엔드포인트 주입.
- **redis**(B2 트랙 A): 로컬 `Deployment`(+ 선택적 PVC), prod `ElastiCache`. AOF 지속성 on 시 잡 유실 최소화.
- **minio**(B3): `StatefulSet replicas:1`, PVC 20Gi, `Service` 9000/9001. 버킷 부트스트랩은 `Job`(mc mb yeonam-documents/reports). prod overlay는 제거 후 앱의 `AWS_S3_ENDPOINT`를 실제 S3(빈 값=SDK 기본)로 전환 → 앱 코드는 이미 엔드포인트 스위치 지원(`.env.example` 주석 근거).
- **qdrant**(B4): `StatefulSet replicas:1`, `volumeClaimTemplates`(PVC 5Gi, `/qdrant/storage`), `Service` 6333(HTTP)/6334(gRPC). **Deployment로 만들면 안 된다** — Pod 재스케줄 시 색인이 전량 소실된다.
  - 프로브: `GET /healthz`. CI(`rag-tests.yml`)가 Qdrant 기동 대기에 이미 쓰는 경로다. 참고로 compose의 qdrant 서비스에는 `healthcheck`가 없어 `depends_on`이 "컨테이너 시작"만 보장하고 "Ready"는 보장하지 않는다 — K8s로 옮기면서 이 공백을 프로브로 메우는 것이 개선점이다.
  - **compose에서 qdrant는 `expose`만 하고 호스트 포트를 발행하지 않는다.** 즉 이미 "내부 전용"이 설계 의도이므로, K8s에서도 `ClusterIP`만 두고 Ingress에 노출하지 않는다. NetworkPolicy에서 `rag-api`/`rag-worker` → `qdrant` 흐름만 허용한다.
  - 앱 연결은 `QDRANT_URL=http://qdrant:6333`으로 서비스명을 그대로 쓰면 되므로 코드 변경이 없다.
  - **컬렉션 차원 불일치 방어와의 상호작용**: `vector_store.ensure_collection`은 기존 컬렉션 차원이 현재 임베딩 모델과 다르면 예외로 기동을 중단시킨다. PVC를 유지한 채 `EMBEDDING_MODEL`만 바꿔 롤아웃하면 **rag-server가 CrashLoopBackOff에 빠진다.** 임베딩 모델 변경은 반드시 컬렉션 재생성(또는 PVC 교체)과 함께 계획해야 한다.
  - prod 확장 경로: Qdrant 다중 노드(샤딩·복제) 또는 Qdrant Cloud로 외부화. 단일 노드 StatefulSet은 여전히 SPOF다.

### 4.5 관측 스택

- `kube-prometheus-stack`(Prometheus Operator) 설치 → `static_configs`를 **ServiceMonitor**로 대체:
  - backend: `path: /actuator/prometheus`, port `http`.
  - rag/llm(-api & -worker): `path: /metrics`.
- **cadvisor 제거**: kubelet 내장 cAdvisor + `node-exporter` + `kube-state-metrics`가 컨테이너/노드 지표를 대체. 호스트 마운트 해킹이 사라짐(운영 성숙 신호).
- **grafana**: 기존 `grafana/provisioning`·`dashboards/yeonam-overview.json`을 ConfigMap으로 이관해 as-code 유지. 어드민 PW는 Secret.
- **pushgateway**: 유지(배치 eval `eval_runner.py`가 push). `Deployment`+`Service`, ServiceMonitor로 스크레이프. eval은 `CronJob`으로 승격 가능(우대).

---

## 5. Config / Secret 분리

`env_file: .env` 단일 파일을 **ConfigMap(비밀 아님) + Secret(비밀)** 으로 분리한다. 이것도 필수 역량 항목("Secret 안전 관리").

**ConfigMap `app-config`** (비밀 아님):
`AI_SERVER_URL`, `BACKEND_URL`, `LLM_PROVIDER`, `LLM_MODEL`, `LLM_BASE_URL`, `AWS_REGION`, `AWS_BEDROCK_MODEL_ID`, `AWS_S3_ENDPOINT`, `AWS_S3_BUCKET_DOCUMENTS`, `AWS_S3_BUCKET_REPORTS`, `S3_ENDPOINT_URL`, `S3_BUCKET`, `MOCK_LLM`, `QDRANT_URL`, `QDRANT_COLLECTION`, `EMBEDDING_MODEL`, (트랙 A) `DB_URL`, `RUN_WORKER`.

> `MOCK_RAG`는 코드에서 완전히 제거되었으므로 ConfigMap에 넣지 않는다. `MOCK_LLM`의 기본값은 `false`이며, ConfigMap에서 누락되어도 mock으로 떨어지지 않는다(누락 시 실제 LLM을 호출한다).
> `EMBEDDING_MODEL` 변경은 Qdrant 컬렉션 차원과 직결된다(4.4 참고). ConfigMap만 바꿔 롤아웃하면 rag-server가 기동에 실패한다.

**Secret `app-secret`** (비밀):
`LLM_API_KEY`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_ACCESS_KEY`, `AWS_S3_SECRET_KEY`, `MINIO_ROOT_USER/PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, (트랙 A) `DB_PASSWORD`.

주입: `envFrom: [{configMapRef: app-config}, {secretRef: app-secret}]`.

**성숙도 단계**:
1. 로컬: `kustomize secretGenerator`(git에 커밋 금지, `.env` 파일 참조).
2. prod: **External Secrets Operator** → AWS Secrets Manager에서 동기화(base64는 암호화가 아님을 인지). CI는 GitHub Secrets에서 주입.

---

## 6. 이미지 · 레지스트리 · CI/CD

- compose `build:`는 K8s에서 못 쓴다 → **사전 빌드 이미지 필수**. GHCR 또는 ECR.
- 이미지: `backend`, `rag-server`, `llm-server`, `frontend` 4종. 태그 = `git SHA`(불변) + `latest`(편의). `imagePullPolicy: IfNotPresent`(SHA 태그이므로).
- rag/llm은 **동일 이미지**를 api/worker 두 Deployment가 command만 달리 사용.
- GitHub Actions 파이프라인:
  ```yaml
  jobs:
    build:
      - docker build → tag $SHA → push (backend/rag/llm/frontend 매트릭스)
    deploy:
      - kustomize edit set image ...=$REG/xxx:$SHA
      - kubectl apply -k k8s/overlays/prod
      - kubectl rollout status deploy/backend deploy/rag-api deploy/rag-worker --timeout=120s
  ```
- 로컬(minikube): `eval $(minikube docker-env)` 또는 `minikube image load`로 레지스트리 없이 검증.

---

## 7. 네트워킹 · 보안

- **Service DNS**: 앱 내부 URL(`AI_SERVER_URL=http://rag-server:8000`, `BACKEND_URL=http://backend:8080`)은 동일 네임스페이스면 **short name 그대로 해석**되어 코드 변경 불필요. 단, rag의 대상은 이제 `rag-api` 서비스명 → ConfigMap 값만 조정.
- **NetworkPolicy**(우대): default-deny 후 필요한 흐름만 허용(ingress→frontend/backend, backend→rag-api/llm-api/postgres/minio, worker→redis/backend/minio, **rag-api/rag-worker→qdrant**). 스크레이프는 monitoring ns 허용. qdrant는 Ingress에 노출하지 않는다 — compose에서도 `expose`만 하고 호스트 포트를 발행하지 않아, 내부 전용이 원래 설계 의도다.
- **securityContext**: 전 워크로드 `runAsNonRoot`, `allowPrivilegeEscalation:false`, `capabilities.drop:[ALL]`, 가능한 곳 `readOnlyRootFilesystem`.
- **ServiceAccount**: backend/rag/worker가 S3/Bedrock 접근 → EKS에선 **IRSA**(IAM Roles for Service Accounts)로 정적 키 제거(`.env`의 AWS_ACCESS_KEY 삭제) → Secret 표면적 축소.

---

## 8. 마이그레이션 시퀀스 (안전 순서)

각 단계는 **이전 단계가 compose에서 검증된 뒤** 진행. 되돌릴 수 있게 작게 쪼갠다.

1. **S0 코드 선행 리팩토링(compose에서 검증)**
   - rag/llm: `RUN_WORKER` 분기 + (트랙 A면) Redis 큐 어댑터 + `/ready` 프로브 + graceful drain.
   - backend: (트랙 A면) Postgres datasource + Flyway. compose에 postgres/redis 임시 추가해 동작 확인.
   - Dockerfile 비루트화, 프론트 빌드 산출물 이미지화.
2. **S1 클러스터/네임스페이스 + Config/Secret** 적용.
3. **S2 데이터 계층**(postgres/redis/minio/**qdrant** StatefulSet, minio 버킷 부트스트랩 Job) 배포·검증.
   - qdrant는 rag-server보다 **먼저** Ready여야 한다. rag-server가 기동 시 지식카드를 적재하며 실패하면 fail-fast로 죽기 때문이다.
   - 검증: `kubectl exec`로 `curl qdrant:6333/healthz` 200 확인 후 다음 단계 진행.
4. **S3 백엔드** 배포(프로브/PDB/리소스) → `/actuator/health/readiness` Green 확인, DB 마이그레이션 반영 확인.
5. **S4 rag/llm api+worker** 배포 → enqueue→worker 처리→backend 콜백 e2e 확인.
   - rag-server 기동 로그에서 `Startup ingestion complete: 184 knowledge documents` 확인.
   - `GET /health`가 `"qdrant":"up"`을 반환하는지 확인(503이면 Qdrant 연결 실패).
   - **메모리 실측**: `kubectl top pod`로 rag-worker RSS를 확인해 §4.2의 임시 리소스 값을 확정한다.
6. **S5 frontend + Ingress** 배포 → 브라우저 e2e(업로드 20M, 분석 트리거 120s) 확인.
7. **S6 관측**: kube-prometheus-stack, ServiceMonitor, Grafana 대시보드 이관, pushgateway eval.
8. **S7 HPA/부하 검증 → 롤링업데이트·롤백·self-healing 시나리오 검증**(§9).
9. **S8 prod overlay**: RDS/S3/ElastiCache 외부화 + ExternalSecrets + IRSA.

---

## 9. 검증 (필수 역량을 "증명"하는 시나리오)

면접에서 인용할 수 있도록 **관측 가능한 증거**를 남긴다.

| 역량 | 검증 방법 | 기대 결과/증거 |
|------|-----------|----------------|
| Self-healing | rag-worker Pod `kubectl delete pod` | 새 Pod 자동 생성, 큐 잡 유실 없음(트랙 A) 또는 drain 후 종료 |
| 무중단 롤링업데이트 | 새 이미지 롤아웃 중 `while true; curl /api/health` | 5xx 0건(backend `maxUnavailable:0`) |
| 롤백 | `kubectl rollout undo deploy/backend` | 직전 리비전 복귀, readiness 재Green |
| HPA 오토스케일 | `k6`/`wrk`로 rag-api 부하 | CPU>70%에서 replicas 2→N, p95 안정화, 부하 종료 후 scale-in |
| 잡 durability(B2) | 워커 처리 중 Pod kill | 트랙 A: 잡 재처리되어 backend 콜백 도착 / 트랙 B: drain 범위 내 완료 |
| 상태 지속성(B1/B3) | postgres·minio Pod 재스케줄 | PVC 재바인딩 후 데이터 보존 |
| 프로브 정확성 | DB 끊고 backend readiness 관찰 | readiness Fail → Service에서 자동 제외(트래픽 차단) |
| 관측성 | Grafana에서 Pod별 지표/큐 depth | rag `queue_size`·latency 대시보드 정상 |

부하 도구·시나리오는 `scripts/`에 추가(기존 `verify.sh`·`demo.sh` 패턴 재사용).

---

## 10. 면접 방어 포인트 (이 프로젝트에서 실제로 한 것)

- **"그냥 replicas 늘리면 되는 거 아닌가?"** → "backend가 H2 파일 DB라 두 Pod가 같은 파일을 경합해서 깨집니다. 그래서 PostgreSQL로 외부화해 backend를 진짜 stateless로 만든 뒤 replicas를 늘렸습니다. DB는 로컬에선 StatefulSet+PVC, prod에선 RDS로 overlay 분리했습니다."
- **"202 fire-and-forget인데 잡 유실은 어떻게 막았나?"** → "원래 asyncio.Queue 메모리 큐라 Pod가 죽으면 잡이 사라졌습니다. API와 워커를 같은 이미지의 다른 command로 분리하고 큐를 Redis로 외부화해서, API는 HPA로 안전하게 늘리고 워커는 큐 depth에 맞춰 독립 스케일하도록 했습니다. at-least-once로 재처리도 보장합니다."
- **"프로브를 어떻게 설계했나?"** → "backend는 Actuator health group으로 liveness/readiness를 분리하고 DB를 readiness에만 포함했습니다. FastAPI는 초기 로드가 느려서 startupProbe로 흡수하고, 항상 healthy만 주던 /health를 liveness로 쓰고 의존성 검증하는 /ready를 readiness로 신설했습니다."
- **"cadvisor는?"** → "compose에선 호스트를 마운트했는데 K8s에선 kubelet 내장 cAdvisor + node-exporter + kube-state-metrics로 대체하고, 스크레이프는 static_configs 대신 Prometheus Operator ServiceMonitor로 전환했습니다."
- **"Secret 관리는?"** → "env_file 단일 파일을 ConfigMap/Secret으로 분리하고, prod는 External Secrets로 AWS Secrets Manager와 동기화, S3/Bedrock 접근은 IRSA로 정적 키를 아예 제거했습니다."

---

## 11. 산출물 체크리스트

- [ ] S0: rag/llm `RUN_WORKER` 분기 + Redis 큐 어댑터 + `/ready` + graceful drain (compose 검증)
- [ ] S0: backend Postgres datasource + Flyway `V1__init.sql` (compose 검증)
- [ ] S0: 4개 Dockerfile 비루트화 + frontend 빌드 산출물 이미지화
- [ ] `k8s/base` + `overlays/local`,`overlays/prod` Kustomize 구성
- [ ] backend Deployment(or StatefulSet) + 프로브 3종 + PDB + HPA
- [ ] rag/llm api·worker 분리 Deployment + HPA + PDB
- [ ] postgres/redis/minio StatefulSet + PVC + minio 버킷 Job
- [ ] Ingress(20m/120s 어노테이션) + frontend Deployment
- [ ] ConfigMap/Secret 분리 + (prod) ExternalSecrets + IRSA
- [ ] kube-prometheus-stack + ServiceMonitor + Grafana 대시보드 이관 + pushgateway
- [ ] GitHub Actions: 매트릭스 빌드→푸시→`kubectl apply -k`→`rollout status`
- [ ] §9 검증 8종 실행 + 증거 캡처(Grafana/rollout 로그)

---

## 부록 A — 예시 매니페스트 스켈레톤 (rag-api / rag-worker)

```yaml
# k8s/base/rag/api-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: rag-api, labels: { app: yeonam, component: rag-api } }
spec:
  replicas: 2
  strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 1, maxUnavailable: 0 } }
  selector: { matchLabels: { app: yeonam, component: rag-api } }
  template:
    metadata: { labels: { app: yeonam, component: rag-api } }
    spec:
      securityContext: { runAsNonRoot: true, runAsUser: 1001, fsGroup: 1001 }
      containers:
        - name: rag-api
          image: ghcr.io/OWNER/yeonam-rag:GIT_SHA
          command: ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
          ports: [{ containerPort: 8000 }]
          envFrom:
            - configMapRef: { name: app-config }
            - secretRef:    { name: app-secret }
          env: [{ name: RUN_WORKER, value: "false" }]
          startupProbe:   { httpGet: { path: /health, port: 8000 }, failureThreshold: 30, periodSeconds: 5 }
          livenessProbe:  { httpGet: { path: /health, port: 8000 }, periodSeconds: 15 }
          readinessProbe: { httpGet: { path: /ready,  port: 8000 }, periodSeconds: 5 }
          resources: { requests: { cpu: 250m, memory: 512Mi }, limits: { cpu: "1", memory: 1Gi } }
          securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: [ALL] } }
          volumeMounts: [{ name: tmp, mountPath: /tmp }]
      volumes: [{ name: tmp, emptyDir: {} }]
---
# k8s/base/rag/worker-deployment.yaml (요지: command=worker, RUN_WORKER=true, readiness=큐 연결, HPA 대신 KEDA 후보)
#   preStop sleep + terminationGracePeriodSeconds:120 + shutdown drain 으로 in-flight 보호
```

## 부록 B — 리스크 / 결정 로그

| 결정 | 대안 | 선택 이유 |
|------|------|-----------|
| Postgres 외부화(트랙 A) | H2 StatefulSet 유지(트랙 B) | 수평확장·무중단배포 확보 + 면접 서사. B는 fallback |
| Redis 외부 큐(트랙 A) | 메모리 큐 + drain(트랙 B) | 잡 durability·API/워커 독립 스케일. B는 최소변경 fallback |
| Kustomize base/overlay | Helm chart | 앱 매니페스트는 Kustomize가 단순·투명. 관측은 Helm(kube-prometheus-stack) 병행 |
| kube-prometheus-stack | 기존 compose 관측 수동 이식 | Operator/ServiceMonitor로 운영 성숙도 신호, cadvisor 해킹 제거 |
| IRSA(prod) | 정적 AWS 키 Secret | 키 로테이션 부담·유출 표면 제거 |
```
