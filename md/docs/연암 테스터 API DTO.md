# 📌 연암 테스터 REST API Request/Response DTO 명세서

## 🗂️ 1. 프로젝트 및 파일 관리 API (Domain 1)

### 1-1. `[POST] /api/projects` (프로젝트 생성)

**Request (ProjectCreateRequest)**

```json
{
  "projectName": "연암 테스터 캡스톤",
  "description": "RAG 기반 QA/TDD 검증 보조 플랫폼",
  "githubUrl": "https://github.com/example/yeonam-tester"
}
```

**Response (ProjectResponse)**

```json
{
  "projectId": "PRJ-2026-0001",
  "projectName": "연암 테스터 캡스톤",
  "description": "RAG 기반 QA/TDD 검증 보조 플랫폼",
  "githubUrl": "https://github.com/example/yeonam-tester",
  "createdAt": "2026-05-31T10:00:00Z"
}
```

### 1-2. `[POST] /api/projects/{projectId}/files` (문서 업로드 및 검증)

**Request (Multipart/form-data)**

* `file`: (Binary File)
* `fileType`: "REQUIREMENT_SPEC" | "REFERENCE"

**Response (FileResponse)**
상태(status)는 UPLOADED, PROCESSING, DONE, FAILED 중 하나로 반환됩니다.

```json
{
  "documentId": "DOC-001",
  "fileName": "요구사항_명세서_v1.0.pdf",
  "fileType": "REQUIREMENT_SPEC",
  "status": "UPLOADED",
  "fileSizeByte": 2048576,
  "uploadedAt": "2026-05-31T10:05:00Z"
}
```

### 1-3. `[GET] /api/files/{fileId}/status` (문서 전처리 상태 추적)

**Response (FileStatusResponse)**

```json
{
  "documentId": "DOC-001",
  "status": "PROCESSING",
  "processingStep": "CHUNKING",
  "message": "문서 분할 및 벡터 임베딩을 진행 중입니다."
}
```

---

## ⚙️ 2. 분석 파이프라인 제어 API (Domain 2 & 3)

### 2-1. `[GET] /api/projects/{projectId}/qa-recommendations` (QA 관점 동적 추천)

**Response (QaRecommendationResponse)**

```json
{
  "recommendedPerspectives": ["SECURITY", "BACKEND"],
  "reason": "업로드된 문서 내 '결제', '비밀번호', '인증' 키워드가 포함되어 보안 관점 검증이 우선적으로 필요합니다."
}
```

### 2-2. `[POST] /api/projects/{projectId}/analysis` (분석 작업 생성 및 RAG 실행)

**Request (AnalysisCreateRequest)**

```json
{
  "targetDocumentIds": ["DOC-001", "DOC-002"],
  "qaPerspectives": ["SECURITY", "FRONTEND"],
  "customPrompt": "예외 상황 발생 시의 에러 메시지 노출 여부를 집중적으로 테스트해 줘."
}
```

**Response (AnalysisJobResponse)**

```json
{
  "analysisId": "ANL-2026-0001",
  "projectId": "PRJ-2026-0001",
  "status": "WAITING",
  "createdAt": "2026-05-31T10:15:00Z"
}
```

### 2-3. `[GET] /api/analysis/{analysisId}/status` (분석 진행 상태 모니터링)

**Response (AnalysisStatusResponse)**

```json
{
  "analysisId": "ANL-2026-0001",
  "status": "PROCESSING",
  "message": "LLM 기반 테스트 케이스 시나리오 생성 중...",
  "progressPercentage": 75
}
```

### 2-4. `[GET] /api/analysis/{analysisId}/results` (분석 결과 시각화 조회)

**Response (AnalysisResultResponse)**
데이터 사전 및 테스트 케이스 상세 예시에 정의된 필드(`testCaseId`, `testCaseName`, `testScenario`, `precondition`, `testSteps`, `expectedResult`, `priority`, `confidenceLevel`, `riskTags(위험 요소 태그 배열)`, `evidenceId`, `evidenceText`, `sourceName`, `sourceSection`)를 모두 포함합니다.

```json
{
  "analysisId": "ANL-2026-0001",
  "summary": "본 명세서는 사용자 인증 및 결제 기능을 중심으로 하며...",
  "testCases": [
    {
      "testCaseId": "TC-001",
      "testCaseName": "잘못된 비밀번호 입력 시 오류 메시지 표시 검증",
      "testScenario": "사용자가 잘못된 비밀번호를 입력했을 때 로그인 실패 메시지가 표시되는지 확인한다.",
      "precondition": "등록된 사용자 계정이 존재해야 한다.",
      "testSteps": [
        "1. 로그인 화면에 접속한다.",
        "2. 올바른 아이디를 입력한다.",
        "3. 잘못된 비밀번호를 입력한다.",
        "4. 로그인 버튼을 클릭한다."
      ],
      "expectedResult": "로그인에 실패하고 사용자에게 오류 메시지가 표시된다.",
      "priority": "HIGH",
      "riskTags": ["인증_실패", "보안_경고"],
      "relatedRequirements": ["REQ-001"],
      "evidences": [
        {
          "evidenceId": "EV-001",
          "evidenceText": "잘못된 인증 정보 입력 시 오류 메시지를 표시해야 한다.",
          "sourceName": "요구사항_명세서.md",
          "sourceSection": "3.2 로그인 기능",
          "confidenceLevel": "HIGH"
        }
      ]
    }
  ],
  "missingItems": [
    "로그아웃 이후의 세션 만료 처리에 대한 명세가 누락되어 보완이 필요합니다."
  ]
}
```

### 2-5. `[POST] /api/internal/analysis/{analysisId}/callback` (RAG 서버 완료 콜백용 내부 API)

**Request (AnalysisCallbackRequest)**

* 외부 RAG 서버가 위 `AnalysisResultResponse` 포맷과 동일하게 결과를 반환(POST)하여 H2 DB에 기록되도록 유도합니다.

---

## 📄 3. 검증 보고서 산출 및 반출 API (Domain 4)

### 3-1. `[POST] /api/analysis/{analysisId}/reports` (보고서 산출 요청)

**Request (ReportCreateRequest)**

```json
{
  "reportFormat": "MARKDOWN" 
}
```

**Response (ReportResponse)**

```json
{
  "reportId": "RPT-001",
  "analysisId": "ANL-2026-0001",
  "reportFormat": "MARKDOWN",
  "status": "DONE",
  "downloadUrl": "/api/reports/RPT-001/download",
  "generatedAt": "2026-05-31T10:30:00Z"
}
```

### 3-2. `[GET] /api/projects/{projectId}/reports` (생성 이력 다중 필터링)

**Response (ReportListResponse)**

```json
{
  "reports": [
    {
      "reportId": "RPT-001",
      "analysisId": "ANL-2026-0001",
      "reportFormat": "MARKDOWN",
      "status": "DONE",
      "generatedAt": "2026-05-31T10:30:00Z"
    }
  ]
}
```

### 3-3. `[GET] /api/reports/{reportId}` (보고서 미리보기 단건 조회)

**Response (ReportPreviewResponse)**
한계 고지 및 주의사항 템플릿이 자동으로 포함되어 응답합니다.

```json
{
  "reportId": "RPT-001",
  "reportFormat": "MARKDOWN",
  "content": "# QA 검증 보고서\n\n## 1. 분석 대상 개요\n본 문서는...\n\n## 2. 테스트 케이스 생성 결과 요약...",
  "disclaimer": "본 시스템은 테스트 수행 결과를 보장하거나 최종 판단을 대체하지 않으며, QA 담당자의 검토를 전제로 합니다.",
  "generatedAt": "2026-05-31T10:30:00Z"
}
```
