# 자기소개서 소재 — 연암 테스터 (Yeonam Tester)

> 현재 구현 기준 (2026-07-03)  
> 아래 내용은 실제 커밋 히스토리와 코드에 근거한 사실만 기술합니다.

---

## 현재 프로젝트 한 줄 요약

> "AI(LLM + RAG) 기반 테스트케이스 자동 생성 플랫폼을 설계부터 AWS 배포, CI/CD, 모니터링까지 직접 구축한 풀스택 DevOps 프로젝트"

---

## 필수 자격 요건별 소재

### 1. 온프레미스 → 클라우드 전환 / 하이브리드 운영 경험

**쓸 수 있는 사실:**
- 초기에 개발자 로컬 머신의 Docker Compose 환경에서만 동작하던 서비스를 AWS EC2(Ubuntu)에 배포
- 로컬 MinIO(S3 호환)로 개발하다가 EC2 배포 시 AWS Bedrock + S3 연동으로 전환
- NAT Gateway 배제 + 퍼블릭 서브넷 단일 EC2 + IAM Instance Profile 구조로 비용 최적화 설계

**자소서 문장 예시:**
> "처음에는 로컬 Docker Compose 환경에서 개발하다, AWS EC2에 직접 배포하면서 퍼블릭 서브넷 설계, IAM Role 기반 Bedrock 인증, S3 호환 스토리지(MinIO → AWS S3) 전환을 단계별로 경험했습니다. 특히 NAT Gateway를 배제하고 인터넷 게이트웨이를 통해 AWS API 엔드포인트와 직접 통신하는 구조로 네트워크 비용을 줄이는 설계 판단을 직접 내렸습니다."

---

### 2. 대용량 트래픽 환경에서 인프라 설계 / 운영 경험

**쓸 수 있는 사실:**
- AI 분석 요청은 처리 시간이 길어(수초~수십초) 동시 요청 시 메모리 과부하 발생
- `asyncio.Queue` 기반 비동기 큐로 요청을 순차 처리해 OOM 방지
- t2.micro 메모리 한계 대응을 위해 eval_runner에 케이스 간 5초 대기 로직 추가 (커밋 `2305620`)
- Prometheus + cAdvisor로 컨테이너별 CPU/메모리 사용량 실시간 모니터링

**자소서 문장 예시:**
> "AI 분석 요청이 몰릴 때 t2.micro 인스턴스에서 메모리 부족으로 컨테이너가 종료되는 문제를 겪었습니다. asyncio.Queue를 도입해 동시 처리 수를 제한하고, Prometheus와 cAdvisor로 컨테이너 리소스 사용량을 모니터링하면서 인스턴스 스펙과 큐 처리 간격을 조정해 안정적인 운영 환경을 구성했습니다."

---

### 3. 쿠버네티스 혹은 도커 기반 서비스 운영 경험

**쓸 수 있는 사실:**
- Docker Compose로 9개 컨테이너(nginx, backend, rag-server, llm-server, minio, prometheus, grafana, cadvisor, pushgateway) 오케스트레이션
- 각 서비스마다 별도 Dockerfile 작성 (Spring Boot multi-stage build, Python FastAPI)
- `restart: always` 정책으로 컨테이너 자동 재시작
- nginx 리버스 프록시로 단일 포트(80)에서 프론트엔드 정적 서빙 + 백엔드 API 라우팅 분리
- `docker image prune -f`로 배포 후 불필요 이미지 자동 정리

**자소서 문장 예시:**
> "React 프론트엔드, Spring Boot 백엔드, FastAPI AI 서버(RAG/LLM), MinIO 스토리지, Prometheus/Grafana 모니터링 스택을 Docker Compose로 통합 운영했습니다. nginx 리버스 프록시를 앞단에 두어 포트를 단일화하고, multi-stage Dockerfile로 이미지 크기를 줄이며, Spring Boot는 non-root 사용자로 실행하는 보안 설정까지 직접 구성했습니다."

---

### 4. CI/CD 구축 및 배포 자동화 경험

**쓸 수 있는 사실:**
- GitHub Actions 3개 워크플로우 구축:
  - `deploy-ec2.yml`: main 브랜치 push 시 프론트엔드 빌드 → SCP 전송 → EC2 SSH 배포 자동화
  - `deploy.yml`: GitHub Pages에 프론트엔드 정적 사이트 배포
  - `eval.yml`: EC2에서 LLM vs RAG 품질 평가 자동 실행 → Pushgateway → Grafana 시각화
- `npm ci` + Vite 빌드 → SCP 전송 → `docker compose up -d --build` 파이프라인
- `docker image prune -f`로 디스크 정리 자동화
- `workflow_dispatch`로 수동 배포 트리거도 지원

**자소서 문장 예시:**
> "main 브랜치에 코드가 머지되면 GitHub Actions가 프론트엔드를 빌드하고 SCP로 EC2에 전송한 뒤 Docker Compose로 백엔드/AI 서버를 재배포하는 파이프라인을 구축했습니다. 배포 외에도 LLM과 RAG 서버의 응답 품질을 자동으로 비교 평가하고 결과를 Prometheus Pushgateway를 통해 Grafana 대시보드에 시각화하는 평가 자동화 워크플로우도 별도로 운영했습니다."

---

## 우대 사항별 소재

### 대규모 트래픽 / 실시간 서비스 인프라 경험

**쓸 수 있는 사실:**
- 비동기 웹훅(Async Webhook) 아키텍처: AI 서버가 분석 완료 시 백엔드로 콜백 전송
- 프론트엔드 폴링 방식으로 실시간 분석 진행 상황 반영
- Spring Boot의 `asyncio.Queue` 비동기 처리로 동시 요청 버퍼링
- AI 서버 간 교체 가능한 플러그인 아키텍처 (llm-server ↔ rag-server 동일 포트 스왑)

**자소서 문장 예시:**
> "AI 분석처럼 처리 시간이 긴 작업을 동기 방식으로 처리하면 HTTP 타임아웃이 발생합니다. 이를 비동기 웹훅 아키텍처로 해결했습니다. 클라이언트는 202 Accepted를 즉시 받고, 분석 완료 시 AI 서버가 백엔드로 콜백을 전송하며, 프론트엔드는 폴링으로 상태를 확인합니다. 이 구조 덕분에 분석 시간이 얼마나 길어도 타임아웃 없이 안정적으로 처리할 수 있었습니다."

---

### 옵저버빌리티 구축 경험

**쓸 수 있는 사실:**
- Prometheus + Grafana + cAdvisor + Pushgateway 스택 직접 구성
- Spring Boot Actuator `/actuator/prometheus` 엔드포인트로 애플리케이션 메트릭 수집
- cAdvisor로 Docker 컨테이너별 CPU/메모리/네트워크 메트릭 수집
- Pushgateway로 일회성 배치 작업(eval_runner) 결과를 Prometheus에 push
- Grafana 대시보드 JSON 파일로 코드 관리 (`grafana/dashboards/yeonam-overview.json`)
- LLM vs RAG 응답 품질(임베딩 유사도) 지표를 커스텀 메트릭으로 시각화

**자소서 문장 예시:**
> "시스템 메트릭(cAdvisor), 애플리케이션 메트릭(Spring Boot Actuator), 배치 작업 결과(Pushgateway)를 Prometheus로 통합 수집하고 Grafana로 시각화하는 모니터링 스택을 직접 구성했습니다. 특히 LLM과 RAG 서버의 응답 품질을 임베딩 유사도로 정량화해 Grafana 대시보드에서 비교하는 커스텀 메트릭을 설계한 경험이 인상적이었습니다. 대시보드 설정은 JSON 파일로 코드화해 git으로 버전 관리했습니다."

---

## 멀티 리전 / 고가용성 / Terraform에 대한 솔직한 입장

현재 프로젝트에 **멀티 리전, 고가용성(ALB+ASG), Terraform**은 구현되어 있지 않습니다.

자소서에 쓸 수 있는 솔직한 문장:

> "현재는 단일 EC2 인스턴스 기반으로 운영 중이며, 고가용성 구성(ALB + Auto Scaling)과 인프라 코드화(Terraform)는 다음 단계로 계획하고 있습니다. 현재 아키텍처의 한계를 인지하고 있으며, 단일 장애점 제거와 IaC 전환 방향을 구체적으로 설계한 상태입니다."

또는 면접에서 이렇게 답변:
> "아직 구현하지 않았지만, 단일 EC2의 한계를 직접 경험하면서 ALB + Multi-AZ 구조가 왜 필요한지 체감했고, Terraform으로 현재 인프라를 코드화하는 계획을 수립해 두었습니다."

---

## 공통 자소서 도입부 (복붙용)

> 저는 AI 기반 테스트케이스 자동 생성 플랫폼 '연암 테스터'를 설계, 개발, 배포, 운영하는 전 과정을 직접 경험했습니다. React + Spring Boot + FastAPI(RAG/LLM) + MinIO로 구성된 멀티 서비스 아키텍처를 Docker Compose로 통합하고, GitHub Actions CI/CD로 AWS EC2에 자동 배포하며, Prometheus + Grafana로 실시간 모니터링하는 인프라를 혼자 처음부터 구축했습니다. 기능 개발보다 "어떻게 안정적으로 운영할 것인가"에 더 많은 고민을 투자했고, 그 과정에서 인프라 설계와 DevOps에 대한 실질적인 감각을 키웠습니다.

---

## 수치로 표현할 수 있는 것들

| 항목 | 수치 |
|------|------|
| 운영 컨테이너 수 | 9개 (nginx, backend, rag, llm, minio, prometheus, grafana, cadvisor, pushgateway) |
| CI/CD 파이프라인 수 | 3개 (EC2 배포, GitHub Pages, 품질 평가) |
| knowledge base 문서 수 | 7개 (ISTQB, OWASP, Playwright, Cypress, NIST, Atlassian, MS Playbook) |
| Prometheus 수집 job 수 | 5개 (prometheus, cadvisor, pushgateway, llm-server, rag-server, backend) |
| 지원하는 LLM 프로바이더 | 3개 (OpenAI, AWS Bedrock, LiteLLM 프록시) |
