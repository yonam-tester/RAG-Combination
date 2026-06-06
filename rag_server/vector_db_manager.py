import os
import logging
from typing import List, Dict

logger = logging.getLogger("rag_server.vector_db_manager")

# Try to import FAISS and SentenceTransformers; fall back to mock if not available.
HAS_FAISS = False
try:
    import faiss
    from sentence_transformers import SentenceTransformer
    import numpy as np
    HAS_FAISS = True
except Exception as e:
    logger.warning(f"Error loading FAISS or sentence-transformers ({str(e)}). Local Vector DB will operate in MOCK / String-Matching fallback mode.")

class VectorDBManager:
    def __init__(self):
        self.chunks: List[Dict] = []
        self.provider = os.getenv("EMBEDDING_PROVIDER", "local").lower()
        self.model_name = os.getenv("EMBEDDING_MODEL", "all-MiniLM-L6-v2")
        self.embedding_model = None
        self.faiss_index = None
        
        # Load local embedding model if needed and library is present
        if HAS_FAISS and self.provider == "local" and os.getenv("MOCK_RAG", "true").lower() != "true":
            try:
                logger.info(f"Loading local embedding model: {self.model_name}...")
                self.embedding_model = SentenceTransformer(self.model_name)
                logger.info("Local embedding model loaded successfully.")
            except Exception as e:
                logger.error(f"Failed to load sentence-transformer model '{self.model_name}': {str(e)}. Operating in fallback mode.")
                self.embedding_model = None

    def add_chunks(self, new_chunks: List[Dict]):
        """
        Adds text chunks to the database and builds/updates the FAISS index.
        """
        # Append new chunks (avoiding duplicates)
        existing_chunk_ids = {c["chunk_id"] for c in self.chunks}
        for chunk in new_chunks:
            if chunk["chunk_id"] not in existing_chunk_ids:
                self.chunks.append(chunk)
                
        logger.info(f"Total chunks registered: {len(self.chunks)}")
        
        # Build index if local provider is active, not in mock, and model loaded
        if self.provider == "local" and self.embedding_model is not None and HAS_FAISS:
            self._rebuild_faiss_index()
        else:
            logger.info("Using mock/bedrock or fallback vector store indexing. Skipped FAISS index rebuild.")

    def delete_file_chunks(self, file_id: str):
        """
        Deletes all chunks associated with a specific file_id.
        Rebuilds index after removal.
        """
        original_count = len(self.chunks)
        self.chunks = [c for c in self.chunks if c["file_id"] != file_id]
        deleted_count = original_count - len(self.chunks)
        logger.info(f"Deleted {deleted_count} chunks matching file_id {file_id}. Remaining chunks: {len(self.chunks)}")
        
        if self.provider == "local" and self.embedding_model is not None and HAS_FAISS:
            self._rebuild_faiss_index()

    def _rebuild_faiss_index(self):
        """
        Rebuilds the FAISS index from the current chunks list.
        """
        if not self.chunks:
            self.faiss_index = None
            return
            
        try:
            logger.info("Rebuilding FAISS index...")
            texts = [c["text"] for c in self.chunks]
            # Generate embeddings
            embeddings = self.embedding_model.encode(texts, show_progress_bar=False)
            embeddings = np.array(embeddings).astype('float32')
            
            # Create a simple IndexFlatIP (Inner Product) index for Cosine Similarity (requires normalized embeddings)
            norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
            normalized_embeddings = embeddings / (norms + 1e-10)
            
            dimension = normalized_embeddings.shape[1]
            self.faiss_index = faiss.IndexFlatIP(dimension)
            self.faiss_index.add(normalized_embeddings)
            logger.info(f"FAISS index rebuilt successfully with {self.faiss_index.ntotal} vectors.")
        except Exception as e:
            logger.error(f"Error rebuilding FAISS index: {str(e)}", exc_info=True)
            self.faiss_index = None

    def search(self, query: str, top_k: int = 5) -> List[Dict]:
        """
        Searches the vector database for relevant chunks matching the query.
        Returns a list of chunk dicts with a 'score' key.
        """
        if not self.chunks:
            logger.warning("Search requested but chunk registry is empty.")
            return []
            
        # 1. AWS Bedrock Knowledge Base Stub Flow
        if self.provider == "bedrock":
            logger.info(f"Querying AWS Bedrock Knowledge Base: '{query}' (stubbed)...")
            # Returns a simulated bedrock response using local chunks matching query text
            return self._fallback_string_search(query, top_k)
            
        # 2. Local FAISS Search Flow
        if self.faiss_index is not None and self.embedding_model is not None and HAS_FAISS:
            try:
                # Encode query and normalize
                query_vector = self.embedding_model.encode([query], show_progress_bar=False)
                query_vector = np.array(query_vector).astype('float32')
                norm = np.linalg.norm(query_vector)
                if norm > 0:
                    query_vector = query_vector / norm
                    
                # Search
                scores, indices = self.faiss_index.search(query_vector, min(top_k, len(self.chunks)))
                
                results = []
                for score, idx in zip(scores[0], indices[0]):
                    if idx < 0 or idx >= len(self.chunks):
                        continue
                    chunk = self.chunks[idx].copy()
                    chunk["score"] = float(score)
                    results.append(chunk)
                return results
            except Exception as e:
                logger.error(f"Error searching FAISS index: {str(e)}, falling back to string search.")
                
        # 3. Fallback / Mock String Matching Search
        return self._fallback_string_search(query, top_k)

    def _fallback_string_search(self, query: str, top_k: int) -> List[Dict]:
        """
        Simple keyword/word-match overlapping search for fallback when FAISS/Embeddings are disabled.
        """
        query_words = set(query.lower().split())
        results = []
        
        for chunk in self.chunks:
            chunk_text_lower = chunk["text"].lower()
            # Calculate simple word overlap score
            match_count = sum(1 for word in query_words if word in chunk_text_lower)
            # Normalize score between 0.0 and 1.0
            score = match_count / max(len(query_words), 1)
            # Add a small base score if there is any match
            if score > 0:
                score = 0.5 + (score * 0.5)
            else:
                score = 0.0
                
            chunk_copy = chunk.copy()
            chunk_copy["score"] = score
            results.append(chunk_copy)
            
        # Sort by score descending and take top_k
        results = [r for r in results if r["score"] > 0]
        results.sort(key=lambda x: x["score"], reverse=True)
        return results[:top_k]

# Global singleton
vector_db_manager = VectorDBManager()
