# 🐍 Yeonam Tester MVP - AI Server (FastAPI)

연암 테스터(Yeonam Tester) MVP의 비동기 문서 분석 및 AI 테스트 케이스/보고서 생성을 전담하는 경량 FastAPI 백앤드 서비스 모듈입니다.

---

## 🛠️ 기술 스택 (Tech Stack)

- **Core Framework**: FastAPI (Uvicorn)
- **Language**: Python 3.10+
- **LLM Gateway**: LiteLLM (Standardized OpenAI/Anthropic call interface)
- **Object Storage Client**: boto3 (MinIO S3 API 연동)
- **Document Parsers**: pypdf, python-docx

---

## 📂 주요 디렉토리 구조 (Directory Structure)

- `main.py`: FastAPI API 엔드포인트 라우팅 및 Pydantic 데이터 검증 스키마 정의
- `queue_manager.py`: `asyncio.Queue` 기반의 비동기 메모리 큐(Queue) 및 순차 백그라운드 워커 태스크
- `document_parser.py`: S3(MinIO) 스토리지에서 문서를 임시 수집하여 텍스트를 추출(PDF, DOCX, MD, TXT 지원)하는 유틸리티
- `llm_client.py`: LiteLLM 연동 및 오프라인 데모 가상 응답을 제어하는 Mock LLM 클라이언트 모듈
- `result_formatter.py`: LLM이 리턴한 문자열 내 JSON 블록 정제 및 스키마 필드 강제 보정용 포맷터
- `webhook_sender.py`: 완성된 분석 결과 데이터 및 진행 상황(Progress) 상태 코드를 Spring Boot 백엔드로 웹훅 호출하는 비동기 HTTP 클라이언트

---

## 🌟 최근 업데이트 및 확장 스펙

1. **동적 API Key 주입 파이프라인 연동**
   - `main.py` 내 API 스키마인 `TriggerRequest` 클래스에 `llmApiKey: Optional[str] = None` 속성이 추가되었습니다.
   - 큐에 작업을 수집할 때 해당 API Key를 직렬화하여 함께 저장하도록 백그라운드 워커 큐를 확장했습니다.
   - `llm_client.py`의 `call_llm` 메서드가 작업 수신 시 넘어온 API Key를 기반으로 **`litellm.acompletion` 호출 시 API Key를 동적으로 맵핑**하여 작동하도록 개선되었습니다.
   - 키가 주입되지 않았을 경우, 디폴트 방식으로 로컬 서버 환경변수(`OPENAI_API_KEY`)를 안전하게 참조하도록 이중 폴백 처리하여 로컬 개발 편의성을 지속 보장합니다.

---

## 🚀 로컬 실행 가이드

### 1. 가상환경 설정 및 의존성 패키지 설치
`llm_server` 폴더 내에서 다음 명령어를 실행하여 Python 가상환경을 활성화하고 의존성을 구축합니다.

```bash
cd llm_server

# 가상환경 생성 (Windows)
python -m venv venv

# 가상환경 활성화 (Windows PowerShell)
.\venv\Scripts\Activate.ps1

# 가상환경 활성화 (macOS/Linux)
source venv/bin/activate

# 의존성 패키지 설치
pip install -r requirements.txt
```

### 2. 환경변수 설정
`.env.example` 파일을 복사하여 `.env` 파일을 생성합니다.

```env
MOCK_LLM=true                     # 로컬 E2E 테스트 및 오프라인 데모 시 true 설정 권장
BACKEND_URL=http://localhost:8080 # 백엔드 웹훅 수신 포트
LLM_MODEL=gpt-4o-mini
# OPENAI_API_KEY=your_key_here    # MOCK_LLM=false 인 경우 로컬 환경 변수 입력 가능
```

### 3. FastAPI 서버 실행
포트 `8000` 번을 할당하여 Uvicorn 개발 서버를 구동합니다:
```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 🔌 API 엔드포인트 사양

### 1. 비동기 분석 작업 트리거 API
- **Endpoint**: `POST /api/analysis/trigger` (또는 `POST /analyze`)
- **Headers**: `Content-Type: application/json`
- **Request Body JSON Example**:
```json
{
  "analysisId": "ANL-2026-0001",
  "projectId": "PRJ-2026-0001",
  "s3Paths": ["projects/PRJ-2026-0001/요구사항명세서.pdf"],
  "qaPerspectives": ["SECURITY", "PERFORMANCE"],
  "customPrompt": "JWT 검증 관련 누락 예외 사항을 검토해 줘.",
  "llmApiKey": "sk-proj-..." // (선택) 동적 LLM 호출용 API Key
}
```
- **Response (202 Accepted)**:
```json
{
  "message": "Job accepted and queued for analysis."
}
```

### 2. 헬스 체크 및 가동 상태 확인
- **Endpoint**: `GET /health`
- **Response (200 OK)**:
```json
{
  "status": "healthy",
  "mock_llm": true,
  "queue_size": 0
}
```

---

## 🧪 cURL 수동 연동 검증
AI 서버가 동작 중인 상태에서 `llmApiKey`가 정상적으로 규격에 맞게 적재되는지 아래 cURL로 임의 트리거 테스트를 수행할 수 있습니다.
```bash
curl -X POST "http://localhost:8000/api/analysis/trigger" \
  -H "Content-Type: application/json" \
  -d "{\"analysisId\":\"ANL-TEST-001\",\"projectId\":\"PRJ-TEST-001\",\"s3Paths\":[],\"llmApiKey\":\"sk-fake-custom-key\"}"
```
- 터미널 출력 및 FastAPI 로그에서 `sk-fake-custom-key` 키값이 수신 완료되어 litellm 래퍼 호출 준비 태스크로 전달되는지 디버깅용 스트림으로 손쉽게 관측할 수 있습니다.
