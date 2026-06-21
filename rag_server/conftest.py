"""Mock heavy ML dependencies before any test imports main."""
import sys
from unittest.mock import MagicMock, AsyncMock

for _mod in ["faiss", "sentence_transformers"]:
    sys.modules.setdefault(_mod, MagicMock())

_qm = MagicMock()
_qm.queue_manager.queue.qsize.return_value = 0
_qm.queue_manager.start_worker = MagicMock()
_qm.queue_manager.stop_worker = AsyncMock()
sys.modules.setdefault("queue_manager", _qm)
sys.modules.setdefault("vector_db_manager", MagicMock())
sys.modules.setdefault("retriever", MagicMock())
