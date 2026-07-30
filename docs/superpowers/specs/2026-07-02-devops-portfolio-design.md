# DevOps 포트폴리오 고도화 설계 — 연암 테스터 (Yeonam Tester)

> 작성일: 2026-07-02 (면접 Q&A 정리: 2026-07-30)
> 목표: 취업 자격 요건(필수 4개 + 우대 4개) 기반 단계적 인프라 고도화

---

## ⚠️ 이 문서를 읽는 방법

Phase 1~4는 **아직 구현되지 않은 계획**입니다. Terraform, Kubernetes, ALB/ASG, RDS, Tempo, Loki, Alertmanager는 모두 이 저장소에 존재하지 않습니다.

각 Phase의 「면접 Q&A」는 **지금 그대로 말할 수 있는 형태**로 작성되어 있습니다. 즉 개념은 설명하되, 구현하지 않은 것을 했다고 말하지 않습니다.

초기 버전의 Q&A는 모든 답변이 완료 과거 시제("~했습니다", "연암 테스터에서 ~ 운영했습니다")로 작성되어 있었습니다. 그대로 외워서 말하면 **하지 않은 일을 했다고 진술하게 되고**, 꼬리 질문 한 번에 무너집니다. 그래서 전면 수정했습니다.

**원칙: 구현한 것은 구체적으로, 구현하지 않은 것은 계획으로 말한다.** 미구현을 인정하는 답변은 감점이 아니라, 자기 시스템의 한계를 아는 사람이라는 신호입니다.

---

## 현재 프로젝트 상태

| 항목 | 현황 | 평가 |
|------|------|------|
| Docker 기반 운영 | Docker Compose (10개 컨테이너) | ✅ 있음 |
| CI/CD | GitHub Actions 4개 워크플로우 (EC2 배포 / rag_server 테스트 / 품질 평가 / GH Pages) | ✅ 있음 (배포 전 테스트 게이트 없음) |
| 벡터 DB | Qdrant + LangChain (문서 청크 + QA 지식카드 184건 단일 컬렉션) | ✅ 있음 |
| 테스트 자동화 | rag_server 54개 (단위 `:memory:` / 통합 실 Qdrant 분리, CI에서 skip 감지) | ✅ 있음 |
| 옵저버빌리티 | Prometheus + Grafana + cAdvisor + Pushgateway (수집 job 6개, 대시보드 16패널) | ⚠️ 메트릭만 |
| 클라우드 운영 | AWS EC2 단일 인스턴스 (t2.micro), IAM Instance Profile, S3 | ⚠️ 단순 배포 |
| 알럿 (Alertmanager) | 없음 — 지표를 사람이 직접 봐야 함 | ❌ |
| 중앙 로그 수집 | 없음 (`docker logs`만) | ❌ |
| 분산 트레이싱 | 없음 | ❌ |
| Kubernetes | 없음 | ❌ |
| IaC (Terraform) | 없음 | ❌ |
| HA / 멀티 AZ | 없음 (단일 EC2) | ❌ |
| 무중단 배포 / 롤백 | 없음 (배포 중 짧은 다운타임 발생) | ❌ |

---

## Phase 구조 (단계적 고도화)

```
Phase 1: Terraform IaC          → 필수: 클라우드 전환 + 우대: IaC 자동화
Phase 2: Kubernetes 전환        → 필수: K8s 기반 서비스 운영
Phase 3: HA / 멀티 AZ          → 우대: 고가용성 아키텍처
Phase 4: 옵저버빌리티 고도화    → 우대: 옵저버빌리티 구축
```

> 우선순위 제안: Phase 4의 **Alertmanager 알럿 룰**과 **배포 파이프라인 테스트 게이트**가 가장 적은 노력으로 가장 큰 설득력을 줍니다. 이미 Prometheus·테스트 54개가 있으므로 연결만 하면 됩니다. Terraform/K8s는 그 다음입니다.

---

## Phase 1 — Terraform IaC

### 목표
현재 AWS EC2 인프라(VPC, Subnet, Security Group, IAM Role, EC2)를 Terraform 코드로 선언하여 "인프라 = 코드" 상태를 만든다.

### 파일 구조
```
infra/terraform/
├── main.tf
├── variables.tf
├── outputs.tf
├── backend.tf       # S3 state backend + DynamoDB lock
└── modules/
    ├── vpc/         # VPC, Subnet, IGW, Route Table
    ├── ec2/         # Instance, Key Pair, IAM Instance Profile
    └── security/    # Security Group (80/443/22)
```

### 핵심 리소스
- `aws_vpc`, `aws_subnet` (퍼블릭/프라이빗 분리)
- `aws_security_group` (ingress: 80, 443, 22)
- `aws_iam_role` + `aws_iam_instance_profile` (Bedrock + S3 접근)
- `aws_s3_bucket` (terraform state), `aws_dynamodb_table` (state lock)

### GitHub Actions 추가
```yaml
- name: Terraform Plan
  run: terraform plan -var-file=prod.tfvars
- name: Terraform Apply
  run: terraform apply -auto-approve -var-file=prod.tfvars
```

### 학습 가이드

| 순서 | 학습 항목 | 참고 자료 |
|------|-----------|-----------|
| 1 | Terraform 기초 (`init`, `plan`, `apply`, `destroy`) | Terraform 공식 튜토리얼 |
| 2 | HCL 문법 (resource, variable, output, local, data) | Learn Terraform 핸즈온 |
| 3 | Remote State (S3 backend + DynamoDB locking) | Terraform Backend 문서 |
| 4 | AWS Provider (VPC, EC2, IAM, S3 리소스) | AWS Provider 레지스트리 |
| 5 | Terraform Modules (재사용 가능한 인프라 패턴) | Terraform Module 가이드 |
| 6 | `terraform import` (기존 수동 리소스 코드화) | terraform import 문서 |

### 면접 Q&A

**Q1. Terraform을 도입한 이유가 무엇인가요? / 도입한다면 왜인가요?**
> "현재 연암 테스터의 인프라는 AWS 콘솔에서 수동으로 만들었습니다. EC2, 보안 그룹, IAM Role을 직접 클릭해서 구성했고, 그 설정이 코드로 남아 있지 않습니다. 그래서 지금 인스턴스가 사라지면 제 기억에 의존해 다시 만들어야 하고, 어떤 값이 왜 그렇게 설정됐는지 이력을 추적할 수 없습니다. 이게 제가 인식한 문제입니다. Terraform으로 코드화하면 `terraform apply`로 동일한 환경을 재현할 수 있고 git으로 변경 이력이 남습니다. 아직 적용하지 못했고, 적용한다면 기존 리소스를 `terraform import`로 state에 가져오는 것부터 시작할 계획입니다."

**Q2. Terraform State는 어떻게 관리해야 한다고 보나요?**
> "로컬 state 파일은 팀 작업에서 충돌 위험이 있어 S3 Remote Backend와 DynamoDB state lock을 쓰는 것이 표준입니다. lock이 있으면 두 사람이 동시에 apply해도 한 명만 획득하므로 race condition이 생기지 않습니다. 다만 이건 개념으로 이해한 수준이고, 제가 직접 운영해 본 경험은 아닙니다."

**Q3. `terraform plan`과 `apply`를 CI/CD에 어떻게 통합하겠습니까?**
> "PR에서는 `plan`만 실행해 결과를 PR 코멘트로 게시하고, main 머지 시에만 `apply`가 돌게 해서 인프라 변경을 코드 리뷰와 같은 흐름으로 검토하는 방식이 일반적입니다. 현재 연암 테스터의 GitHub Actions는 애플리케이션 배포만 담당하고 인프라는 다루지 않습니다."

**Q4. 기존에 수동 생성된 AWS 리소스를 Terraform으로 관리하려면?**
> "`terraform import`로 기존 리소스의 ID를 state에 가져온 뒤 해당 HCL을 작성하고, `terraform plan`에서 변경 사항이 없음(no drift)을 확인하는 순서입니다. 연암 테스터의 EC2와 IAM Role이 정확히 이 대상인데, 아직 실습해 보지 못했습니다."

---

## Phase 2 — Kubernetes 전환

### 목표
Docker Compose 기반 로컬/EC2 배포를 Kubernetes Manifest로 전환한다. 로컬은 minikube, 프로덕션은 EKS(또는 기존 EC2 kubeadm)를 사용한다.

### 파일 구조
```
k8s/
├── namespace.yaml
├── configmap.yaml
├── secrets/
│   └── app-secrets.yaml   # base64 인코딩 또는 External Secrets
├── deployments/
│   ├── backend.yaml       # replicas: 2
│   ├── rag-server.yaml
│   ├── llm-server.yaml
│   └── minio.yaml
├── services/
│   ├── backend-svc.yaml   # ClusterIP
│   └── rag-server-svc.yaml
├── ingress.yaml            # nginx-ingress
└── monitoring/
    ├── prometheus.yaml
    └── grafana.yaml
```

> Qdrant는 상태를 가지므로 Deployment가 아니라 StatefulSet + PVC로 배치해야 합니다. 위 구조에는 아직 반영되지 않았습니다.

### 핵심 K8s 오브젝트
- `Deployment` (replicas, rollingUpdate 전략, resource limits)
- `StatefulSet` + `PersistentVolumeClaim` (Qdrant 등 상태 보유 컴포넌트)
- `Service` (ClusterIP 내부 통신, LoadBalancer 외부 노출)
- `Ingress` (nginx-ingress, 경로 기반 라우팅)
- `ConfigMap` (환경변수), `Secret` (API 키, DB 패스워드)
- `HorizontalPodAutoscaler` (CPU 70% 기준 자동 스케일)

### GitHub Actions 추가
```yaml
- name: Build & push to ECR
  run: |
    docker build -t $ECR_REGISTRY/yeonam-backend:$SHA .
    docker push $ECR_REGISTRY/yeonam-backend:$SHA
- name: Deploy to K8s
  run: kubectl apply -f k8s/
```

### 학습 가이드

| 순서 | 학습 항목 | 참고 자료 |
|------|-----------|-----------|
| 1 | K8s 핵심 개념 (Pod, Deployment, Service, Ingress) | Kubernetes 공식 문서 Concepts |
| 2 | kubectl 기본 명령어 (apply, get, describe, logs, exec) | kubectl 치트시트 |
| 3 | minikube 로컬 클러스터 구축 | minikube 시작 가이드 |
| 4 | ConfigMap / Secret 관리 | K8s Secret 문서 |
| 5 | HorizontalPodAutoscaler (HPA) | K8s HPA 문서 |
| 6 | nginx-ingress + TLS 설정 | ingress-nginx 문서 |
| 7 | StatefulSet + PVC (Qdrant 영속성) | K8s StatefulSet 문서 |
| 8 | ECR + kubectl CI/CD 연동 | GitHub Actions + EKS 가이드 |

### 면접 Q&A

**Q1. Docker Compose와 Kubernetes의 차이는? 왜 전환이 필요하다고 보나요?**
> "Docker Compose는 단일 호스트 전용이라 그 호스트가 죽으면 전체 서비스가 중단됩니다. 연암 테스터가 정확히 이 상태입니다. t2.micro 한 대에 10개 컨테이너가 올라가 있어서 인스턴스 장애가 곧 전체 장애이고, 트래픽이 늘면 수동으로 스케일업해야 합니다. Kubernetes는 여러 노드에 Pod를 분산하고 죽은 Pod를 자동 재시작하는 self-healing이 내장돼 있어 이 문제를 구조적으로 해결합니다. 다만 K8s는 아직 도입하지 않았고, 설계 문서만 작성해 둔 상태입니다."

**Q2. Deployment의 rollingUpdate 전략은 어떻게 설정하는 것이 좋습니까?**
> "`maxSurge: 1, maxUnavailable: 0`이면 새 Pod가 먼저 Ready가 된 뒤 기존 Pod를 하나씩 제거하므로 배포 중에도 가용 replica 수가 유지됩니다. 현재 연암 테스터는 `docker compose up -d --build`로 컨테이너를 교체해서 배포 중 짧은 다운타임이 발생합니다. 이 부분을 무중단으로 개선하는 것이 목표입니다."

**Q3. K8s에서 Secret을 안전하게 관리하는 방법은?**
> "base64 인코딩된 Secret을 git에 올리면 안 됩니다. base64는 인코딩이지 암호화가 아니기 때문입니다. AWS Secrets Manager에 실제 값을 두고 External Secrets Operator로 K8s Secret을 동기화하는 방식이 권장됩니다. 현재 연암 테스터는 K8s를 쓰지 않고, 민감 값은 GitHub Actions Secrets와 EC2의 `.env` 파일로 관리합니다. `.env`는 git에 올라가지 않도록 `.gitignore`에 넣었지만, 인스턴스에 평문으로 존재하는 건 한계입니다."

**Q4. HPA는 어떤 기준으로 설정하겠습니까?**
> "연암 테스터에서 스케일 기준으로 삼을 지표는 CPU가 맞다고 봅니다. rag-server가 임베딩 계산을 로컬 CPU에서 수행하기 때문입니다. CPU 70% 기준으로 최소 2, 최대 5 replicas 정도가 출발점일 것 같습니다. 다만 지금은 HPA가 없고, 대신 `asyncio.Queue`에 워커를 1개만 둬서 분석 작업을 직렬로 처리하는 방식으로 메모리 초과를 막고 있습니다. 스케일 아웃 대신 스케일을 강제로 1로 묶은 셈입니다."

---

## Phase 3 — HA / 멀티 AZ 아키텍처

### 목표
단일 EC2에서 ALB + Auto Scaling Group + 멀티 AZ 구성으로 전환. H2 → RDS PostgreSQL(Multi-AZ), MinIO → S3로 교체하여 스토리지 영속성 확보.

### 아키텍처 변경
```
Before: EC2 (단일 인스턴스, ap-northeast-2a)
         └── Docker Compose (모든 서비스 한 호스트)

After:  ALB (Multi-AZ)
        ├── ap-northeast-2a: EC2 / EKS Node
        └── ap-northeast-2c: EC2 / EKS Node
                    │
            RDS PostgreSQL (Multi-AZ Standby)
            S3 (데이터 영속성, 99.999999999% 내구성)
```

### Terraform 추가 모듈
```
infra/terraform/modules/
├── alb/
│   ├── main.tf    # aws_lb, aws_lb_listener, aws_lb_target_group
│   └── ...
├── asg/
│   ├── main.tf    # aws_autoscaling_group, aws_launch_template
│   └── ...
└── rds/
    ├── main.tf    # aws_db_instance (multi_az = true)
    └── ...
```

### Spring Boot 변경
- `application.yml`: H2 → PostgreSQL datasource 전환
- `pom.xml`: `postgresql` driver 의존성 추가

### 학습 가이드

| 순서 | 학습 항목 | 참고 자료 |
|------|-----------|-----------|
| 1 | AWS VPC 설계 (퍼블릭/프라이빗 서브넷, AZ 분산) | AWS VPC 문서 |
| 2 | ALB vs NLB vs CLB 차이 및 Target Group 설정 | AWS ELB 문서 |
| 3 | Auto Scaling Group + Launch Template | AWS ASG 문서 |
| 4 | RDS Multi-AZ vs Read Replica 차이 | AWS RDS 문서 |
| 5 | Health Check 설계 (ALB → Application Readiness) | ALB Health Check 문서 |
| 6 | 세션 스티키니스 vs Stateless 아키텍처 | AWS 아키텍처 블로그 |

### 면접 Q&A

**Q1. 고가용성(HA)을 위해 어떤 설계가 필요하다고 보나요?**
> "핵심은 단일 장애점 제거입니다. 애플리케이션 레이어는 ALB + ASG로 2개 이상 AZ에 분산하고, 데이터 레이어는 RDS Multi-AZ로 Standby 자동 페일오버를 두는 구성이 표준입니다. 연암 테스터는 아직 이 구조가 아닙니다. 단일 AZ의 EC2 한 대에 애플리케이션과 H2 파일 DB, Qdrant가 모두 올라가 있어서 SPOF가 여러 겹으로 존재합니다. 이걸 알고 있고, 개선 순서는 데이터 레이어 분리(RDS) → 애플리케이션 다중화 → ALB 순으로 잡고 있습니다."

**Q2. 멀티 AZ 구성에서 데이터 정합성은 어떻게 유지됩니까?**
> "RDS Multi-AZ는 동기 복제라 Primary에 커밋된 데이터가 Standby에 즉시 반영되고, 페일오버 시 엔드포인트 DNS가 Standby를 가리키므로 애플리케이션 코드 변경이 필요 없습니다. 다중화의 전제 조건은 애플리케이션이 stateless여야 한다는 점인데, 연암 테스터 백엔드는 JPA 기반이고 세션에 상태를 두지 않아 그 조건은 충족합니다. 다만 현재 DB가 로컬 H2 파일이라 인스턴스를 늘리면 각 인스턴스가 서로 다른 DB를 보게 되어, 다중화 전에 DB 외부화가 반드시 선행돼야 합니다."

**Q3. ALB Health Check는 어떻게 설정하겠습니까?**
> "Spring Boot Actuator의 `/actuator/health`를 Health Check 경로로 쓰면 됩니다. 이 엔드포인트는 이미 노출해 둔 상태입니다. 인스턴스가 비정상이면 ALB가 Target Group에서 자동 제외합니다. 참고로 rag-server에도 `/health`를 두고 Qdrant 연결이 끊기면 503을 반환하도록 구현했는데, 지금은 이 신호를 받아 트래픽을 빼줄 로드밸런서가 없어서 신호만 있고 활용은 못 하는 상태입니다."

**Q4. Auto Scaling 트리거 기준은?**
> "CPU 단일 지표만 보면 메모리 부하에 늦게 반응하고, 요청 수만 보면 여유가 있어도 불필요하게 스케일 아웃될 수 있어 두 지표를 조합하는 편이 안전합니다. 연암 테스터는 오토스케일링이 없어 트래픽이 늘면 수동 스케일업해야 하고, 이건 8장 한계로 정리해 뒀습니다."

---

## Phase 4 — 옵저버빌리티 고도화

### 목표
기존 Prometheus + Grafana 스택을 기반으로 분산 트레이싱(Tempo), 로그 수집(Loki), 알람(Alertmanager), SLO 대시보드를 추가한다.

### 옵저버빌리티 3 Pillars 완성

| Pillar | 현재 | 추가 후 |
|--------|------|---------|
| Metrics | Prometheus + Grafana + cAdvisor + Pushgateway (job 6개, 16패널) | + Alertmanager + SLO 패널 |
| Logs | 없음 (`docker logs`만) | + Loki + Promtail |
| Traces | 없음 | + OpenTelemetry SDK + Tempo |

### 파일 구조
```
observability/
├── otel-collector/
│   └── otel-config.yaml      # OTLP 수신 → Prometheus/Tempo/Loki 라우팅
├── loki/
│   └── loki-config.yaml
├── tempo/
│   └── tempo-config.yaml
├── alertmanager/
│   ├── alertmanager.yaml     # Slack/Email 알람 채널 설정
│   └── alerts.yaml           # SLO 기반 알람 룰 (error rate > 1%, p95 > 2s)
└── grafana/dashboards/
    ├── slo-dashboard.json
    └── traces-dashboard.json
```

### 핵심 설정

**Prometheus Alerting Rule 예시 (alerts.yaml):**
```yaml
groups:
  - name: yeonam-slo
    rules:
      - alert: HighErrorRate
        expr: rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m]) > 0.01
        for: 2m
        annotations:
          summary: "에러율 1% 초과"
      - alert: SlowResponse
        expr: histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m])) > 2
        for: 5m
        annotations:
          summary: "p95 응답시간 2초 초과"
```

> 위 예시는 표준 메트릭명(`http_requests_total`)을 쓰고 있습니다. 실제 적용 시에는 현재 노출 중인 메트릭명(`rag_request_duration_seconds`, `llm_request_duration_seconds`)으로 바꿔야 동작합니다.

**OpenTelemetry 연동 (Spring Boot):**
```xml
<!-- pom.xml 추가 -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
```

```yaml
# application.yml 추가
otel:
  exporter:
    otlp:
      endpoint: http://otel-collector:4317
  service:
    name: yeonam-backend
```

### 학습 가이드

| 순서 | 학습 항목 | 참고 자료 |
|------|-----------|-----------|
| 1 | 옵저버빌리티 3 Pillars (Metrics / Logs / Traces) | OpenTelemetry 공식 문서 |
| 2 | Prometheus PromQL (rate, histogram_quantile, irate) | PromQL 치트시트 |
| 3 | Alertmanager 설정 (route, receiver, inhibit_rules) | Alertmanager 문서 |
| 4 | SLI / SLO / SLA 개념과 Error Budget | Google SRE Book (무료) |
| 5 | Loki + Promtail 로그 수집 파이프라인 | Grafana Loki 문서 |
| 6 | OpenTelemetry SDK 자동 계측 (Java, Python) | OTel Java 에이전트 문서 |
| 7 | Distributed Tracing 개념 (Span, TraceID, Context Propagation) | Jaeger / Tempo 문서 |
| 8 | Grafana 대시보드 as Code (JSON 프로비저닝) | Grafana Provisioning 문서 |

### 면접 Q&A

**Q1. 옵저버빌리티와 모니터링의 차이를 설명해주세요.**
> "모니터링은 '알고 있는 문제'를 감시하는 것입니다. CPU 90% 초과 시 알람 같은 식이죠. 옵저버빌리티는 시스템이 내보내는 신호만으로 '예상하지 못한 문제'까지 파악할 수 있는 능력입니다. 이 기준으로 보면 연암 테스터는 옵저버빌리티가 아니라 모니터링 단계입니다. Prometheus로 메트릭은 수집하지만 로그 중앙 수집과 분산 트레이싱이 없고, 알럿도 없어서 제가 대시보드를 직접 봐야 이상을 압니다. 다만 단계별 소요 시간을 응답에 담는 `pipelineTrace`를 직접 구현해서, 분석 요청이 파싱·청킹·색인·검색·생성 중 어디서 오래 걸렸는지는 추적할 수 있습니다. 정식 트레이싱의 아주 축소된 형태라고 생각합니다."

**Q2. SLO와 Error Budget 개념을 설명해주세요.**
> "SLO는 목표 서비스 수준이고, Error Budget은 `1 - SLO`로 허용되는 실패량입니다. 가용성 SLO가 99.5%면 월 약 216분의 예산이 있고, 이 예산이 빠르게 소진되면 신규 기능 배포를 멈추고 안정화에 집중하는 판단 기준으로 씁니다. 연암 테스터에는 SLO를 정의하지 않았습니다. 개인 프로젝트이고 트래픽이 없어 의미 있는 목표치를 정하기 어려웠는데, 정한다면 먼저 필요한 건 SLO 수치가 아니라 위반을 감지할 Alertmanager라고 생각합니다."

**Q3. Loki와 Elasticsearch 중 무엇을 택하겠습니까?**
> "Elasticsearch는 로그 본문을 인덱싱해 검색 성능이 좋지만 운영 비용이 큽니다. Loki는 레이블만 인덱싱해 저장 비용이 낮고 Grafana에 네이티브로 통합됩니다. 연암 테스터는 이미 Prometheus + Grafana를 쓰고 있고 t2.micro 한 대에서 돌기 때문에, 리소스 관점에서 Loki가 맞습니다. 현재는 둘 다 없고 `docker logs`로 확인하고 있습니다."

**Q4. 분산 트레이싱으로 문제를 해결한 경험이 있나요?**
> "분산 트레이싱은 도입하지 않았으니 그 경험은 없습니다. 대신 트레이싱 없이도 단계별 지연을 볼 수 있게 파이프라인 각 단계(PARSE/CHUNK/INDEX/EXTRACT/RETRIEVE)의 소요 시간을 측정해 응답에 `pipelineTrace`로 담았고, Prometheus Histogram으로 p50/p95를 Grafana에서 확인했습니다. 구조상 가장 비싼 구간은 LLM 호출입니다. 추출된 요구사항마다 LLM을 한 번씩 순차 호출하기 때문에 전체 시간이 요구사항 수에 비례해 늘어납니다. 개선하려면 호출을 병렬화하거나 배치로 묶어야 하는데, t2.micro 메모리 제약 때문에 지금은 의도적으로 직렬을 유지하고 있습니다."

---

## 전체 면접 대비 — 통합 질문

**Q. 로컬 환경에서 클라우드로 전환한 경험을 설명해주세요.**
> "연암 테스터는 처음에 제 로컬 머신의 Docker Compose에서만 동작했습니다. 이를 AWS EC2 Ubuntu에 배포하면서 퍼블릭 서브넷, 보안 그룹, IAM Instance Profile 기반 인증, S3 연동, Bedrock 호출 구조를 구성했습니다. 특히 NAT Gateway를 두지 않고 퍼블릭 서브넷 EC2가 인터넷 게이트웨이로 AWS API와 통신하게 설계해 고정 비용을 줄였습니다. 배포는 GitHub Actions에서 프론트엔드를 빌드해 SCP로 전송하고 SSH로 컨테이너를 재기동하는 방식으로 자동화했습니다. 다만 여기까지가 전부입니다. Terraform, Kubernetes, 멀티 AZ는 아직 적용하지 않았고, 단일 EC2 구조를 직접 운영하면서 SPOF와 수동 스케일업, 배포 중 다운타임 같은 한계를 확인한 단계입니다."

**Q. 대용량 트래픽 대응 경험을 설명해주세요.**
> "솔직히 말씀드리면 대용량 트래픽을 받아 본 경험은 없습니다. 개인 프로젝트이고 t2.micro 한 대에서 운영했습니다. 다만 자원이 극도로 제한된 환경에서 요청이 서버를 죽이는 문제는 겪었고, 그걸 해결했습니다. AI 분석 요청이 동시에 들어오면 메모리 사용량이 급증해 컨테이너가 불안정해졌습니다. 세 가지로 대응했습니다. 첫째, `asyncio.Queue`에 워커를 1개만 둬서 분석 작업을 직렬 처리해 동시 실행을 원천 차단했습니다. 둘째, 긴 작업을 202 Accepted + 웹훅 콜백 구조로 분리해 HTTP 타임아웃을 없앴습니다. 셋째, cAdvisor로 컨테이너별 메모리를 관찰하며 평가 실행 간격(케이스 간 5초 대기)을 조정했습니다. 스케일 아웃이 아니라 스케일을 1로 묶어서 버틴 것이고, 트래픽이 실제로 늘어난다면 큐를 외부 브로커로 빼고 워커를 수평 확장하는 방향이 맞다고 생각합니다."

**Q. 이 프로젝트에서 가장 아쉬운 점은 무엇인가요?**
> "배포 파이프라인이 테스트를 통과 조건으로 삼지 않는 점입니다. rag_server에 테스트 54개를 작성하고 CI에서 실제 Qdrant를 띄워 통합 검증까지 하는데, 그 워크플로우가 배포의 전제 조건이 아니라 별도로 돕니다. 즉 테스트가 깨진 상태로도 main에 푸시하면 배포가 됩니다. 테스트를 공들여 만들어 놓고 정작 배포를 막는 데 쓰지 않은 게 가장 아쉽고, 가장 먼저 고칠 부분이라고 생각합니다."

---

## 실습 체크리스트

각 항목을 **직접 구현했을 때만** 면접에서 해당 경험을 주장할 수 있습니다. 체크되지 않은 항목은 "개념은 알지만 해보지 않았다"로 답하는 것이 맞습니다.

### Phase 1 체크리스트
- [ ] `terraform init` → `plan` → `apply`로 EC2 인스턴스 생성해본 경험
- [ ] S3 Remote Backend 설정 및 `terraform state list` 확인
- [ ] 기존 수동 리소스 `terraform import` 실습
- [ ] terraform.tfvars로 dev/prod 환경 분리
- [ ] GitHub Actions에서 `terraform plan` 결과를 PR 코멘트로 자동 게시

### Phase 2 체크리스트
- [ ] minikube 클러스터에 모든 서비스 Deployment 배포
- [ ] Qdrant를 StatefulSet + PVC로 배치하고 Pod 재시작 후 데이터 유지 확인
- [ ] `kubectl rollout undo` 롤백 실습
- [ ] HPA 설정 후 부하 테스트로 자동 스케일 확인 (k6 또는 wrk 사용)
- [ ] nginx-ingress로 경로 기반 라우팅 설정
- [ ] ECR에 이미지 push 후 K8s에서 pull 배포

### Phase 3 체크리스트
- [ ] Terraform으로 ALB + Target Group + ASG 생성
- [ ] RDS Multi-AZ 인스턴스 생성 및 Spring Boot 연결
- [ ] ALB Health Check 실패 시 인스턴스 자동 교체 확인
- [ ] ASG 스케일 아웃/인 동작 직접 확인 (CPU 부하 주기)

### Phase 4 체크리스트
- [ ] Prometheus Alertmanager 알람 룰 작성 및 Slack 연동
- [ ] Loki + Promtail로 컨테이너 로그 수집 및 Grafana에서 조회
- [ ] OpenTelemetry SDK 적용 후 Tempo에서 TraceID 조회
- [ ] Grafana SLO 대시보드 구성 (error rate, p95 latency 패널)
- [x] Grafana 대시보드 JSON 파일로 프로비저닝 자동화 — **구현 완료** (`grafana/dashboards/yeonam-overview.json`, 16패널)

### 이미 구현해 둔 것 (면접에서 주장 가능)
- [x] Docker Compose 10개 컨테이너 운영, multi-stage 빌드 + non-root 실행 (Spring Boot)
- [x] GitHub Actions 4개 워크플로우 (EC2 배포 / 테스트 / 품질 평가 / GH Pages)
- [x] CI에서 서비스 컨테이너로 실제 Qdrant를 띄워 통합 테스트, skip 감지 시 워크플로우 실패 처리
- [x] Prometheus 6 job + 커스텀 Counter/Histogram + Pushgateway로 배치 메트릭 수집
- [x] LangChain + Qdrant RAG 파이프라인 (멱등 upsert, payload 인덱스 부분 삭제, 차원 불일치 fail-fast)
- [x] 202 Accepted + 인메모리 큐 + 웹훅 콜백(지수 백오프 3회) + 프론트 폴링 비동기 구조
- [x] IAM Instance Profile 기반 AWS 인증, NAT Gateway 배제 비용 최적화

---

## 참고 자료 모음

| 카테고리 | 자료 |
|----------|------|
| Terraform | [registry.terraform.io](https://registry.terraform.io), HashiCorp Learn |
| Kubernetes | [kubernetes.io/docs](https://kubernetes.io/docs), CKAD 시험 커리큘럼 |
| AWS 아키텍처 | AWS Well-Architected Framework (Reliability Pillar) |
| 옵저버빌리티 | Google SRE Book (무료 공개), OpenTelemetry.io |
| PromQL | Prometheus 공식 쿼리 문서, PromQL 치트시트 |
| 면접 준비 | CNCF Landscape, AWS Solutions Architect 문제집 |
