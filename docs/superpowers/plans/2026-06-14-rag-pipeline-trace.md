# RAG Pipeline Trace 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RAG 파이프라인의 각 단계(파싱→청킹→인덱싱→요구사항 추출→유사도 검색)가 실제로 실행됐는지를 프론트엔드 "RAG 진단" 모달에서 확인할 수 있도록, RAG 서버→백엔드→프론트엔드로 trace 데이터를 흘려보낸다.

**Architecture:** RAG 서버의 `process_job()`이 각 단계 실행 시간과 메타데이터를 `pipeline_trace` 리스트로 수집해 웹훅 콜백에 포함시킨다. Spring 백엔드는 이를 `analysis_job.pipeline_trace TEXT` 컬럼에 JSON 문자열로 저장하고, 결과 조회 응답에 역직렬화해서 포함시킨다. 프론트엔드는 기존 폴링 응답에서 trace를 받아 모달로 표시한다.

**Tech Stack:** Python/FastAPI (rag_server), Java 17/Spring Boot 3/Jackson (backend), React 18/TypeScript (frontend)

---

## 파일 목록

| 파일 | 변경 유형 |
|------|---------|
| `rag_server/main.py` | 수정 |
| `backend/src/main/resources/schema.sql` | 수정 |
| `backend/src/main/java/com/yeonam/tester/domain/AnalysisJob.java` | 수정 |
| `backend/src/main/java/com/yeonam/tester/dto/AnalysisCallbackRequest.java` | 수정 |
| `backend/src/main/java/com/yeonam/tester/service/CallbackService.java` | 수정 |
| `backend/src/main/java/com/yeonam/tester/dto/AnalysisResultResponse.java` | 수정 |
| `backend/src/main/java/com/yeonam/tester/service/AnalysisService.java` | 수정 |
| `backend/src/test/java/com/yeonam/tester/PipelineTraceTests.java` | 신규 |
| `frontend/src/services/api.ts` | 수정 |
| `frontend/src/pages/RagTraceModal.tsx` | 신규 |
| `frontend/src/pages/AnalysisResultPage.tsx` | 수정 |

---

### Task 1: RAG 서버 — `process_job()`에 pipeline trace 수집 추가

**Files:**
- Modify: `rag_server/main.py`

`process_job()` 함수가 각 단계의 실행 여부, 소요 시간, 메타데이터를 `pipeline_trace` 리스트로 수집해 웹훅 콜백 payload에 포함시킨다.

- [ ] **Step 1: `main.py` 상단에 `time` import 추가**

`rag_server/main.py` 파일 상단 import 블록을 찾아서 `import time`을 추가한다.

```python
import time
```

- [ ] **Step 2: `process_job()` 함수를 pipeline trace 수집 버전으로 교체**

기존 `process_job()` 함수 전체를 아래 코드로 교체한다. 핵심 변경: 각 단계 앞뒤로 `time.time()` 측정 + `pipeline_trace` 리스트 구축, Mock 모드에서는 모든 단계 `SKIPPED` 기록, `formatted_data`에 `pipelineTrace` 키 추가.

```python
async def process_job(job_data: dict):
    analysis_id = job_data.get("analysisId")
    project_id = job_data.get("projectId")
    s3_paths = job_data.get("s3Paths", [])
    perspectives = job_data.get("qaPerspectives", [])
    custom_prompt = job_data.get("customPrompt", "")
    llm_api_key = job_data.get("llmApiKey")

    logger.info(f"Worker processing RAG job {analysis_id}")

    mock_rag = os.getenv("MOCK_RAG", "true").lower() == "true"
    mock_llm = os.getenv("MOCK_LLM", "true").lower() == "true"

    pipeline_trace = []

    try:
        requirements = []
        chunks = []

        if not mock_rag:
            # Step 1: PARSE
            t0 = time.time()
            logger.info("Downloading and parsing documents from S3...")
            parsed_documents = await process_and_extract(s3_paths)
            pipeline_trace.append({
                "step": "PARSE",
                "status": "SUCCESS",
                "fileCount": len(parsed_documents),
                "durationMs": int((time.time() - t0) * 1000)
            })

            # Step 2: CHUNK
            t0 = time.time()
            logger.info("Cleaning and chunking parsed text...")
            for doc in parsed_documents:
                doc_chunks = chunk_document(doc["text"], doc["file_id"], doc["file_name"])
                chunks.extend(doc_chunks)
            pipeline_trace.append({
                "step": "CHUNK",
                "status": "SUCCESS",
                "chunkCount": len(chunks),
                "durationMs": int((time.time() - t0) * 1000)
            })

            # Step 3: INDEX
            t0 = time.time()
            logger.info(f"Indexing {len(chunks)} chunks to vector database...")
            vector_db_manager.add_chunks(chunks)
            pipeline_trace.append({
                "step": "INDEX",
                "status": "SUCCESS",
                "vectorCount": len(chunks),
                "durationMs": int((time.time() - t0) * 1000)
            })

            # Step 4: EXTRACT
            t0 = time.time()
            logger.info("Extracting requirements from document text...")
            requirements = await extract_requirements(parsed_documents, llm_api_key)
            pipeline_trace.append({
                "step": "EXTRACT",
                "status": "SUCCESS",
                "reqCount": len(requirements),
                "durationMs": int((time.time() - t0) * 1000)
            })
        else:
            logger.info("MOCK_RAG is enabled, skipping S3 download, parsing, chunking, and indexing.")
            pipeline_trace = [
                {"step": "PARSE",    "status": "SKIPPED", "durationMs": 0},
                {"step": "CHUNK",    "status": "SKIPPED", "durationMs": 0},
                {"step": "INDEX",    "status": "SKIPPED", "durationMs": 0},
                {"step": "EXTRACT",  "status": "SKIPPED", "durationMs": 0},
                {"step": "RETRIEVE", "status": "SKIPPED", "durationMs": 0},
            ]
            requirements = await extract_requirements([], llm_api_key)

        # Step 5: RETRIEVE (only in real mode — mock already added RETRIEVE above)
        test_cases = []
        top_score = 0.0
        elapsed_retrieve = 0  # default if requirements list is empty

        for req in requirements:
            req_id = req["id"]
            req_text = req["text"]

            # Step 5: RETRIEVE timing (real mode only; in mock mode already SKIPPED above)
            t0 = time.time()
            evidences = retrieve_evidences(req_text)
            elapsed_retrieve = int((time.time() - t0) * 1000)

            if not mock_rag:
                for ev in evidences:
                    sc = ev.get("score", 0.0)
                    if sc and sc > top_score:
                        top_score = sc

            limited_evidences = evidences[:3]

            prompt = build_prompt(req_text, limited_evidences, custom_prompt, perspectives)

            logger.info(f"Calling LLM for requirement {req_id}...")
            raw_test_cases = await call_llm_with_key(prompt, llm_api_key)

            for tc in raw_test_cases:
                tc["requirementId"] = req_id
                tc["requirementText"] = req_text
                tc["evidence_list"] = limited_evidences

                if "testSteps" in tc:
                    if isinstance(tc["testSteps"], str):
                        tc["testSteps"] = [s.strip() for s in tc["testSteps"].split("\n") if s.strip()]
                    elif not isinstance(tc["testSteps"], list):
                        tc["testSteps"] = []
                else:
                    tc["testSteps"] = []

                test_cases.append(tc)

        # Add RETRIEVE trace step for real mode (after loop so top_score is finalized)
        if not mock_rag:
            pipeline_trace.append({
                "step": "RETRIEVE",
                "status": "SUCCESS",
                "topScore": round(top_score, 4),
                "durationMs": elapsed_retrieve
            })

        formatted_data = {
            "analysisId": analysis_id,
            "status": "COMPLETED",
            "summary": f"RAG 기반 분석 완료. 추출된 요구사항 수: {len(requirements)}, 생성된 테스트 케이스 수: {len(test_cases)}.",
            "testCases": test_cases,
            "errorMessage": None,
            "pipelineTrace": pipeline_trace
        }

        success = await send_callback(analysis_id, formatted_data)
        if not success:
            logger.error(f"Failed to send RAG webhook callback for job {analysis_id}")

    except Exception as e:
        logger.error(f"Error while executing RAG worker for job {analysis_id}: {str(e)}", exc_info=True)
        try:
            error_msg = f"RAG 서버 분석 중 예외 발생: {str(e)}"
            if "AuthenticationError" in type(e).__name__ or "api key" in str(e).lower() or "api_key" in str(e).lower():
                error_msg = "API 키 유효성 검증 실패: 유효하지 않은 API 키이거나 만료되었습니다. 키 설정을 재점검해 주세요."
            await send_failure_callback(analysis_id, error_msg)
        except Exception as err:
            logger.error(f"Failed to send failure callback for job {analysis_id}: {str(err)}")
```

- [ ] **Step 3: Mock 모드로 빠른 동작 확인**

터미널에서 아래 명령으로 RAG 서버를 기동한다:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/rag_server
source venv/bin/activate
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

새 터미널에서 cURL로 분석을 트리거하고 `pipelineTrace`가 웹훅 payload에 포함되어 로그에 찍히는지 확인한다:
```bash
curl -X POST "http://localhost:8000/api/analysis/trigger" \
  -H "Content-Type: application/json" \
  -d '{"analysisId":"TRACE-TEST-001","projectId":"PRJ-TEST","s3Paths":[],"llmApiKey":"sk-fake"}'
```

RAG 서버 로그에서 아래와 같은 라인이 보여야 한다:
```
Worker processing RAG job TRACE-TEST-001
MOCK_RAG is enabled, skipping S3 download, parsing, chunking, and indexing.
```

(백엔드가 없으므로 웹훅 전송 실패는 정상)

- [ ] **Step 4: Commit**

```bash
git add rag_server/main.py
git commit -m "feat: RAG server에 pipeline trace 수집 및 webhook 콜백 포함 추가"
```

---

### Task 2: 백엔드 스키마 — `analysis_job`에 `pipeline_trace` 컬럼 추가

**Files:**
- Modify: `backend/src/main/resources/schema.sql`

- [ ] **Step 1: `schema.sql`의 `analysis_job` 테이블 정의에 컬럼 추가**

`schema.sql` 파일에서 아래 블록을 찾는다:
```sql
CREATE TABLE analysis_job (
    analysis_id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    qa_perspective VARCHAR(255),
    custom_prompt TEXT,
    summary TEXT,
    status VARCHAR(50) NOT NULL,
    missing_items_text VARCHAR(2000),
    CONSTRAINT fk_job_project FOREIGN KEY (project_id) REFERENCES project(project_id) ON DELETE CASCADE
);
```

`missing_items_text` 행 다음, `CONSTRAINT` 행 앞에 아래 한 줄을 삽입한다:
```sql
    pipeline_trace TEXT,
```

결과:
```sql
CREATE TABLE analysis_job (
    analysis_id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    qa_perspective VARCHAR(255),
    custom_prompt TEXT,
    summary TEXT,
    status VARCHAR(50) NOT NULL,
    missing_items_text VARCHAR(2000),
    pipeline_trace TEXT,
    CONSTRAINT fk_job_project FOREIGN KEY (project_id) REFERENCES project(project_id) ON DELETE CASCADE
);
```

- [ ] **Step 2: 기존 H2 DB 파일 삭제 (스키마 재생성 필요)**

```bash
rm /Users/rinaeshin/IdeaProjects/RAG-Combination/backend/data/yeonam_db.mv.db
```

스프링 부트 재시작 시 `schema.sql`을 기반으로 DB가 자동 재생성된다.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/schema.sql
git commit -m "feat: analysis_job 테이블에 pipeline_trace TEXT 컬럼 추가"
```

---

### Task 3: 백엔드 도메인 — `AnalysisJob.java`에 `pipelineTrace` 필드 추가

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/domain/AnalysisJob.java`

- [ ] **Step 1: 필드, getter/setter, builder 추가**

`AnalysisJob.java`에서 `missingItemsText` 필드 선언 바로 아래에 새 필드를 추가한다:

```java
@Column(name = "pipeline_trace", columnDefinition = "TEXT")
private String pipelineTrace;
```

`getMissingItemsText()` / `setMissingItemsText()` 메서드 아래에 getter/setter를 추가한다:
```java
public String getPipelineTrace() { return pipelineTrace; }
public void setPipelineTrace(String pipelineTrace) { this.pipelineTrace = pipelineTrace; }
```

`AnalysisJobBuilder` 내부 클래스에 필드와 builder 메서드를 추가한다:
- 내부 클래스 필드 선언부에: `private String pipelineTrace;`
- 기존 `missingItemsText()` builder 메서드 아래에:
```java
public AnalysisJobBuilder pipelineTrace(String pipelineTrace) {
    this.pipelineTrace = pipelineTrace;
    return this;
}
```

`build()` 메서드는 현재 `new AnalysisJob(analysisId, project, qaPerspective, customPrompt, summary, status, missingItemsText)`를 호출하는데, 새 필드는 생성자 대신 setter로 주입한다. `build()` 메서드를 아래로 교체한다:
```java
public AnalysisJob build() {
    AnalysisJob job = new AnalysisJob(analysisId, project, qaPerspective, customPrompt, summary, status, missingItemsText);
    job.setPipelineTrace(pipelineTrace);
    return job;
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
./mvnw compile -q
```

Expected: BUILD SUCCESS (에러 없음)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/domain/AnalysisJob.java
git commit -m "feat: AnalysisJob 엔티티에 pipelineTrace 필드 추가"
```

---

### Task 4: 백엔드 DTO — `AnalysisCallbackRequest.java`에 `pipelineTrace` 필드 추가

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/dto/AnalysisCallbackRequest.java`

RAG 서버가 보내는 `pipelineTrace` JSON 배열을 Jackson이 `List<Map<String, Object>>`로 역직렬화할 수 있도록 필드를 추가한다.

- [ ] **Step 1: import 추가**

`AnalysisCallbackRequest.java` 파일 상단 import 블록에 아래를 추가한다:
```java
import java.util.Map;
```

(`java.util.List`는 이미 존재함)

- [ ] **Step 2: 필드, getter/setter, builder 추가**

클래스 필드 선언부 (`errorMessage` 필드 아래)에 추가:
```java
private List<Map<String, Object>> pipelineTrace;
```

`getErrorMessage()` / `setErrorMessage()` 메서드 아래에 getter/setter 추가:
```java
public List<Map<String, Object>> getPipelineTrace() { return pipelineTrace; }
public void setPipelineTrace(List<Map<String, Object>> pipelineTrace) { this.pipelineTrace = pipelineTrace; }
```

`AnalysisCallbackRequestBuilder` 내부 클래스에:
- 필드: `private List<Map<String, Object>> pipelineTrace;`
- builder 메서드 (`errorMessage()` 아래):
```java
public AnalysisCallbackRequestBuilder pipelineTrace(List<Map<String, Object>> pipelineTrace) {
    this.pipelineTrace = pipelineTrace;
    return this;
}
```
- `build()` 메서드 교체:
```java
public AnalysisCallbackRequest build() {
    AnalysisCallbackRequest req = new AnalysisCallbackRequest(summary, testCases, missingItems, status, errorMessage);
    req.setPipelineTrace(pipelineTrace);
    return req;
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/dto/AnalysisCallbackRequest.java
git commit -m "feat: AnalysisCallbackRequest DTO에 pipelineTrace 필드 추가"
```

---

### Task 5: 백엔드 서비스 — `CallbackService.java`에서 trace 저장

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/service/CallbackService.java`

콜백 수신 시 `pipelineTrace`를 JSON 문자열로 직렬화해 `AnalysisJob`에 저장한다.

- [ ] **Step 1: `ObjectMapper` import 및 의존성 주입 추가**

파일 상단 import 블록에 추가:
```java
import com.fasterxml.jackson.databind.ObjectMapper;
```

클래스 필드 선언부에 추가:
```java
private final ObjectMapper objectMapper;
```

생성자 파라미터에 `ObjectMapper objectMapper` 추가 및 `this.objectMapper = objectMapper;` 할당:

기존 생성자:
```java
public CallbackService(AnalysisJobRepository analysisJobRepository,
                       RequirementRepository requirementRepository,
                       TestCaseRepository testCaseRepository,
                       RiskItemRepository riskItemRepository,
                       EvidenceRepository evidenceRepository,
                       PriorityEvaluator priorityEvaluator,
                       RiskDetector riskDetector,
                       FallbackHandler fallbackHandler) {
```

아래로 교체:
```java
public CallbackService(AnalysisJobRepository analysisJobRepository,
                       RequirementRepository requirementRepository,
                       TestCaseRepository testCaseRepository,
                       RiskItemRepository riskItemRepository,
                       EvidenceRepository evidenceRepository,
                       PriorityEvaluator priorityEvaluator,
                       RiskDetector riskDetector,
                       FallbackHandler fallbackHandler,
                       ObjectMapper objectMapper) {
    this.analysisJobRepository = analysisJobRepository;
    this.requirementRepository = requirementRepository;
    this.testCaseRepository = testCaseRepository;
    this.riskItemRepository = riskItemRepository;
    this.evidenceRepository = evidenceRepository;
    this.priorityEvaluator = priorityEvaluator;
    this.riskDetector = riskDetector;
    this.fallbackHandler = fallbackHandler;
    this.objectMapper = objectMapper;
}
```

- [ ] **Step 2: `processCallback()`에서 trace 직렬화 및 저장**

`processCallback()` 메서드에서 `job.setStatus("COMPLETED");` 라인 바로 아래, `job.setMissingItemsText(...)` 블록 위에 아래 코드를 삽입한다:

```java
// Persist pipeline trace as JSON string
if (request.getPipelineTrace() != null && !request.getPipelineTrace().isEmpty()) {
    try {
        job.setPipelineTrace(objectMapper.writeValueAsString(request.getPipelineTrace()));
    } catch (Exception e) {
        log.warn("Failed to serialize pipeline trace for job {}: {}", analysisId, e.getMessage());
    }
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/service/CallbackService.java
git commit -m "feat: CallbackService에서 pipelineTrace를 JSON 직렬화 후 AnalysisJob에 저장"
```

---

### Task 6: 백엔드 DTO — `AnalysisResultResponse.java`에 `pipelineTrace` 필드 추가

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/dto/AnalysisResultResponse.java`

- [ ] **Step 1: import 추가**

파일 상단 import 블록에 추가:
```java
import java.util.Map;
```

- [ ] **Step 2: 필드 추가**

클래스 필드 선언부 (`missingItems` 필드 아래)에 추가:
```java
private List<Map<String, Object>> pipelineTrace;
```

- [ ] **Step 3: getter/setter 추가**

`getMissingItems()` / `setMissingItems()` 메서드 아래에:
```java
public List<Map<String, Object>> getPipelineTrace() { return pipelineTrace; }
public void setPipelineTrace(List<Map<String, Object>> pipelineTrace) { this.pipelineTrace = pipelineTrace; }
```

- [ ] **Step 4: Builder에 `pipelineTrace` 추가**

`AnalysisResultResponseBuilder` 내부 클래스에:
- 필드: `private List<Map<String, Object>> pipelineTrace;`
- builder 메서드 (`missingItems()` 아래):
```java
public AnalysisResultResponseBuilder pipelineTrace(List<Map<String, Object>> pipelineTrace) {
    this.pipelineTrace = pipelineTrace;
    return this;
}
```
- `build()` 메서드 교체:
```java
public AnalysisResultResponse build() {
    AnalysisResultResponse r = new AnalysisResultResponse(analysisId, summary, testCases, missingItems);
    r.setPipelineTrace(pipelineTrace);
    return r;
}
```

- [ ] **Step 5: 컴파일 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/dto/AnalysisResultResponse.java
git commit -m "feat: AnalysisResultResponse DTO에 pipelineTrace 필드 추가"
```

---

### Task 7: 백엔드 서비스 — `AnalysisService.java`에서 trace 역직렬화 및 응답 포함

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/service/AnalysisService.java`

`getAnalysisResults()`가 `AnalysisJob.pipelineTrace` JSON 문자열을 역직렬화해 `AnalysisResultResponse`에 포함시킨다.

- [ ] **Step 1: import 추가**

파일 상단 import 블록에 추가:
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
```

- [ ] **Step 2: `ObjectMapper` 의존성 주입**

클래스 필드에 추가:
```java
private final ObjectMapper objectMapper;
```

생성자 파라미터에 `ObjectMapper objectMapper` 추가. 기존 생성자:
```java
public AnalysisService(AnalysisJobRepository analysisJobRepository,
                       ProjectRepository projectRepository,
                       UploadedFileRepository fileRepository,
                       TestCaseRepository testCaseRepository,
                       RequirementRepository requirementRepository,
                       RiskItemRepository riskItemRepository,
                       EvidenceRepository evidenceRepository,
                       S3Client s3Client,
                       ReportRepository reportRepository,
                       AnalysisProcessor analysisProcessor) {
```

파라미터 마지막에 `ObjectMapper objectMapper` 추가하고, 생성자 본문 마지막에 `this.objectMapper = objectMapper;` 추가.

- [ ] **Step 3: `getAnalysisResults()`에서 trace 역직렬화 및 builder에 포함**

`getAnalysisResults()` 메서드 내부, `return AnalysisResultResponse.builder()` 직전에 아래 코드를 삽입한다:

```java
List<Map<String, Object>> pipelineTrace = new ArrayList<>();
if (job.getPipelineTrace() != null && !job.getPipelineTrace().isBlank()) {
    try {
        pipelineTrace = objectMapper.readValue(
            job.getPipelineTrace(),
            new TypeReference<List<Map<String, Object>>>() {}
        );
    } catch (Exception e) {
        log.warn("Failed to deserialize pipeline trace for job {}: {}", analysisId, e.getMessage());
    }
}
```

그리고 `return AnalysisResultResponse.builder()` 체인에 `.pipelineTrace(pipelineTrace)` 추가:

```java
return AnalysisResultResponse.builder()
        .analysisId(analysisId)
        .summary(job.getSummary() != null ? job.getSummary() : "전체 기능 명세 요구사항 분석 요약 완료.")
        .testCases(tcDtos)
        .missingItems(missing)
        .pipelineTrace(pipelineTrace)
        .build();
```

`log`가 `AnalysisService`에 없다면 클래스 선언부 아래에 아래 필드를 추가한다:
```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalysisService.class);
```

- [ ] **Step 4: 컴파일 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/service/AnalysisService.java
git commit -m "feat: AnalysisService getAnalysisResults()에서 pipelineTrace 역직렬화 후 응답 포함"
```

---

### Task 8: 백엔드 테스트 — `PipelineTraceTests.java` 작성

**Files:**
- Create: `backend/src/test/java/com/yeonam/tester/PipelineTraceTests.java`

- [ ] **Step 1: 테스트 파일 작성**

```java
package com.yeonam.tester;

import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.domain.Project;
import com.yeonam.tester.dto.AnalysisCallbackRequest;
import com.yeonam.tester.dto.AnalysisResultResponse;
import com.yeonam.tester.repository.AnalysisJobRepository;
import com.yeonam.tester.repository.ProjectRepository;
import com.yeonam.tester.service.AnalysisService;
import com.yeonam.tester.service.CallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PipelineTraceTests {

    @Autowired
    private CallbackService callbackService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private static final String TEST_PROJECT_ID = "PRJ-TRACE-TEST";
    private static final String TEST_ANALYSIS_ID = "ANL-TRACE-TEST-001";

    @BeforeEach
    void setUp() {
        analysisJobRepository.deleteById(TEST_ANALYSIS_ID);
        projectRepository.deleteById(TEST_PROJECT_ID);

        Project project = Project.builder()
                .projectId(TEST_PROJECT_ID)
                .name("Trace Test Project")
                .description("For pipeline trace tests")
                .createdAt(LocalDateTime.now())
                .build();
        projectRepository.save(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId(TEST_ANALYSIS_ID)
                .project(project)
                .status("WAITING")
                .build();
        analysisJobRepository.save(job);
    }

    @Test
    void pipelineTrace_savedAndRetrievedCorrectly() {
        // Arrange: build a callback request with pipelineTrace
        List<Map<String, Object>> trace = List.of(
                Map.of("step", "PARSE",    "status", "SUCCESS", "fileCount", 2, "durationMs", 1200),
                Map.of("step", "CHUNK",    "status", "SUCCESS", "chunkCount", 47, "durationMs", 300),
                Map.of("step", "INDEX",    "status", "SUCCESS", "vectorCount", 47, "durationMs", 800),
                Map.of("step", "EXTRACT",  "status", "SUCCESS", "reqCount", 8, "durationMs", 2100),
                Map.of("step", "RETRIEVE", "status", "SUCCESS", "topScore", 0.87, "durationMs", 95)
        );

        AnalysisCallbackRequest req = AnalysisCallbackRequest.builder()
                .status("COMPLETED")
                .summary("Test summary")
                .testCases(Collections.emptyList())
                .missingItems(Collections.emptyList())
                .pipelineTrace(trace)
                .build();

        // Act
        callbackService.processCallback(TEST_ANALYSIS_ID, req);

        // Assert: pipelineTrace persisted on job
        AnalysisJob saved = analysisJobRepository.findById(TEST_ANALYSIS_ID).orElseThrow();
        assertNotNull(saved.getPipelineTrace(), "pipeline_trace 컬럼에 값이 저장되어야 한다");
        assertTrue(saved.getPipelineTrace().contains("PARSE"), "저장된 JSON에 PARSE 단계가 포함되어야 한다");
        assertTrue(saved.getPipelineTrace().contains("SUCCESS"), "저장된 JSON에 SUCCESS 상태가 포함되어야 한다");

        // Assert: pipelineTrace included in result response
        AnalysisResultResponse result = analysisService.getAnalysisResults(TEST_ANALYSIS_ID);
        assertNotNull(result.getPipelineTrace(), "결과 응답에 pipelineTrace가 포함되어야 한다");
        assertEquals(5, result.getPipelineTrace().size(), "5단계 trace가 모두 포함되어야 한다");
        assertEquals("PARSE", result.getPipelineTrace().get(0).get("step"));
        assertEquals("SUCCESS", result.getPipelineTrace().get(0).get("status"));
    }

    @Test
    void pipelineTrace_mockMode_allSkipped() {
        // Arrange: mock mode trace (all SKIPPED)
        List<Map<String, Object>> mockTrace = List.of(
                Map.of("step", "PARSE",    "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "CHUNK",    "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "INDEX",    "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "EXTRACT",  "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "RETRIEVE", "status", "SKIPPED", "durationMs", 0)
        );

        AnalysisCallbackRequest req = AnalysisCallbackRequest.builder()
                .status("COMPLETED")
                .summary("Mock summary")
                .testCases(Collections.emptyList())
                .missingItems(Collections.emptyList())
                .pipelineTrace(mockTrace)
                .build();

        // Act
        callbackService.processCallback(TEST_ANALYSIS_ID, req);

        // Assert
        AnalysisResultResponse result = analysisService.getAnalysisResults(TEST_ANALYSIS_ID);
        assertNotNull(result.getPipelineTrace());
        assertEquals(5, result.getPipelineTrace().size());
        assertTrue(result.getPipelineTrace().stream()
                .allMatch(step -> "SKIPPED".equals(step.get("status"))),
                "Mock 모드에서는 모든 단계가 SKIPPED여야 한다");
    }

    @Test
    void pipelineTrace_nullWhenNotProvided() {
        // Arrange: no pipelineTrace in callback
        AnalysisCallbackRequest req = AnalysisCallbackRequest.builder()
                .status("COMPLETED")
                .summary("No trace summary")
                .testCases(Collections.emptyList())
                .missingItems(Collections.emptyList())
                .build();

        // Act
        callbackService.processCallback(TEST_ANALYSIS_ID, req);

        // Assert
        AnalysisResultResponse result = analysisService.getAnalysisResults(TEST_ANALYSIS_ID);
        // pipelineTrace가 없을 때 빈 리스트 반환 (null이 아님)
        assertNotNull(result.getPipelineTrace());
        assertTrue(result.getPipelineTrace().isEmpty(), "trace가 없으면 빈 리스트여야 한다");
    }
}
```

- [ ] **Step 2: 테스트 실행 (RED 확인 — Task 5-7이 완료된 후 GREEN이 되어야 함)**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
./mvnw test -Dtest=PipelineTraceTests -q
```

Task 2-7이 모두 완료된 상태라면: Expected: 3 tests PASSED

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/yeonam/tester/PipelineTraceTests.java
git commit -m "test: PipelineTrace 저장 및 조회 통합 테스트 추가"
```

---

### Task 9: 프론트엔드 타입 — `api.ts`에 `PipelineTraceStep` 추가

**Files:**
- Modify: `frontend/src/services/api.ts`

- [ ] **Step 1: `PipelineTraceStep` 인터페이스 및 `AnalysisResultResponse` 확장**

`api.ts` 파일에서 `AnalysisResultResponse` 인터페이스를 찾는다:
```typescript
export interface AnalysisResultResponse {
  analysisId: string;
  summary: string;
  testCases: TestCase[];
  missingItems: string[];
}
```

이 인터페이스 **바로 위**에 `PipelineTraceStep` 인터페이스를 삽입하고, `AnalysisResultResponse`에 `pipelineTrace` 필드를 추가한다:

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

export interface AnalysisResultResponse {
  analysisId: string;
  summary: string;
  testCases: TestCase[];
  missingItems: string[];
  pipelineTrace?: PipelineTraceStep[];
}
```

- [ ] **Step 2: TypeScript 타입 체크**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend
npx tsc --noEmit
```

Expected: 에러 없음

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/api.ts
git commit -m "feat: PipelineTraceStep 타입 및 AnalysisResultResponse 확장"
```

---

### Task 10: 프론트엔드 — `RagTraceModal.tsx` 신규 컴포넌트 작성

**Files:**
- Create: `frontend/src/pages/RagTraceModal.tsx`

- [ ] **Step 1: 컴포넌트 파일 작성**

```tsx
import React from 'react';
import { PipelineTraceStep } from '../services/api';

const STEP_LABELS: Record<string, string> = {
  PARSE:    '1. 문서 파싱',
  CHUNK:    '2. 텍스트 청킹',
  INDEX:    '3. 벡터 인덱싱',
  EXTRACT:  '4. 요구사항 추출',
  RETRIEVE: '5. 유사도 검색',
};

const STEP_ICONS: Record<string, string> = {
  PARSE:    'description',
  CHUNK:    'content_cut',
  INDEX:    'database',
  EXTRACT:  'manage_search',
  RETRIEVE: 'query_stats',
};

function getStepMeta(step: PipelineTraceStep): string {
  const parts: string[] = [];
  if (step.fileCount !== undefined)   parts.push(`${step.fileCount}개 파일`);
  if (step.chunkCount !== undefined)  parts.push(`${step.chunkCount}개 청크`);
  if (step.vectorCount !== undefined) parts.push(`${step.vectorCount}개 벡터`);
  if (step.reqCount !== undefined)    parts.push(`${step.reqCount}개 요구사항`);
  if (step.topScore !== undefined)    parts.push(`최고 유사도 ${Math.round(step.topScore * 100)}%`);
  return parts.join(' · ');
}

function isRealRag(trace: PipelineTraceStep[]): boolean {
  return trace.some(s => s.status === 'SUCCESS');
}

interface Props {
  analysisId: string;
  trace: PipelineTraceStep[];
  onClose: () => void;
}

export const RagTraceModal: React.FC<Props> = ({ analysisId, trace, onClose }) => {
  const real = isRealRag(trace);

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/75 backdrop-blur-sm p-4">
      <div
        className="bg-surface-container-high border border-white/10 rounded-2xl shadow-2xl w-full max-w-lg relative"
        style={{ background: '#1e1e2f' }}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-white/10">
          <div>
            <h3 className="text-lg font-bold bg-gradient-to-r from-primary to-tertiary bg-clip-text text-transparent flex items-center gap-2">
              <span className="material-symbols-outlined text-indigo-400">biotech</span>
              RAG 파이프라인 진단
            </h3>
            <p className="text-[11px] text-slate-500 font-mono mt-0.5">{analysisId}</p>
          </div>
          <div className="flex items-center gap-3">
            <span
              className={`text-xs font-bold px-3 py-1 rounded-full border ${
                real
                  ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                  : 'bg-slate-500/10 text-slate-400 border-slate-500/30'
              }`}
            >
              {real ? '● REAL RAG' : '○ MOCK'}
            </span>
            <button
              onClick={onClose}
              className="text-slate-400 hover:text-white transition-colors"
            >
              <span className="material-symbols-outlined">close</span>
            </button>
          </div>
        </div>

        {/* Steps */}
        <div className="px-6 py-4 space-y-3">
          {trace.map((step) => {
            const isSuccess = step.status === 'SUCCESS';
            const isSkipped = step.status === 'SKIPPED';
            const isFailed  = step.status === 'FAILED';
            const meta = getStepMeta(step);

            return (
              <div
                key={step.step}
                className={`flex items-center gap-4 p-3 rounded-xl border ${
                  isSuccess ? 'border-emerald-500/20 bg-emerald-500/5' :
                  isFailed  ? 'border-red-500/20 bg-red-500/5' :
                              'border-white/5 bg-white/3'
                }`}
              >
                {/* Status icon */}
                <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${
                  isSuccess ? 'bg-emerald-500/20 text-emerald-400' :
                  isFailed  ? 'bg-red-500/20 text-red-400' :
                              'bg-white/5 text-slate-600'
                }`}>
                  <span className="material-symbols-outlined text-sm">
                    {isSuccess ? 'check_circle' : isFailed ? 'error' : 'radio_button_unchecked'}
                  </span>
                </div>

                {/* Step info */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="material-symbols-outlined text-sm text-slate-500">
                      {STEP_ICONS[step.step] || 'circle'}
                    </span>
                    <span className={`text-sm font-semibold ${
                      isSuccess ? 'text-slate-200' : isFailed ? 'text-red-300' : 'text-slate-600'
                    }`}>
                      {STEP_LABELS[step.step] || step.step}
                    </span>
                  </div>
                  {meta && (
                    <p className="text-[11px] text-slate-500 mt-0.5 ml-6">{meta}</p>
                  )}
                  {isFailed && step.errorMessage && (
                    <p className="text-[11px] text-red-400 mt-0.5 ml-6">{step.errorMessage}</p>
                  )}
                </div>

                {/* Duration */}
                <span className={`text-[11px] font-mono shrink-0 ${
                  isSkipped ? 'text-slate-600' : 'text-slate-400'
                }`}>
                  {isSkipped ? '-' : `${step.durationMs}ms`}
                </span>
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div className="px-6 pb-5 pt-2 border-t border-white/5">
          <p className="text-[11px] text-slate-600 text-center">
            {real
              ? '실제 문서 기반 RAG 파이프라인이 실행되었습니다.'
              : 'MOCK_RAG=true 모드로 실행되었습니다. 실제 문서 파싱 및 벡터 검색이 생략되었습니다.'}
          </p>
        </div>
      </div>
    </div>
  );
};
```

- [ ] **Step 2: TypeScript 타입 체크**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend
npx tsc --noEmit
```

Expected: 에러 없음

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/RagTraceModal.tsx
git commit -m "feat: RAG 파이프라인 진단 모달 컴포넌트 추가"
```

---

### Task 11: 프론트엔드 — `AnalysisResultPage.tsx`에 "RAG 진단" 버튼 및 모달 연동

**Files:**
- Modify: `frontend/src/pages/AnalysisResultPage.tsx`

- [ ] **Step 1: import 추가**

파일 상단 import 블록에 아래를 추가한다:
```tsx
import { RagTraceModal } from './RagTraceModal';
import { PipelineTraceStep } from '../services/api';
```

- [ ] **Step 2: state 추가**

`AnalysisResultPage` 컴포넌트 내부, 기존 `useState` 선언들 아래에 추가한다:
```tsx
const [isTraceModalOpen, setIsTraceModalOpen] = useState(false);
const [pipelineTrace, setPipelineTrace] = useState<PipelineTraceStep[]>([]);
```

- [ ] **Step 3: `fetchAnalysisResults`에서 trace 저장**

`fetchAnalysisResults` 함수 내부, `setTestCases(response.data.testCases);` 아래에 추가한다:
```tsx
setPipelineTrace(response.data.pipelineTrace || []);
```

- [ ] **Step 4: "RAG 진단" 버튼 추가**

`AnalysisResultPage`의 JSX에서 상단 "AI TDD 분석 검증 완료" 패널을 감싸는 `<section>` 안의 `<div className="glass-panel p-lg rounded-xl flex flex-col md:flex-row items-center gap-lg">` 블록 내부, 닫는 `</div>` 바로 전에 아래 버튼을 추가한다:

```tsx
<button
  onClick={() => setIsTraceModalOpen(true)}
  disabled={pipelineTrace.length === 0}
  className={`mt-4 md:mt-0 md:ml-auto self-end flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all ${
    pipelineTrace.length > 0
      ? 'border-indigo-500/30 bg-indigo-500/10 text-indigo-400 hover:bg-indigo-500/20'
      : 'border-white/5 bg-white/3 text-slate-600 cursor-not-allowed'
  }`}
>
  <span className="material-symbols-outlined text-sm">biotech</span>
  RAG 진단
</button>
```

- [ ] **Step 5: 모달 렌더링 추가**

`AnalysisResultPage` JSX 최하단, 기존 "보고서 포맷 선택" 모달(`{isReportModalOpen && ...}`) 아래에 추가한다:
```tsx
{isTraceModalOpen && (
  <RagTraceModal
    analysisId={analysisId}
    trace={pipelineTrace}
    onClose={() => setIsTraceModalOpen(false)}
  />
)}
```

- [ ] **Step 6: TypeScript 타입 체크**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend
npx tsc --noEmit
```

Expected: 에러 없음

- [ ] **Step 7: 전체 통합 동작 확인**

1. `backend/data/yeonam_db.mv.db` 삭제 후 Spring Boot 재시작
2. RAG 서버 기동 (`MOCK_RAG=true` 상태)
3. 프론트엔드 기동 (`npm run dev`)
4. 브라우저에서 프로젝트 생성 → 문서 업로드 → 분석 실행
5. 분석 완료 후 `AnalysisResultPage`에서 "RAG 진단" 버튼이 활성화됨 확인
6. 버튼 클릭 → 모달에서 5개 단계 모두 `SKIPPED` (회색), 배지 `MOCK` 표시 확인

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/AnalysisResultPage.tsx
git commit -m "feat: AnalysisResultPage에 RAG 진단 버튼 및 모달 연동"
```
