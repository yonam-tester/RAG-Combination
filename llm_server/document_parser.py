import os
import boto3
import logging
from botocore.client import Config
from pypdf import PdfReader
from docx import Document

logger = logging.getLogger("llm_server.document_parser")

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

# In-memory storage for parsed texts if needed (optional)
analysis_texts = {}

def download_file_from_s3(s3_path: str) -> str:
    """
    Downloads file from S3 bucket to a local temp file.
    Returns the path to the downloaded local file.
    """
    # Create a temp directory inside the project if it doesn't exist
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

def parse_pdf(file_path: str) -> str:
    logger.info(f"Parsing PDF: {file_path}")
    try:
        reader = PdfReader(file_path)
        text = ""
        for i, page in enumerate(reader.pages):
            page_text = page.extract_text()
            if page_text:
                text += f"\n--- Page {i+1} ---\n" + page_text
        return text
    except Exception as e:
        logger.error(f"Error parsing PDF {file_path}: {str(e)}")
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
        return parse_pdf(file_path)
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

async def process_and_extract(s3_paths: list) -> str:
    """
    Downloads and extracts text from all given S3 paths.
    Concatenates and returns the aggregated text.
    """
    combined_text = ""
    for path in s3_paths:
        local_path = None
        try:
            local_path = download_file_from_s3(path)
            file_text = extract_text_from_file(local_path)
            combined_text += f"\n\n=== Document: {os.path.basename(path)} ===\n\n" + file_text
        except Exception as e:
            logger.error(f"Error extracting text from {path}: {str(e)}")
            raise e
        finally:
            if local_path:
                cleanup_file(local_path)
    return combined_text
