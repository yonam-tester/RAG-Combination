# AWS EC2 장애 대응 및 트러블슈팅 가이드 (`troubleshooting.md`)

본 문서는 `yeonam_tester` 프로젝트를 AWS EC2 Ubuntu 환경에 Docker Compose로 배포한 후 발생할 수 있는 주요 인프라, 네트워크, 권한 장애 시나리오의 점검 스크립트와 트러블슈팅 대응 방법을 설명합니다.

---

## 7. 장애 확인 및 모니터링 쉘 명령어

서버의 작동 유무 및 병목 현상을 검진하기 위한 필수 CLI 명령어 리스트입니다.

```bash
# 1. 전체 컨테이너 구동 상태 및 포트 매핑 확인
docker compose ps

# 2. 특정 컨테이너 실시간 로그 추적 (예: backend, nginx, rag-server)
docker compose logs -f backend
docker compose logs -f nginx --tail 100

# 3. 컨테이너별 CPU, Memory, Network I/O 실시간 리소스 점검 (t3.micro 리소스 고갈 감지)
docker stats

# 4. 특정 서비스 컨테이너 내부 터미널 진입 및 디버깅 (ping, curl 등 테스트)
docker compose exec backend sh

# 5. 호스트 OS 레벨의 도커 데몬 시스템 이벤트 로그 검색
sudo journalctl -u docker -f -n 50

# 6. EC2 인스턴스 내부 디스크 용량 고갈 감지 (H2 DB 파일 및 도커 캐시 오버플로우)
df -h
docker system df
```

---

## 🚨 주요 장애 시나리오 및 해결 가이드

### 1. Nginx `502 Bad Gateway` 에러

#### 🔍 증상
브라우저로 접속 시 502 오류 화면이 렌더링되거나 API 요청 시 502 응답이 반환됩니다.

#### 🛠️ 원인 및 점검 리스트
1. **백엔드 컨테이너 웜업 지연 (JVM Warmup Delay):** Spring Boot 어플리케이션이 완전히 부팅되기 전(통상 10~20초 소요) Nginx 컨테이너가 먼저 켜져서 연결을 시도하는 타이밍 이슈입니다.
2. **도커 브릿지 네트워크 이름 매핑 실패:** `nginx.conf` 내부의 `proxy_pass http://backend:8080/` 도메인이 도커 내부 DNS를 통해 올바른 컨테이너 IP를 지목하지 못하는 상황입니다.

#### 💡 조치 단계
*   **단계 1:** 백엔드가 정상 부팅되었는지 로그로 체크합니다.
    ```bash
    docker compose logs backend | grep "Started TesterApplication"
    ```
    로그에 위 문구가 없다면 아직 부팅 중이거나 부팅 도중 예외로 인해 크래시가 났음을 의미합니다.
*   **단계 2:** Nginx와 Backend 컨테이너가 동일한 도커 브릿지 네트워크 상에 물려있는지 확인합니다.
    ```bash
    docker network inspect app_yeonam-network
    ```
*   **단계 3:** 백엔드가 뻗었을 경우 컨테이너만 부분 재시작을 수행합니다.
    ```bash
    docker compose restart backend
    ```

---

### 2. Bedrock API 호출 실패 (`AccessDeniedException`)

#### 🔍 증상
백엔드가 직접 Bedrock을 호출할 때(`llm.direct=true`), 혹은 RAG 서버가 LLM을 호출할 때 AWS 인증 에러가 발생하며 분석 상태가 `FAILED`로 전이됩니다.
`AccessDeniedException: User: arn:aws:sts:... is not authorized to perform: bedrock:InvokeModel on resource: arn:aws:bedrock:...`

#### 🛠️ 원인 및 점검 리스트
1. **EC2 인스턴스 프로파일 누락:** EC2 인스턴스 자체에 Bedrock 호출 권한이 있는 IAM Role이 부여되지 않았습니다.
2. **.env 내 수동 자격증명 잔재:** IAM Role을 사용하도록 세팅했으나, `.env` 내의 `AWS_ACCESS_KEY_ID`가 빈값(`""`)이 아니라 더미 키 값으로 채워져 있어 AWS SDK가 IAM Role 대신 잘못된 더미 자격증명 조각을 참조하려는 로직 분기 충돌입니다.

#### 💡 조치 단계
*   **단계 1:** `.env` 설정을 확인하여 IAM Role 작동 방식을 적용할 경우 키 값이 확실히 비워져 있는지 대조합니다.
    ```bash
    cat .env | grep AWS_
    # AWS_ACCESS_KEY_ID= 이 형태로 비어 있어야 함
    ```
*   **단계 2:** EC2 인스턴스 터미널 내에서 IAM Role이 실제 자격증명 토큰을 정상 호출하는지 메타데이터 서버(IMDS)를 통해 확인해 봅니다.
    ```bash
    curl http://169.254.169.254/latest/meta-data/iam/security-credentials/
    ```
    반환되는 Role 이름이 배포한 IAM Role과 다르면 인스턴스 자격 부착이 정상 수행되지 않은 것입니다.
*   **단계 3:** AWS 관리 콘솔로 이동하여 해당 IAM Role의 **신뢰 정책(Trust Relationship)**과 **권한 정책(Permissions)**에 아래 JSON 권한 정책이 연결되어 있는지 검토하고 매핑하십시오.
    ```json
    {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Action": [
                    "bedrock:InvokeModel",
                    "bedrock:InvokeModelWithResponseStream"
                ],
                "Resource": "*"
            }
        ]
    }
    ```

---

### 3. S3 / MinIO 버킷 연결 거부 (`SdkClientException` / `Connection Refused`)

#### 🔍 증상
문서 업로드 또는 다운로드 시 `Failed to connect to localhost:9000` 형태의 예외가 출력됩니다.

#### 🛠️ 원인 및 점검 리스트
1. **Endpoint 호스트명 지정 오류:** 도커 컨테이너 내부의 백엔드가 `localhost:9000`을 타겟으로 잡으면 컨테이너 자기 자신을 가리키게 되므로, 호스트 OS에 돌고 있는 MinIO 컨테이너를 찾지 못합니다.
2. **S3 버킷 미생성:** 지정한 버킷(`yeonam-documents`)이 스토리지 내에 사전 개설되어 있지 않은 상태입니다.

#### 💡 조치 단계
*   **단계 1:** 외부 AWS S3 버킷을 상용 배포용으로 사용할 경우, `.env` 내의 `AWS_S3_ENDPOINT`를 비워두거나 실제 S3 도메인으로 교체하고 `AWS_ACCESS_KEY_ID` 및 S3 액세스 권한이 부착된 IAM Role을 연동했는지 확인합니다.
*   **단계 2:** 만약 단일 EC2 내에 MinIO 컨테이너를 띄워 로컬로 흉내 내는 설정을 유지한다면, `.env`의 엔드포인트를 다음과 같이 도커 브릿지 네트워크용 호스트 별칭으로 교체해주어야 합니다.
    - **수정 전:** `AWS_S3_ENDPOINT=http://localhost:9000`
    - **수정 후:** `AWS_S3_ENDPOINT=http://yeonam-minio:9000` (minio 컨테이너명이 yeonam-minio일 때)

---

### 4. H2 데이터베이스 파일 잠금 (`Database may be already in use: locked`)

#### 🔍 증상
컨테이너 재배포 후 스프링 부트 로그에 `org.h2.jdbc.JdbcSQLNonTransientConnectionException: Database may be already in use` 락 에러가 나면서 실행에 실패합니다.

#### 🛠️ 원인 및 점검 리스트
1. **도커 프로세스 미정리:** 이전 백엔드 컨테이너가 정상적으로 종료(`down`)되지 않은 상태에서 새로운 컨테이너가 기동하여 데이터 폴더 내의 `.lock.db` 파일 소유권을 동시에 확보하려다가 충돌하는 상황입니다.

#### 💡 조치 단계
*   **단계 1:** 구동 중인 도커 컨테이너를 강제 정리하고 네트워크를 리셋합니다.
    ```bash
    docker compose down --remove-orphans
    ```
*   **단계 2:** 호스트 디렉토리에 마운트된 데이터 폴더 내에 `.lock.db` 락 플래그 파일이 지워지지 않고 방치되어 있다면 수동으로 안전 삭제합니다.
    ```bash
    rm -f ~/app/backend/data/yeonam_db.lock.db
    ```
*   **단계 3:** 도커 컴포즈로 깨끗하게 재시작을 트리거합니다.
    ```bash
    docker compose up -d
    ```
