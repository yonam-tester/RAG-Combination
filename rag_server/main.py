from fastapi import FastAPI, BackgroundTasks, status, Response
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from contextlib import asynccontextmanager
from dotenv import load_dotenv
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
import logging
import os
import time
from typing import List, Optional
from pydantic import BaseModel

from queue_manager import queue_manager
from document_parser import process_and_extract
from text_chunker import chunk_document
import vector_store
import ingestion
from requirement_extractor import extract_requirements
from retriever import retrieve_evidences
from prompt_builder import build_prompt, call_llm_with_key
from webhook_sender import send_callback, send_failure_callback

# Load environment variables
load_dotenv()

# Configure Logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("rag_server.main")

async def process_job(job_data: dict):
    analysis_id = job_data.get("analysisId")
    project_id = job_data.get("projectId")
    s3_paths = job_data.get("s3Paths", [])
    perspectives = job_data.get("qaPerspectives", [])
    custom_prompt = job_data.get("customPrompt", "")
    llm_api_key = job_data.get("llmApiKey")

    logger.info(f"Worker processing RAG job {analysis_id}")

    pipeline_trace = []

    try:
        # Step 1: PARSE
        t0 = time.time()
        logger.info("Downloading and parsing documents from S3...")
        parsed_documents = await process_and_extract(s3_paths, llm_api_key)
        pipeline_trace.append({
            "step": "PARSE",
            "status": "SUCCESS",
            "fileCount": len(parsed_documents),
            "durationMs": int((time.time() - t0) * 1000)
        })

        # Step 2: CHUNK
        t0 = time.time()
        logger.info("Cleaning and chunking parsed text...")
        chunks = []
        for doc in parsed_documents:
            chunks.extend(chunk_document(doc["text"], doc["file_id"], doc["file_name"]))
        pipeline_trace.append({
            "step": "CHUNK",
            "status": "SUCCESS",
            "chunkCount": len(chunks),
            "durationMs": int((time.time() - t0) * 1000)
        })

        # Step 3: INDEX
        t0 = time.time()
        logger.info(f"Indexing {len(chunks)} chunks to Qdrant...")
        vector_store.add_documents(chunks)
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

        # Step 5: RETRIEVE + GENERATE
        test_cases = []
        top_score = 0.0
        elapsed_retrieve = 0
        seen_chunk_ids: set = set()

        for req in requirements:
            req_id = req["id"]
            req_text = req["text"]

            t0 = time.time()
            evidences = retrieve_evidences(req_text, exclude_chunk_ids=seen_chunk_ids)
            elapsed_retrieve += int((time.time() - t0) * 1000)
            seen_chunk_ids.update(ev["chunk_id"] for ev in evidences)

            for ev in evidences:
                sc = ev.get("score", 0.0)
                if sc is not None and sc > top_score:
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

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: 지식카드를 Qdrant에 적재한다.
    # Qdrant 연결이나 임베딩 로드가 실패하면 예외가 전파되어 기동이 실패한다 (fail-fast).
    count = ingestion.ingest_knowledge_base()
    logger.info(f"Startup ingestion complete: {count} knowledge documents")

    queue_manager.start_worker(process_job)
    yield
    # Shutdown: Stop worker safely
    await queue_manager.stop_worker()

app = FastAPI(title="Yeonam Tester RAG AI Server", lifespan=lifespan)

RAG_TOKEN_COUNTER = Counter(
    'rag_tokens_total',
    'Total tokens processed by rag-server',
    ['service']
)
RAG_REQUEST_DURATION = Histogram(
    'rag_request_duration_seconds',
    'RAG request duration in seconds',
    ['service'],
    buckets=[1, 5, 10, 30, 60, 120]
)

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request, exc):
    logger.error(f"Request validation error: {exc.errors()}")
    logger.error(f"Request body: {await request.body()}")
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={"detail": exc.errors(), "body": str(await request.body())}
    )

class TriggerRequest(BaseModel):
    analysisId: str
    projectId: str
    s3Paths: List[str]
    qaPerspectives: Optional[List[str]] = None
    customPrompt: Optional[str] = None
    llmApiKey: Optional[str] = None

class PreprocessRequest(BaseModel):
    fileId: str
    s3Path: str

@app.post("/api/files/preprocess")
async def preprocess_file_api(req: PreprocessRequest):
    logger.info(f"Received preprocess request for file: {req.fileId}, path: {req.s3Path}")
    try:
        # Preprocess download and check parsing suitability
        await process_and_extract([req.s3Path])
        return {"status": "success", "fileId": req.fileId, "valid": True}
    except Exception as e:
        logger.error(f"Failed to parse and preprocess file {req.fileId}: {str(e)}")
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"status": "error", "message": f"파싱 오류: {str(e)}"}
        )

@app.post("/api/analysis/trigger", status_code=status.HTTP_202_ACCEPTED)
async def trigger_analysis_api(req: TriggerRequest):
    logger.info(f"Received trigger request for RAG analysis: {req.analysisId}")
    job_data = {
        "analysisId": req.analysisId,
        "projectId": req.projectId,
        "s3Paths": req.s3Paths,
        "qaPerspectives": req.qaPerspectives,
        "customPrompt": req.customPrompt,
        "llmApiKey": req.llmApiKey
    }
    await queue_manager.add_job(job_data)
    return {"message": "Job accepted and queued for RAG analysis."}

@app.post("/analyze", status_code=status.HTTP_202_ACCEPTED)
async def trigger_analysis_alternative(req: TriggerRequest):
    logger.info(f"Received trigger request on alternative route for RAG analysis: {req.analysisId}")
    return await trigger_analysis_api(req)

@app.delete("/api/vectors/{fileId}")
async def delete_vectors_api(fileId: str):
    logger.info(f"Received delete vectors request for fileId: {fileId}")
    try:
        vector_store.delete_by_file_id(fileId)
        return {"status": "success", "message": f"Vectors for file {fileId} deleted."}
    except Exception as e:
        logger.error(f"Failed to delete vectors for file {fileId}: {str(e)}")
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"status": "error", "message": f"벡터 삭제 오류: {str(e)}"}
        )

@app.get("/metrics")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


class EvalRequest(BaseModel):
    text: str
    perspectives: List[str] = []
    llm_api_key: Optional[str] = None


@app.post("/api/eval/generate")
async def eval_generate_rag(req: EvalRequest):
    start = time.time()

    parsed_documents = [{"text": req.text, "file_id": "eval-doc", "file_name": "eval.txt"}]
    chunks = chunk_document(req.text, "eval-doc", "eval.txt")
    vector_store.add_documents(chunks)

    requirements = await extract_requirements(parsed_documents, req.llm_api_key)

    all_test_cases = []
    for requirement in requirements:
        evidences = retrieve_evidences(requirement["text"], exclude_chunk_ids=set())
        prompt = build_prompt(requirement["text"], evidences[:3], "", req.perspectives)
        raw_tcs = await call_llm_with_key(prompt, req.llm_api_key)
        all_test_cases.extend(raw_tcs)

    duration_s = time.time() - start
    token_count = len(req.text.split())

    RAG_TOKEN_COUNTER.labels(service="rag").inc(token_count)
    RAG_REQUEST_DURATION.labels(service="rag").observe(duration_s)

    return {
        "output": str(all_test_cases),
        "token_count": token_count,
        "duration_ms": int(duration_s * 1000)
    }


@app.get("/health")
async def health_check():
    qdrant_up = vector_store.ping()
    return JSONResponse(
        status_code=status.HTTP_200_OK if qdrant_up else status.HTTP_503_SERVICE_UNAVAILABLE,
        content={
            "status": "healthy" if qdrant_up else "unhealthy",
            "mock_llm": os.getenv("MOCK_LLM", "true").lower() == "true",
            "qdrant": "up" if qdrant_up else "down",
            "queue_size": queue_manager.queue.qsize(),
        },
    )
