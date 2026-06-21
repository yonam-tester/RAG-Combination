from fastapi import FastAPI, BackgroundTasks, status, Response
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from contextlib import asynccontextmanager
from dotenv import load_dotenv
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
import logging
import os
import time

from queue_manager import queue_manager
from document_parser import process_and_extract
from llm_client import call_llm
from result_formatter import format_and_validate_result
from webhook_sender import send_callback, send_failure_callback

# Load environment variables
load_dotenv()

# Configure Logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("llm_server.main")

async def process_job(job_data: dict):
    analysis_id = job_data.get("analysisId")
    s3_paths = job_data.get("s3Paths", [])
    perspectives = job_data.get("qaPerspectives", [])
    custom_prompt = job_data.get("customPrompt", "")
    llm_api_key = job_data.get("llmApiKey")
    
    logger.info(f"Worker processing job {analysis_id}")
    
    mock_llm = os.getenv("MOCK_LLM", "true").lower() == "true"
    
    try:
        combined_text = ""
        try:
            # Always download and parse documents to support local RAG mock generation
            combined_text = await process_and_extract(s3_paths)
        except Exception as ex:
            logger.warning(f"S3 download/parse failed: {str(ex)}. Falling back with empty document text.")
            
        # 2. Call LLM (or mock)
        raw_response = await call_llm(combined_text, perspectives, custom_prompt, llm_api_key)
        
        # 3. Format and validate
        formatted_data = format_and_validate_result(raw_response)
        
        # 4. Webhook callback to Spring Boot
        success = await send_callback(analysis_id, formatted_data)
        if not success:
            logger.error(f"Failed to send webhook callback for job {analysis_id}")
            
    except Exception as e:
        logger.error(f"Error while executing worker for job {analysis_id}: {str(e)}", exc_info=True)
        # Send failure callback to backend
        try:
            await send_failure_callback(analysis_id, f"AI 서버 분석 중 예외 발생: {str(e)}")
        except Exception as err:
            logger.error(f"Failed to send failure callback for job {analysis_id}: {str(err)}")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: Start background worker
    queue_manager.start_worker(process_job)
    yield
    # Shutdown: Stop worker safely
    await queue_manager.stop_worker()

app = FastAPI(title="Yeonam Tester MVP AI Server", lifespan=lifespan)

LLM_TOKEN_COUNTER = Counter(
    'llm_tokens_total',
    'Total tokens processed by llm-server',
    ['service']
)
LLM_REQUEST_DURATION = Histogram(
    'llm_request_duration_seconds',
    'LLM request duration in seconds',
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

from pydantic import BaseModel
from typing import List, Optional

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
    mock_llm = os.getenv("MOCK_LLM", "true").lower() == "true"
    
    if mock_llm:
        logger.info("MOCK_LLM is enabled, skipping S3 document download validation.")
        return {"status": "success", "fileId": req.fileId, "valid": True}
        
    try:
        # If not mock, validate if we can parse the document successfully.
        await process_and_extract([req.s3Path])
        return {"status": "success", "fileId": req.fileId, "valid": True}
    except Exception as e:
        logger.error(f"Failed to parse and extract text for file {req.fileId}: {str(e)}")
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"status": "error", "message": f"파싱 오류: {str(e)}"}
        )

@app.post("/api/analysis/trigger", status_code=status.HTTP_202_ACCEPTED)
async def trigger_analysis_api(req: TriggerRequest):
    logger.info(f"Received trigger request for analysis: {req.analysisId}")
    job_data = {
        "analysisId": req.analysisId,
        "projectId": req.projectId,
        "s3Paths": req.s3Paths,
        "qaPerspectives": req.qaPerspectives,
        "customPrompt": req.customPrompt,
        "llmApiKey": req.llmApiKey
    }
    await queue_manager.add_job(job_data)
    return {"message": "Job accepted and queued for analysis."}

@app.post("/analyze", status_code=status.HTTP_202_ACCEPTED)
async def trigger_analysis_alternative(req: TriggerRequest):
    # Alternate endpoint mapping to support both specs
    logger.info(f"Received trigger request on alternative route for analysis: {req.analysisId}")
    return await trigger_analysis_api(req)

@app.get("/metrics")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


class EvalRequest(BaseModel):
    text: str
    perspectives: List[str] = []
    llm_api_key: Optional[str] = None


@app.post("/api/eval/generate")
async def eval_generate(req: EvalRequest):
    start = time.time()
    raw_output = await call_llm(req.text, req.perspectives, "", req.llm_api_key)
    duration_s = time.time() - start
    token_count = len(req.text.split()) + len(str(raw_output).split())

    LLM_TOKEN_COUNTER.labels(service="llm").inc(token_count)
    LLM_REQUEST_DURATION.labels(service="llm").observe(duration_s)

    return {
        "output": raw_output,
        "token_count": token_count,
        "duration_ms": int(duration_s * 1000)
    }


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "mock_llm": os.getenv("MOCK_LLM", "true").lower() == "true",
        "queue_size": queue_manager.queue.qsize()
    }
