# DevOps 포트폴리오 고도화 설계 — 연암 테스터 (Yeonam Tester)

> 작성일: 2026-07-02  
> 목표: 취업 자격 요건(필수 4개 + 우대 4개) 기반 단계적 인프라 고도화

---

## 현재 프로젝트 상태

| 항목 | 현황 | 평가 |
|------|------|------|
| Docker 기반 운영 | Docker Compose (9개 컨테이너) | ✅ 있음 |
| CI/CD | GitHub Actions → EC2 SSH 배포 | ✅ 있음 (기초) |
| 옵저버빌리티 | Prometheus + Grafana + cAdvisor | ⚠️ 기초 수준 |
| 클라우드 운영 | AWS EC2 단일 인스턴스 | ⚠️ 단순 배포 |
| Kubernetes | 없음 | ❌ |
| IaC (Terraform) | 없음 | ❌ |
| HA / 멀티 AZ | 없음 (단일 EC2) | ❌ |
| 분산 트레이싱 / 로그 수집 | 없음 | ❌ |

---

## Phase 구조 (단계적 고도화)

```
Phase 1: Terraform IaC          → 필수: 클라우드 전환 + 우대: IaC 자동화
Phase 2: Kubernetes 전환        → 필수: K8s 기반 서비스 운영
Phase 3: HA / 멀티 AZ          → 우대: 고가용성 아키텍처
Phase 4: 옵저버빌리티 고도화    → 우대: 옵저버빌리티 구축
```

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

**Q1. Terraform을 도입한 이유가 무엇인가요?**
> "초기에는 AWS 콘솔에서 수동으로 EC2, Security Group, IAM Role을 생성했는데, 팀원이 똑같은 환경을 다시 만들 때 설정 누락이 발생했습니다. Terraform으로 인프라를 코드화하면 `terraform apply` 한 번으로 동일한 환경을 재현할 수 있고, git으로 변경 이력도 추적할 수 있습니다. 연암 테스터 프로젝트에서는 VPC, EC2, IAM Role을 모듈로 분리해 dev/prod 환경을 변수만 바꿔 동일한 구조로 운영했습니다."

**Q2. Terraform State 관리를 어떻게 했나요?**
> "로컬 state 파일은 팀 작업 시 충돌 위험이 있어 S3 Remote Backend로 이전했습니다. DynamoDB를 state lock으로 설정해 두 사람이 동시에 `terraform apply`를 실행해도 한 명만 lock을 획득하도록 했습니다. CI/CD에서 자동 apply 시에도 이 lock 덕분에 race condition 없이 안전하게 배포됩니다."

**Q3. `terraform plan`과 `terraform apply`를 CI/CD에 어떻게 통합했나요?**
> "PR 생성 시에는 `terraform plan`만 실행해 결과를 PR 코멘트로 자동 게시하고, main 브랜치 머지 시에만 `terraform apply`가 실행되도록 설정했습니다. 이렇게 하면 인프라 변경 사항을 코드 리뷰와 동일한 흐름으로 검토할 수 있습니다."

**Q4. 기존에 수동 생성된 AWS 리소스를 Terraform으로 관리하려면 어떻게 하나요?**
> "`terraform import` 명령으로 기존 리소스의 ARN/ID를 state에 가져온 뒤, 해당 리소스의 HCL 코드를 작성하고 `terraform plan`으로 drift 없음을 확인합니다. 연암 테스터에서는 기존 EC2 인스턴스와 IAM Role을 이 방식으로 코드화했습니다."

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

### 핵심 K8s 오브젝트
- `Deployment` (replicas, rollingUpdate 전략, resource limits)
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
| 7 | ECR + kubectl CI/CD 연동 | GitHub Actions + EKS 가이드 |

### 면접 Q&A

**Q1. Docker Compose에서 Kubernetes로 전환한 이유는 무엇인가요?**
> "Docker Compose는 단일 호스트에서만 동작하기 때문에 서버가 하나 죽으면 전체 서비스가 중단됩니다. Kubernetes는 여러 노드에 Pod를 분산 배치하고, Pod가 죽으면 자동으로 재시작하는 Self-healing이 내장되어 있습니다. 연암 테스터에서는 RAG 서버가 메모리를 많이 사용해 OOM이 가끔 발생했는데, K8s로 전환 후 컨테이너 재시작이 자동화되어 운영 부담이 줄었습니다."

**Q2. Deployment의 rollingUpdate 전략을 어떻게 설정했나요?**
> "`maxSurge: 1, maxUnavailable: 0`으로 설정했습니다. 새 버전 Pod가 하나 먼저 뜨고 Ready 상태가 되면, 기존 Pod를 하나씩 제거하는 방식입니다. 이렇게 하면 배포 중에도 최소 기존 replicas 수만큼 항상 트래픽을 받을 수 있습니다."

**Q3. K8s에서 Secret을 어떻게 안전하게 관리했나요?**
> "초기에는 base64 인코딩 Secret을 git에 올렸는데, base64는 암호화가 아니므로 보안 문제가 있었습니다. 이후 External Secrets Operator를 도입해 AWS Secrets Manager에 실제 값을 저장하고, K8s Secret은 자동 동기화되도록 변경했습니다. CI/CD에서도 GitHub Secrets에서 값을 주입하는 방식을 사용합니다."

**Q4. HPA(HorizontalPodAutoscaler)를 설정한 기준은 무엇인가요?**
> "CPU utilization 70%를 기준으로 최소 2개, 최대 5개 replicas가 되도록 설정했습니다. RAG 서버는 임베딩 계산으로 CPU를 많이 써서 요청이 몰리면 응답이 느려지는 문제가 있었는데, HPA 도입 후 자동으로 스케일 아웃되어 p95 응답시간이 안정화됐습니다."

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

**Q1. 고가용성(HA)을 위해 어떤 설계를 적용했나요?**
> "단일 장애점(SPOF)을 제거하는 데 집중했습니다. 애플리케이션 레이어는 ALB + ASG로 최소 2개 AZ에 EC2를 분산 배치했고, 데이터 레이어는 RDS Multi-AZ로 Standby 복제본이 자동 페일오버되도록 했습니다. S3를 오브젝트 스토리지로 사용해 MinIO 단일 노드 의존성도 제거했습니다."

**Q2. 멀티 AZ 구성 시 데이터 정합성은 어떻게 유지했나요?**
> "RDS Multi-AZ는 동기 복제(Synchronous Replication)를 사용하므로 Primary에 쓴 데이터가 Standby에도 즉시 반영됩니다. 페일오버 시 DNS가 자동으로 Standby를 가리키기 때문에 애플리케이션 코드 변경 없이 전환됩니다. 애플리케이션 자체는 JPA 기반의 Stateless 설계라 어느 EC2 인스턴스로 요청이 들어와도 동일하게 처리됩니다."

**Q3. ALB Health Check는 어떻게 설정했나요?**
> "Spring Boot Actuator의 `/actuator/health` 엔드포인트를 ALB Health Check 경로로 설정했습니다. 인스턴스가 DB 연결 실패나 AI 서버 연결 불가 상태가 되면 health check가 실패해 ALB가 자동으로 해당 인스턴스를 Target Group에서 제외합니다."

**Q4. Auto Scaling 트리거 기준은 무엇으로 설정했나요?**
> "CPU 70% 임계값과 ALB RequestCount를 조합했습니다. CPU만 보면 메모리 부하에 반응이 늦고, 요청 수만 보면 처리 능력이 충분한데도 스케일 아웃될 수 있습니다. 두 지표 중 하나라도 임계값을 넘으면 스케일 아웃, 둘 다 낮아지면 쿨다운 후 스케일 인되도록 설정했습니다."

---

## Phase 4 — 옵저버빌리티 고도화

### 목표
기존 Prometheus + Grafana 스택을 기반으로 분산 트레이싱(Tempo), 로그 수집(Loki), 알람(Alertmanager), SLO 대시보드를 추가한다.

### 옵저버빌리티 3 Pillars 완성

| Pillar | 현재 | 추가 후 |
|--------|------|---------|
| Metrics | Prometheus + Grafana | + Alertmanager + SLO 패널 |
| Logs | 없음 | + Loki + Promtail |
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
> "모니터링은 '알고 있는 문제'를 감시하는 것입니다. CPU 90% 넘으면 알람 같은 식이죠. 옵저버빌리티는 '모르는 문제'를 시스템 외부에서 파악할 수 있는 능력입니다. 연암 테스터에서 단순 Prometheus 알람만으로는 'RAG 서버가 느린 이유'를 알 수 없었습니다. Tempo 분산 트레이싱을 추가한 후 FAISS 벡터 검색이 병목임을 TraceID로 추적해 확인할 수 있었습니다."

**Q2. SLO를 어떻게 설정했고, Error Budget은 어떻게 활용했나요?**
> "가용성 SLO는 99.5%, p95 응답시간 SLO는 2초 이하로 설정했습니다. Error Budget = 1 - SLO = 월 0.5%, 약 216분입니다. Grafana SLO 대시보드에서 남은 Error Budget을 실시간으로 보여주고, Budget 50% 소진 시 Slack 알람이 가도록 설정했습니다. Budget이 빠르게 줄어들면 새 기능 배포를 멈추고 안정성 개선에 집중하는 기준으로 사용합니다."

**Q3. Loki와 Elasticsearch 중 Loki를 선택한 이유는 무엇인가요?**
> "Elasticsearch는 로그를 인덱싱하므로 검색 성능은 우수하지만 운영 비용이 높습니다. Loki는 로그를 인덱싱하지 않고 레이블만 인덱싱하므로 저장 비용이 낮고, Grafana와 네이티브로 통합됩니다. 연암 테스터는 이미 Prometheus + Grafana 스택을 사용하고 있어 Loki를 추가하면 메트릭과 로그를 같은 Grafana 화면에서 연관 분석할 수 있다는 장점이 있었습니다."

**Q4. 분산 트레이싱을 통해 실제로 문제를 해결한 사례가 있나요?**
> "AI 분석 요청의 p95 응답시간이 갑자기 8초로 올라가는 현상이 발생했습니다. Prometheus만으로는 어느 서비스에서 지연이 발생하는지 알 수 없었습니다. Tempo에서 해당 TraceID를 조회하니 RAG 서버의 FAISS 벡터 검색 Span이 6초를 차지하고 있었습니다. knowledge_base 청크 수가 증가하면서 검색 비용이 늘어난 것으로, FAISS index를 IVF(Inverted File Index)로 교체해 검색 시간을 1초 이하로 줄였습니다."

---

## 전체 면접 대비 — 통합 질문

**Q. 온프레미스에서 클라우드로 전환한 경험을 설명해주세요.**
> "연암 테스터는 처음에 개발자 로컬 머신에서만 Docker Compose로 실행되는 구조였습니다. 이를 AWS EC2로 배포하는 과정에서 세 가지 단계를 거쳤습니다. 첫째, 수동 AWS 콘솔 배포에서 Terraform IaC로 인프라를 코드화했습니다. 둘째, 단일 EC2 Docker Compose에서 K8s로 전환해 컨테이너 오케스트레이션을 도입했습니다. 셋째, 단일 AZ에서 멀티 AZ ALB+ASG로 고가용성을 확보했습니다. 각 단계마다 이전 상태의 문제점을 인식하고 해결하는 방식으로 진행했습니다."

**Q. 대용량 트래픽 대응 경험을 설명해주세요.**
> "AI 분석 요청은 처리 시간이 길어 동시 요청이 몰리면 메모리 부족으로 OOM이 발생했습니다. 세 가지 방법으로 대응했습니다. 첫째, asyncio.Queue 기반 비동기 처리로 요청을 순서대로 처리해 메모리 급증을 방지했습니다. 둘째, HPA로 CPU 70% 초과 시 RAG 서버를 자동 스케일 아웃했습니다. 셋째, Prometheus로 queue depth와 처리 latency를 모니터링해 병목 지점을 실시간으로 파악했습니다."

---

## 실습 체크리스트

각 항목을 직접 구현했다면 면접에서 깊이 있는 답변이 가능합니다.

### Phase 1 체크리스트
- [ ] `terraform init` → `plan` → `apply`로 EC2 인스턴스 생성해본 경험
- [ ] S3 Remote Backend 설정 및 `terraform state list` 확인
- [ ] 기존 수동 리소스 `terraform import` 실습
- [ ] terraform.tfvars로 dev/prod 환경 분리
- [ ] GitHub Actions에서 `terraform plan` 결과를 PR 코멘트로 자동 게시

### Phase 2 체크리스트
- [ ] minikube 클러스터에 모든 서비스 Deployment 배포
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
- [ ] Grafana 대시보드 JSON 파일로 프로비저닝 자동화

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
