import asyncio
import logging

logger = logging.getLogger("llm_server.queue_manager")

class QueueManager:
    def __init__(self):
        self.queue = asyncio.Queue()
        self.worker_task = None
        self.running = False

    async def add_job(self, job_data: dict):
        await self.queue.put(job_data)
        logger.info(f"Job added to queue: {job_data.get('analysisId')}. Current queue size: {self.queue.qsize()}")

    def start_worker(self, worker_func):
        if not self.running:
            self.running = True
            self.worker_task = asyncio.create_task(self._worker_loop(worker_func))
            logger.info("Background worker started.")

    async def stop_worker(self):
        if self.running:
            self.running = False
            if self.worker_task:
                self.worker_task.cancel()
                try:
                    await self.worker_task
                except asyncio.CancelledError:
                    pass
            logger.info("Background worker stopped.")

    async def _worker_loop(self, worker_func):
        while self.running:
            try:
                # Wait for a job with timeout so cancel check can run periodically
                try:
                    job_data = await asyncio.wait_for(self.queue.get(), timeout=1.0)
                except asyncio.TimeoutError:
                    continue

                logger.info(f"Processing job from queue: {job_data.get('analysisId')}")
                try:
                    await worker_func(job_data)
                except Exception as e:
                    logger.error(f"Error processing job {job_data.get('analysisId')}: {str(e)}", exc_info=True)
                finally:
                    self.queue.task_done()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in worker loop: {str(e)}", exc_info=True)
                await asyncio.sleep(1)

# Global singleton
queue_manager = QueueManager()
