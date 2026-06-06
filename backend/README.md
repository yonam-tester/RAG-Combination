# ☕ Yeonam Tester - Backend Service (Spring Boot)

연암 테스터(Yeonam Tester)의 백엔드 서비스 모듈입니다. 프론트엔드의 요청을 처리하고, 업로드된 문서 파일을 MinIO(S3 호환 스토리지)에 저장하며, 비동기 AI 분석 작업을 FastAPI 서버로 트리거하고, 분석 결과를 영속화 및 보고서(Markdown) 파일로 합성하여 제공하는 역할을 담당합니다.

---

## 🛠️ 기술 스택 (Tech Stack)

- **Framework**: Spring Boot 3.3.0
- **Language**: Java 17
- **Database**: H2 Database (File Mode, Embedded)
- **ORM**: Spring Data JPA / Hibernate
- **Object Storage**: MinIO (AWS Java SDK S3 연동)
- **Build Tool**: Apache Maven (Maven Wrapper 내장)

---

## 📂 주요 디렉토리 구조 (Directory Structure)

```text
backend/
├── .maven/                  # 내장 Maven Wrapper 바이너리 및 설정
├── data/                    # H2 RDB 파일 적재용 폴더 (yeonam_db.mv.db 등)
├── src/
│   ├── main/
│   │   ├── java/com/yeonam/tester/
│   │   │   ├── TesterApplication.java    # 백엔드 애플리케이션 진입점
│   │   │   ├── controller/               # REST API 컨트롤러
│   │   │   │   ├── AnalysisController.java
│   │   │   │   ├── ProjectController.java (S3 동기화 트리거 연동)
│   │   │   │   ├── ReportController.java
│   │   │   │   └── TestCaseController.java
│   │   │   ├── domain/                   # JPA Entity 모델 (AnalysisJob, Project, UploadedFile 등)
│   │   │   ├── dto/                      # API 요청/응답용 Data Transfer Object
│   │   │   ├── repository/               # Spring Data JPA Repository 인터페이스
│   │   │   └── service/                  # 비즈니스 로직 처리 레이어
│   │   │       ├── AnalysisService.java   # 비동기 AI 분석 트리거
│   │   │       ├── CallbackService.java   # FastAPI 웹훅 수신 및 결과 파싱
│   │   │       ├── FileService.java       # MinIO(S3) 파일 업로드 및 프로젝트 메타데이터 영속화
│   │   │       ├── ReportService.java     # 보고서 생성 및 매핑 관리
│   │   │       ├── S3SyncService.java     # (신규) S3 물리 데이터 기반 DB 복구 스캐너
│   │   │       └── TestCaseService.java   # 테스트케이스 관리 및 수정 로직
│   │   └── resources/
│   │       ├── application.yml           # 데이터베이스, S3, API 주소 등 환경 설정
│   │       └── schema.sql                # 테이블 정의 및 DDL 스키마 스크립트 (missing_items_text 칼럼 반영)
│   └── test/                             # JUnit 통합 테스트 및 기능 검증 테스트
```

---

## 🌟 최근 추가 개선 및 확장 스펙 (Phase 5)

다른 팀원이나 유지보수자가 참고할 수 있는 최근 백엔드 주요 변경 내역입니다:

1. **MinIO S3 메타데이터 기반 DB 자동 복구 및 동기화 (`S3SyncService`)**
   - 로컬 구동 시 DB 데이터가 휘발되었거나 비어있더라도, `yeonam-documents` 버킷을 스캔하여 S3 상에 잔존하던 프로젝트와 명세 파일들을 데이터베이스에 자동 복원합니다.
   - 복구 시 단순히 임시 명칭을 쓰는 것이 아니라, `FileService`에서 파일 업로드 시점에 `PutObject` 매개변수로 함께 실어 보낸 **프로젝트의 실제 상세 정보(이름, 설명, GitHub URL, 브랜치 등)**를 S3 객체의 User Metadata에서 읽고 URL 디코딩하여 온전히 보존시킵니다.
   - 대시보드 진입점인 `GET /api/projects` 엔드포인트 호출 시 동기화 메서드가 자동 트리거되도록 통합하였습니다.
2. **H2 DB 스키마 확장 및 기획 누락 텍스트 영속화**
   - RAG 분석을 통해 탐지된 명세서의 요구사항 누락 내용들을 영속적으로 기록하기 위해 `analysis_job` 테이블에 `missing_items_text VARCHAR(2000)` 컬럼을 확장 설계하였습니다.
   - Callback 수신 시 RAG 서버가 반환해 준 `missingItems` 리스트를 세미콜론 `;` 구분자로 조인하여 테이블에 정상 세이브하고, 결과 조회 시 이를 다시 파싱하여 프론트엔드로 전달합니다.
3. **보안 API Key 전달 및 비동기 실패 콜백 일원화**
   - 프론트엔드로부터 주입받은 `llmApiKey`를 유실 없이 FastAPI 서버로 전달하며, RAG 서버 분석 중 API Key 만료 등으로 발생한 비동기 예외를 전달받아 `FAILED` 상태로 변환하고 예외 원인 문구를 `summary` 필드에 안전하게 영속화합니다.

---

## 🚀 실행 및 빌드 가이드

### 사전 요구 사항
- 로컬 또는 Docker 환경에 **MinIO 스토리지**가 실행 중이어야 합니다. (루트의 `docker-compose.yml` 참고)
- Java JDK 17 이상이 설치되어 있어야 합니다.

### 1. 백엔드 기동
루트 폴더에서 `backend` 디렉토리로 이동한 뒤, 내장된 Maven Wrapper 명령어로 스프링 부트를 기동합니다.
```bash
cd backend

# Windows PowerShell 또는 cmd 공통
.maven\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run
```
- **API 서버 주소**: `http://localhost:8080`
- **H2 데이터베이스 콘솔**: `http://localhost:8080/h2-console`
  - *JDBC URL*: `jdbc:h2:file:./data/yeonam_db`
  - *사용자 ID*: `sa`
  - *비밀번호*: 없음 (공란)

### 2. 빌드 및 패키징
실 배포용 jar 파일로 빌드하기 위해 다음 명령을 실행합니다.
```bash
.maven\apache-maven-3.9.6\bin\mvn.cmd clean package
```
빌드가 완료되면 `target/tester-0.0.1-SNAPSHOT.jar` 파일이 생성됩니다.

---

## 🧪 자동화 테스트 검증 (JUnit 5)

새로 추가된 데이터 확장 아키텍처와 통합 보고서, 연쇄 파기 파이프라인의 기능 정합성을 검증하기 위해 통합 테스트 코드가 작성되어 있습니다.

터미널에서 아래 테스트 명령어로 검증이 가능합니다:

```bash
# 1. S3 데이터 및 메타데이터 기반 DB 완전 복구 통합 검증
.maven\apache-maven-3.9.6\bin\mvn.cmd test -Dtest=S3SyncTests

# 2. H2 테이블 확장 DDL 및 비동기 콜백 수집 로직 단위 검증
.maven\apache-maven-3.9.6\bin\mvn.cmd test -Dtest=AnalysisJobEntityTests
```

---

## 💡 개발 및 협업 팁 (Developer Notes)
- **로컬 H2 데이터베이스 초기화**: 스키마 구조 변경이나 테스트 데이터 꼬임 현상이 일어난 경우, 백엔드 서버를 종료하고 `backend/data/yeonam_db.mv.db` 파일을 과감히 삭제한 뒤 백엔드 서버를 재시작하면 `resources/schema.sql`에 설정된 DDL 스키마 스크립트를 기반으로 데이터베이스가 깨끗하게 재생성됩니다.
- **MinIO 스토리지 연동 문제**: 서버 부팅 시 백엔드가 MinIO 버킷(`yeonam-documents`, `yeonam-reports`)의 존재를 먼저 점검하고 자동 생성합니다. 만약 S3 연결 에러가 발생한다면 Docker Container 상의 MinIO(9000번 포트)가 정상적으로 바인딩되었는지 체크해 주세요.
