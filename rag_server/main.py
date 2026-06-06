from fastapi import FastAPI, BackgroundTasks, status
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from contextlib import asynccontextmanager
from dotenv import load_dotenv
import logging
import os
from typing import List, Optional
from pydantic import BaseModel

from queue_manager import queue_manager
from document_parser import process_and_extract
from text_chunker import chunk_document
from vector_db_manager import vector_db_manager
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
    
    mock_rag = os.getenv("MOCK_RAG", "true").lower() == "true"
    mock_llm = os.getenv("MOCK_LLM", "true").lower() == "true"
    
    try:
        requirements = []
        chunks = []
        
        if not mock_rag:
            # 1. Download and parse documents from S3
            logger.info("Downloading and parsing documents from S3...")
            parsed_documents = await process_and_extract(s3_paths) # list of dict: {file_id, file_name, text}
            
            # 2. Text clean and chunking
            logger.info("Cleaning and chunking parsed text...")
            for doc in parsed_documents:
                doc_chunks = chunk_document(doc["text"], doc["file_id"], doc["file_name"])
                chunks.extend(doc_chunks)
            
            # 3. Build/Index local FAISS Vector DB or Bedrock Knowledge Base
            logger.info(f"Indexing {len(chunks)} chunks to vector database...")
            vector_db_manager.add_chunks(chunks)
            
            # 4. Extract requirements from document text
            logger.info("Extracting requirements from document text...")
            requirements = await extract_requirements(parsed_documents, llm_api_key)
        else:
            logger.info("MOCK_RAG is enabled, skipping S3 download, parsing, chunking, and indexing.")
            # In mock RAG mode, extract_requirements returns static/mocked requirements
            requirements = await extract_requirements([], llm_api_key)
            
        # 5. Hybrid retrieve and merge contexts for each requirement
        logger.info("Retrieving evidences and merging QA knowledge...")
        test_cases = []
        
        # We process each requirement to generate test cases
        for req in requirements:
            req_id = req["id"]
            req_text = req["text"]
            
            # Retrieve evidence chunks for this requirement
            # If mock_rag is enabled, retrieve_evidences will return mock evidence metadata
            evidences = retrieve_evidences(req_text) # list of dict: {chunk_id, text, source_name, source_section, score}
            
            # Limit to at most 3 evidences (Task 7.7 requirement)
            limited_evidences = evidences[:3]
            
            # 6. Build prompt using prompt builder (with truncation and tags)
            prompt = build_prompt(req_text, limited_evidences, custom_prompt, perspectives)
            
            # 7. Call LLM (or mock)
            logger.info(f"Calling LLM for requirement {req_id}...")
            raw_test_cases = await call_llm_with_key(prompt, llm_api_key)
            
            # Add metadata back
            for tc in raw_test_cases:
                tc["requirementId"] = req_id
                tc["requirementText"] = req_text
                # Attach evidence list (Task 8.2 & 8.3 preparation)
                tc["evidence_list"] = limited_evidences
                test_cases.append(tc)
                
        # 8. Webhook callback to Spring Boot
        formatted_data = {
            "analysisId": analysis_id,
            "status": "COMPLETED",
            "summary": f"RAG 기반 분석 완료. 추출된 요구사항 수: {len(requirements)}, 생성된 테스트 케이스 수: {len(test_cases)}.",
            "testCases": test_cases,
            "errorMessage": None
        }
        
        success = await send_callback(analysis_id, formatted_data)
        if not success:
            logger.error(f"Failed to send RAG webhook callback for job {analysis_id}")
            
    except Exception as e:
        logger.error(f"Error while executing RAG worker for job {analysis_id}: {str(e)}", exc_info=True)
        # Send failure callback to backend
        try:
            error_msg = f"RAG 서버 분석 중 예외 발생: {str(e)}"
            # Check if it is an API Key verification failure
            if "AuthenticationError" in type(e).__name__ or "api key" in str(e).lower() or "api_key" in str(e).lower():
                error_msg = "API 키 유효성 검증 실패: 유효하지 않은 API 키이거나 만료되었습니다. 키 설정을 재점검해 주세요."
            await send_failure_callback(analysis_id, error_msg)
        except Exception as err:
            logger.error(f"Failed to send failure callback for job {analysis_id}: {str(err)}")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: Start background worker
    queue_manager.start_worker(process_job)
    yield
    # Shutdown: Stop worker safely
    await queue_manager.stop_worker()

app = FastAPI(title="Yeonam Tester RAG AI Server", lifespan=lifespan)

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
    mock_rag = os.getenv("MOCK_RAG", "true").lower() == "true"
    
    if mock_rag:
        logger.info("MOCK_RAG is enabled, skipping preprocessing document download/validation.")
        return {"status": "success", "fileId": req.fileId, "valid": True}
        
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
    mock_rag = os.getenv("MOCK_RAG", "true").lower() == "true"
    if mock_rag:
        logger.info("MOCK_RAG is enabled, skipping vector deletion.")
        return {"status": "success", "message": f"Vectors for file {fileId} deleted (mocked)."}
        
    try:
        vector_db_manager.delete_file_chunks(fileId)
        return {"status": "success", "message": f"Vectors for file {fileId} deleted."}
    except Exception as e:
        logger.error(f"Failed to delete vectors for file {fileId}: {str(e)}")
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"status": "error", "message": f"벡터 삭제 오류: {str(e)}"}
        )

@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "mock_llm": os.getenv("MOCK_LLM", "true").lower() == "true",
        "mock_rag": os.getenv("MOCK_RAG", "true").lower() == "true",
        "queue_size": queue_manager.queue.qsize()
    }
