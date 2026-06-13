# RAG 파이프라인 추적 (Pipeline Trace) 설계 명세서

**날짜:** 2026-06-14  
**목적:** 프론트엔드에서 RAG가 실제로 동작했는지(Real vs Mock) 파이프라인 단계별로 확인할 수 있는 진단 기능 추가

---

## 1. 배경 및 목표

현재 `rag_server`는 `MOCK_RAG` / `MOCK_LLM` 환경 변수로 실제 RAG 파이프라인과 Mock 모드를 전환할 수 있으나, 프론트엔드 사용자는 분석 결과만 보고는 실제 RAG가 동작했는지 알 수 없다.

**목표:**
- 개발자: Mock/Real 모드를 프론트에서 즉시 확인하고 각 단계 실행 여부를 디버깅
- 최종 사용자: 테스트 케이스가 실제 문서 기반으로 생성됐는지 결과 화면에서 신뢰 확인

---

## 2. 아키텍처 및 데이터 흐름

```
[RAG Server] process_job()
  ├─ Step 1: 문서 파싱      → PipelineTraceStep {step: PARSE,    status, fileCount, durationMs}
  ├─ Step 2: 텍스트 청킹    → PipelineTraceStep {step: CHUNK,    status, chunkCount, durationMs}
  ├─ Step 3: FAISS 인덱싱   → PipelineTraceStep {step: INDEX,    status, vectorCount, durationMs}
  ├─ Step 4: 요구사항 추출  → PipelineTraceStep {step: EXTRACT,  status, reqCount, durationMs}
  └─ Step 5: 유사도 검색    → PipelineTraceStep {step: RETRIEVE, status, topScore, durationMs}
       ↓ webhook callback 바디에 pipelineTrace[] 추가
[Spring Boot Backend]
  └─ AnalysisJob 엔티티 pipeline_trace TEXT 컬럼 저장 (JSON 직렬화)
       ↓ GET /api/analysis/{id}/results 응답에 pipelineTrace[] 포함
[React Frontend]
  └─ AnalysisResultPage 상단 패널에 "RAG 진단" 버튼
       └─ 클릭 → 모달: 단계별 타임라인 + Mock/Real 배지
```

**Mock 모드 처리:** `MOCK_RAG=true` 시에도 trace를 생성하되 `status: "SKIPPED"`, `durationMs: 0`으로 기록한다. 단계 건너뜀이 UI에서 명확히 구분된다.

---

## 3. RAG 서버 변경 (`rag_server/main.py`)

### 3-1. PipelineTrace 수집

`process_job()` 함수 내부에서 각 단계 실행 전후 `time.time()`으로 경과 시간을 측정하고, 결과 메타데이터(파일 수, 청크 수 등)를 수집한다.

```python
pipeline_trace = []

# Mock 모드: 모든 단계 SKIPPED
if mock_rag:
    pipeline_trace = [
        {"step": "PARSE",    "status": "SKIPPED", "durationMs": 0},
        {"step": "CHUNK",    "status": "SKIPPED", "durationMs": 0},
        {"step": "INDEX",    "status": "SKIPPED", "durationMs": 0},
        {"step": "EXTRACT",  "status": "SKIPPED", "durationMs": 0},
        {"step": "RETRIEVE", "status": "SKIPPED", "durationMs": 0},
    ]
else:
    # 각 단계 실행 후 SUCCESS trace 추가
    # 예외 발생 시 FAILED + errorMessage 추가
    ...
```

### 3-2. 웹훅 콜백 바디에 포함

```python
formatted_data = {
    "analysisId": analysis_id,
    "status": "COMPLETED",
    "summary": "...",
    "testCases": test_cases,
    "errorMessage": None,
    "pipelineTrace": pipeline_trace   # 신규 추가
}
```

---

## 4. 백엔드 변경 (`backend/`)

### 4-1. DB 스키마

```sql
ALTER TABLE analysis_job ADD COLUMN pipeline_trace TEXT;
```

`schema.sql`에 컬럼 추가. 기존 레코드는 NULL 허용.

### 4-2. 웹훅 수신 DTO

`AnalysisCallbackRequest`에 `pipelineTrace` 필드 추가 (JSON 문자열로 역직렬화):

```java
@JsonProperty("pipelineTrace")
private List<Map<String, Object>> pipelineTrace;
```

수신 시 `ObjectMapper`로 JSON 문자열 변환 후 `AnalysisJob.pipelineTrace`에 저장.

### 4-3. 결과 조회 응답 DTO

`AnalysisResultResponse`에 `pipelineTrace` 필드 추가:

```java
private List<Map<String, Object>> pipelineTrace;
```

`GET /api/analysis/{id}/results` 응답에 포함.

---

## 5. 프론트엔드 변경 (`frontend/`)

### 5-1. 타입 추가 (`services/api.ts`)

```typescript
export interface PipelineTraceStep {
  step: 'PARSE' | 'CHUNK' | 'INDEX' | 'EXTRACT' | 'RETRIEVE';
  status: 'SUCCESS' | 'SKIPPED' | 'FAILED';
  durationMs: number;
  fileCount?: number;
  chunkCount?: number;
  vectorCount?: number;
  reqCount?: number;
  topScore?: number;
  errorMessage?: string;
}

// AnalysisResultResponse에 추가
export interface AnalysisResultResponse {
  analysisId: string;
  summary: string;
  testCases: TestCase[];
  missingItems: string[];
  pipelineTrace?: PipelineTraceStep[];   // 신규
}
```

### 5-2. "RAG 진단" 버튼

`AnalysisResultPage` 상단 "AI TDD 분석 검증 완료" 패널 우측에 배치:

```tsx
<button onClick={() => setIsTraceModalOpen(true)}>
  🔬 RAG 진단
</button>
```

`pipelineTrace`가 없거나 비어 있을 때 버튼을 비활성화(disabled).

### 5-3. RAG 진단 모달 (`RagTraceModal` 컴포넌트)

| 요소 | 내용 |
|------|------|
| 모드 배지 | 모든 step이 SKIPPED → `MOCK` (회색), 하나라도 SUCCESS → `REAL RAG` (초록) |
| 단계별 타임라인 | step명, status 아이콘, 메타데이터(파일 수 등), 소요 시간 |
| 색상 규칙 | SUCCESS: 초록 / SKIPPED: 회색 / FAILED: 빨강 + errorMessage |
| 위치 | `AnalysisResultPage` 내 상태 관리, 별도 파일 `RagTraceModal.tsx`로 분리 |

**단계별 표시 메타데이터:**
- PARSE: `fileCount`개 파일
- CHUNK: `chunkCount`개 청크
- INDEX: `vectorCount`개 벡터
- EXTRACT: `reqCount`개 요구사항
- RETRIEVE: 최고 유사도 `topScore * 100`%

---

## 6. 에러 처리

| 상황 | 처리 |
|------|------|
| RAG 서버가 trace를 미포함한 구버전 응답 | `pipelineTrace`가 null → "진단 정보 없음" 문구 표시, 버튼 비활성화 |
| 특정 단계 실패 (`FAILED`) | 해당 단계 빨간색 + `errorMessage` 표시, 이후 단계는 `SKIPPED`로 기록 |
| 웹훅 콜백 실패 | 기존 실패 콜백 흐름 유지, trace는 빈 배열로 전달 |

---

## 7. 변경 파일 목록

| 파일 | 변경 유형 |
|------|---------|
| `rag_server/main.py` | 수정 — `process_job()`에 trace 수집 및 콜백 포함 |
| `backend/src/.../AnalysisJob.java` | 수정 — `pipelineTrace` 컬럼 추가 |
| `backend/src/.../AnalysisCallbackRequest.java` | 수정 — `pipelineTrace` 필드 추가 |
| `backend/src/.../AnalysisResultResponse.java` | 수정 — `pipelineTrace` 필드 추가 |
| `backend/src/.../AnalysisService.java` | 수정 — 콜백 저장 및 결과 조회 로직 |
| `backend/src/main/resources/schema.sql` | 수정 — `pipeline_trace TEXT` 컬럼 추가 |
| `frontend/src/services/api.ts` | 수정 — `PipelineTraceStep` 타입 및 `AnalysisResultResponse` 확장 |
| `frontend/src/pages/AnalysisResultPage.tsx` | 수정 — "RAG 진단" 버튼 및 모달 연동 |
| `frontend/src/pages/RagTraceModal.tsx` | 신규 — RAG 진단 모달 컴포넌트 |

---

## 8. 범위 밖 (이번 구현 제외)

- RAG 진단 결과의 별도 히스토리 페이지
- 실시간 스트리밍 진단 (WebSocket)
- 개별 Evidence 청크의 원본 문서 뷰어
