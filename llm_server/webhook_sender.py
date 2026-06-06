import os
import httpx
import logging
import asyncio

logger = logging.getLogger("llm_server.webhook_sender")

async def send_callback(analysis_id: str, payload: dict) -> bool:
    """
    Sends the analysis results back to the Spring Boot backend via webhook.
    Implements a retry mechanism with exponential backoff.
    """
    backend_url = os.getenv("BACKEND_URL", "http://localhost:8080")
    callback_url = f"{backend_url}/api/internal/analysis/{analysis_id}/callback"
    
    max_retries = 3
    backoff = 1.0  # seconds
    
    logger.info(f"Sending callback to {callback_url} for analysis {analysis_id}...")
    
    async with httpx.AsyncClient() as client:
        for attempt in range(1, max_retries + 1):
            try:
                response = await client.post(
                    callback_url, 
                    json=payload, 
                    timeout=10.0
                )
                if response.status_code == 200:
                    logger.info(f"Webhook callback successful for {analysis_id} on attempt {attempt}")
                    return True
                else:
                    logger.warning(
                        f"Webhook callback failed with status code {response.status_code} "
                        f"on attempt {attempt}. Response: {response.text}"
                    )
            except Exception as e:
                logger.error(f"Error sending webhook on attempt {attempt}: {str(e)}")
            
            if attempt < max_retries:
                logger.info(f"Waiting {backoff} seconds before retry...")
                await asyncio.sleep(backoff)
                backoff *= 2
                
    logger.error(f"Failed to send webhook callback for {analysis_id} after {max_retries} attempts.")
    return False

async def send_failure_callback(analysis_id: str, error_message: str):
    """
    Helper function to send a FAILED status callback if parsing or LLM run fails.
    """
    payload = {
        "status": "FAILED",
        "errorMessage": error_message,
        "summary": "AI 분석 실패",
        "testCases": [],
        "missingItems": []
    }
    await send_callback(analysis_id, payload)
