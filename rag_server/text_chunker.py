import re
import uuid
import logging

logger = logging.getLogger("rag_server.text_chunker")

def clean_text(text: str) -> str:
    """
    Cleans document text by removing noise, extra whitespaces,
    and merging single/double character items with surrounding text.
    """
    # Remove continuous empty lines or whitespaces
    text = re.sub(r'\n\s*\n', '\n\n', text)
    
    # Remove page boundary markers introduced by parser if we don't need them
    text = re.sub(r'--- Page \d+ ---\n?', '', text)
    
    lines = text.split('\n')
    cleaned_lines = []
    
    for line in lines:
        stripped = line.strip()
        # Filter out header/footer patterns like page numbers at the bottom (e.g. "Page 1 of 5", "1 / 5", or lone numbers)
        if re.match(r'^\d+\s*/\s*\d+$', stripped) or re.match(r'^Page \d+$', stripped) or re.match(r'^\d+$', stripped):
            continue
        # Merge very short standalone line items (1-2 characters) to avoid fragmenting
        if len(stripped) > 0:
            cleaned_lines.append(stripped)
            
    return "\n".join(cleaned_lines)

def detect_section_title(line: str) -> str:
    """
    Detects if a line is a section or chapter header.
    """
    # Markdown headers, numbered list headers (e.g. 1. Intro, 제 1 장, 1.1 개요)
    line = line.strip()
    if line.startswith('#') or re.match(r'^(제\s*\d+\s*[장절]|I+|V|X|\d+(\.\d+)*)\b', line):
        # Limit title length to prevent capturing long lines
        if len(line) < 100:
            return line
    return None

def chunk_document(raw_text: str, file_id: str, file_name: str) -> list:
    """
    Chunks text semantically with overlap and attaches metadata.
    Chunk size: 500-1000 characters. Overlap: 100-200 characters.
    """
    cleaned_text = clean_text(raw_text)
    paragraphs = cleaned_text.split('\n\n')
    
    chunks = []
    current_chunk = ""
    current_section = "General"
    
    chunk_size_min = 500
    chunk_size_max = 1000
    overlap_size = 150
    
    for para in paragraphs:
        para = para.strip()
        if not para:
            continue
            
        # Detect section title updates
        lines = para.split('\n')
        if lines:
            detected_title = detect_section_title(lines[0])
            if detected_title:
                current_section = detected_title
                
        # If adding this paragraph exceeds max size, we finalize current chunk
        if len(current_chunk) + len(para) > chunk_size_max:
            if current_chunk:
                chunk_id = f"CHNK-{uuid.uuid4().hex[:8].upper()}"
                chunks.append({
                    "chunk_id": chunk_id,
                    "file_id": file_id,
                    "file_name": file_name,
                    "section_title": current_section,
                    "text": current_chunk.strip()
                })
                # Set next chunk starting with overlap from current chunk
                overlap_start = max(0, len(current_chunk) - overlap_size)
                current_chunk = current_chunk[overlap_start:] + "\n\n" + para
            else:
                # If a single paragraph is too large, split it by characters
                para_start = 0
                while para_start < len(para):
                    sub_text = para[para_start : para_start + chunk_size_max]
                    chunk_id = f"CHNK-{uuid.uuid4().hex[:8].upper()}"
                    chunks.append({
                        "chunk_id": chunk_id,
                        "file_id": file_id,
                        "file_name": file_name,
                        "section_title": current_section,
                        "text": sub_text.strip()
                    })
                    para_start += (chunk_size_max - overlap_size)
                current_chunk = ""
        else:
            if current_chunk:
                current_chunk += "\n\n" + para
            else:
                current_chunk = para
                
    # Add any remaining text as the final chunk
    if current_chunk.strip():
        chunk_id = f"CHNK-{uuid.uuid4().hex[:8].upper()}"
        chunks.append({
            "chunk_id": chunk_id,
            "file_id": file_id,
            "file_name": file_name,
            "section_title": current_section,
            "text": current_chunk.strip()
        })
        
    logger.info(f"Generated {len(chunks)} chunks for file {file_name} ({file_id})")
    return chunks
