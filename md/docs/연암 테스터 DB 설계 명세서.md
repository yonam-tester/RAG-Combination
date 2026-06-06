# 🗄️ 연암 테스터 데이터베이스 설계 명세서

연암 테스터는 관계형 데이터베이스(H2 DB), 파일 저장소(S3/MinIO), 벡터 데이터베이스(초기 로컬 MVP용 FAISS 및 향후 클라우드 전환을 위한 AWS Bedrock Knowledge Base 모두 지원)로 구성된 **3중 저장소 아키텍처**를 사용합니다.

본 명세는 메타데이터와 트랜잭션을 기록하는 핵심 **관계형 데이터베이스(H2 DB)** 스키마를 중심으로 작성되었습니다.

---

## 1. Project (프로젝트 관리)

사용자가 등록한 프로젝트의 기본 메타데이터 및 GitHub 연동 정보를 저장합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `project_id` | VARCHAR(255) | PK | 프로젝트 고유 ID |
| `name` | VARCHAR(255) | NOT NULL | 프로젝트명 |
| `description` | TEXT | | 프로젝트 개요 및 주요 기능 |
| `github_url` | VARCHAR(255) | | GitHub 리포지토리 주소 |
| `github_branch` | VARCHAR(100) | | GitHub 기본 브랜치명 (예: `main`, `master`) |
| `integration_status` | VARCHAR(50) | | 외부 코드 저장소 연동 상태 |
| `created_at` | TIMESTAMP | NOT NULL | 프로젝트 생성 일시 |

## 2. UploadedFile (파일 메타데이터)

S3에 업로드된 문서 원본 파일의 위치 및 전처리 상태를 추적합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `file_id` | VARCHAR(255) | PK | 업로드 문서 ID (`documentId`) |
| `project_id` | VARCHAR(255) | FK | 소속 프로젝트 ID |
| `file_name` | VARCHAR(255) | NOT NULL | 원본 파일명 |
| `file_type` | VARCHAR(50) | NOT NULL | 문서 유형 (`REQUIREMENT_SPEC`, `REFERENCE`) |
| `s3_path` | VARCHAR(500) | NOT NULL | S3 물리적 경로 (로컬 인프라 구동 시 MinIO 호환 경로) |
| `status` | VARCHAR(50) | NOT NULL | 전처리 상태 (`NOT_STARTED`, `UPLOADED`, `PROCESSING` / `PARSING`, `DONE`, `FAILED`) |

## 3. AnalysisJob (분석 작업 파이프라인)

사용자가 선택한 QA 관점 및 전체 분석 작업의 진행 상태를 관리합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `analysis_id` | VARCHAR(255) | PK | 분석 작업 고유 ID (기능 명세의 `generationId`에 대응) |
| `project_id` | VARCHAR(255) | FK | 대상 프로젝트 ID |
| `qa_perspective` | VARCHAR(255) | | 사용자가 선택한 QA 관점 (보안, 프론트엔드 등) |
| `custom_prompt` | TEXT | | 사용자 맞춤형 지시사항 (프롬프트) |
| `summary` | TEXT | | 시스템이 생성한 전체 명세서 요약 |
| `status` | VARCHAR(50) | NOT NULL | 분석 처리 상태 (`WAITING`, `QUEUED`, `PROCESSING` / `GENERATING` / `PARSING`, `COMPLETED`, `FAILED`) |

## 4. Requirement (요구사항 추출 목록)

문서 전처리 과정에서 분리되고 식별된 개별 요구사항 원문을 저장합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `requirement_id` | VARCHAR(255) | PK | 요구사항 고유 ID (예: `REQ-001`) |
| `analysis_id` | VARCHAR(255) | FK | 소속 분석 작업 ID |
| `requirement_text` | TEXT | NOT NULL | 요구사항 원문 내용 |

## 5. TestCase (TDD 테스트 시나리오 초안)

LLM을 통해 파싱된 구조화된 테스트 케이스 시나리오의 상세 항목을 기록합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `test_case_id` | VARCHAR(255) | PK | 테스트 케이스 고유 ID (예: `TC-001`) |
| `analysis_id` | VARCHAR(255) | FK | 소속 분석 작업 ID |
| `requirement_id` | VARCHAR(255) | FK | 검증 대상 요구사항 ID |
| `test_case_name` | VARCHAR(255) | NOT NULL | 테스트 케이스 명칭 |
| `test_scenario` | TEXT | NOT NULL | 시나리오 설명 문장 |
| `precondition` | TEXT | | 테스트 사전조건 |
| `test_steps` | JSON | | 단계별 테스트 절차 (Array 형태) |
| `expected_result` | TEXT | NOT NULL | 기대 결과 |
| `priority` | VARCHAR(20) | NOT NULL | 우선순위 (`HIGH`, `MEDIUM`, `LOW`, `NEEDS_REVIEW`) |
| `confidence_level` | VARCHAR(20) | | 확신도 (`HIGH`, `MEDIUM`, `LOW`) |

## 6. RiskItem (위험 요소 태그)

테스트 케이스에 부착할 결함 유형 및 위험 요소를 해시태그 형태로 관리합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `risk_id` | VARCHAR(255) | PK | 위험 요소 태그 식별자 |
| `test_case_id` | VARCHAR(255) | FK | 소속 테스트 케이스 ID |
| `risk_type` | VARCHAR(100) | NOT NULL | 식별된 결함/위험 유형 (예: `인증 실패`) |

## 7. Evidence (다중 근거 매핑 데이터)

단일 테스트 케이스(1)에 요구사항 및 QA 지식베이스 근거 문서 조각(N)을 최대 3개까지 매핑합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `evidence_id` | VARCHAR(255) | PK | 근거 ID |
| `test_case_id` | VARCHAR(255) | FK | 매핑된 대상 테스트 케이스 ID |
| `chunk_id` | VARCHAR(255) | | Vector DB와 연동되는 문서 조각 ID |
| `evidence_text` | TEXT | NOT NULL | 추출된 근거 원문 텍스트 |
| `source_name` | VARCHAR(255) | NOT NULL | 원본 파일명 (출처) |
| `source_section` | VARCHAR(255) | | 근거 문서가 속한 상위 절(Section) 제목 |

## 8. Report (최종 검증 보고서)

S3에 저장되는 최종 산출물(Markdown 또는 PDF)의 물리적 경로와 메타데이터를 관리합니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | 설명 |
| :--- | :--- | :--- | :--- |
| `report_id` | VARCHAR(255) | PK | 보고서 ID (예: `RPT-001`) |
| `analysis_id` | VARCHAR(255) | FK | 기반이 된 분석 작업 ID |
| `file_id` | VARCHAR(255) | FK | 대상 업로드 문서 ID (기능 명세의 `documentId` 필터링 조회용, Nullable) |
| `s3_path` | VARCHAR(500) | NOT NULL | 생성된 보고서의 S3 물리적 경로 (로컬 인프라 구동 시 MinIO 호환 경로) |
| `format` | VARCHAR(20) | NOT NULL | 보고서 포맷 (`MARKDOWN`, `PDF`) |
| `created_at` | TIMESTAMP | NOT NULL | 보고서 생성 일시 |

---

### 💡 참고: 연쇄 파기 및 Vector DB 연동

* **연쇄 파기(Cascade Delete):** 문서 연쇄 삭제 및 완전 파기 요구사항을 위해 RDB의 모든 엔티티는 외래키(FK)로 연결되어 있으며, 보고서와 원본 문서 삭제 시 연동된 S3 파일과 Vector DB 데이터까지 일괄 폐기되도록 설계되었습니다.
* **Vector DB 메타데이터:** FAISS 및 AWS Bedrock Knowledge Base 등 외부 벡터 데이터베이스는 `chunkId`를 식별자로 사용하며, 원본 파일명(`sourceName`), 문서 절 제목(`sectionTitle`), 문서 조각 원문(`text`)을 메타데이터로 함께 포함해야 Evidence 엔티티와 무결성 연동이 가능합니다.
