import os
import boto3
import logging
import uuid
import fitz  # PyMuPDF
from botocore.client import Config
from docx import Document

logger = logging.getLogger("rag_server.document_parser")

# S3 Client configuration
s3_endpoint_url = os.getenv("S3_ENDPOINT_URL", "http://localhost:9000")
s3_bucket = os.getenv("S3_BUCKET", "yeonam-documents")
aws_access_key = os.getenv("AWS_ACCESS_KEY_ID", "minioadmin")
aws_secret_key = os.getenv("AWS_SECRET_ACCESS_KEY", "minioadmin")

s3_client = boto3.client(
    "s3",
    endpoint_url=s3_endpoint_url,
    aws_access_key_id=aws_access_key,
    aws_secret_access_key=aws_secret_key,
    config=Config(signature_version="s3v4"),
    region_name="us-east-1"  # dummy region for MinIO
)

def download_file_from_s3(s3_path: str) -> str:
    temp_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "temp")
    os.makedirs(temp_dir, exist_ok=True)
    
    filename = os.path.basename(s3_path)
    local_path = os.path.join(temp_dir, filename)
    
    logger.info(f"Downloading from S3: bucket={s3_bucket}, path={s3_path} -> {local_path}")
    try:
        s3_client.download_file(s3_bucket, s3_path, local_path)
        logger.info(f"Download complete: {local_path}")
        return local_path
    except Exception as e:
        logger.error(f"Failed to download {s3_path} from S3: {str(e)}")
        raise e

def parse_pdf_layout_and_tables(file_path: str) -> str:
    """
    Parses PDF using PyMuPDF, preserving layout flow and converting tables into markdown format.
    """
    logger.info(f"Parsing PDF with layout/tables: {file_path}")
    try:
        doc = fitz.open(file_path)
        combined_text = ""
        
        for page_idx, page in enumerate(doc):
            combined_text += f"\n--- Page {page_idx+1} ---\n"
            
            # Find tables on the page
            tables = []
            try:
                tables = page.find_tables()
            except Exception as te:
                logger.warning(f"find_tables not supported or failed on page {page_idx+1}: {str(te)}")
            
            # Get text blocks: list of (x0, y0, x1, y1, text, block_no, block_type)
            blocks = page.get_text("blocks")
            # Sort blocks by y0 (top to bottom) then x0 (left to right)
            blocks.sort(key=lambda b: (b[1], b[0]))
            
            # Track which blocks are inside tables to avoid duplicate text
            table_bboxes = [t.bbox for t in tables]
            
            # We will render page contents by combining text blocks and tables in order of y0
            content_items = []
            
            # Add tables to content items
            for t in tables:
                try:
                    markdown_table = t.to_markdown()
                    if markdown_table:
                        # Store bbox for ordering
                        content_items.append({
                            "type": "table",
                            "bbox": t.bbox,
                            "content": markdown_table
                        })
                except Exception as e:
                    logger.error(f"Failed to convert table to markdown: {str(e)}")
            
            # Add text blocks that are NOT inside any table
            for block in blocks:
                x0, y0, x1, y1, text, block_no, block_type = block
                
                # Check if block is inside a table
                in_table = False
                for bbox in table_bboxes:
                    # If the block overlaps significantly with a table bbox
                    if x0 >= bbox[0] - 5 and x1 <= bbox[2] + 5 and y0 >= bbox[1] - 5 and y1 <= bbox[3] + 5:
                        in_table = True
                        break
                
                if not in_table:
                    cleaned_text = text.strip()
                    if cleaned_text:
                        content_items.append({
                            "type": "text",
                            "bbox": (x0, y0, x1, y1),
                            "content": cleaned_text
                        })
            
            # Sort all items on page by top y-coordinate
            content_items.sort(key=lambda item: item["bbox"][1])
            
            page_text_list = []
            for item in content_items:
                page_text_list.append(item["content"])
                
            combined_text += "\n\n".join(page_text_list) + "\n"
            
        doc.close()
        return combined_text
    except Exception as e:
        logger.error(f"Error parsing PDF with layout {file_path}: {str(e)}")
        raise e

def parse_docx(file_path: str) -> str:
    logger.info(f"Parsing DOCX: {file_path}")
    try:
        doc = Document(file_path)
        text = []
        for paragraph in doc.paragraphs:
            text.append(paragraph.text)
        return "\n".join(text)
    except Exception as e:
        logger.error(f"Error parsing DOCX {file_path}: {str(e)}")
        raise e

def parse_txt(file_path: str) -> str:
    logger.info(f"Parsing TXT/MD: {file_path}")
    try:
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read()
    except Exception as e:
        logger.error(f"Error parsing text file {file_path}: {str(e)}")
        raise e

def extract_text_from_file(file_path: str) -> str:
    ext = os.path.splitext(file_path)[1].lower()
    if ext == ".pdf":
        return parse_pdf_layout_and_tables(file_path)
    elif ext in [".docx", ".doc"]:
        return parse_docx(file_path)
    elif ext in [".txt", ".md"]:
        return parse_txt(file_path)
    else:
        logger.warning(f"Unsupported file extension {ext}, attempting to read as text.")
        return parse_txt(file_path)

def cleanup_file(file_path: str):
    try:
        if os.path.exists(file_path):
            os.remove(file_path)
            logger.info(f"Cleaned up temp file: {file_path}")
    except Exception as e:
        logger.warning(f"Failed to clean up temp file {file_path}: {str(e)}")

def extract_file_id_from_path(s3_path: str) -> str:
    basename = os.path.basename(s3_path)
    if "_" in basename:
        parts = basename.split("_")
        if len(parts[0]) >= 32 or parts[0].startswith("DOC-"): # UUID or Doc-id
            return parts[0]
    return str(uuid.uuid4())

async def process_and_extract(s3_paths: list) -> list:
    """
    Downloads and extracts text from all given S3 paths.
    Returns a list of dicts: [{"file_id": ..., "file_name": ..., "text": ...}]
    """
    parsed_documents = []
    for path in s3_paths:
        local_path = None
        try:
            local_path = download_file_from_s3(path)
            file_text = extract_text_from_file(local_path)
            file_id = extract_file_id_from_path(path)
            file_name = os.path.basename(path)
            
            parsed_documents.append({
                "file_id": file_id,
                "file_name": file_name,
                "text": file_text
            })
        except Exception as e:
            logger.error(f"Error extracting text from {path}: {str(e)}")
            raise e
        finally:
            if local_path:
                cleanup_file(local_path)
    return parsed_documents
