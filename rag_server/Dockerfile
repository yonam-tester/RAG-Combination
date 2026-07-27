FROM python:3.10-slim
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 임베딩 수행을 위해 프로덕션에서도 LangChain/sentence-transformers가 필요하다.
# CUDA 휠(수 GB)을 피하기 위해 CPU 전용 torch 인덱스를 함께 지정한다.
COPY requirements.prod.txt .
RUN pip install --no-cache-dir \
    --extra-index-url https://download.pytorch.org/whl/cpu \
    -r requirements.prod.txt

COPY . .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
