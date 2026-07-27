# RAG 파이프라인 LangChain + Qdrant 리팩토링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `rag_server`의 자체 구현 FAISS 인메모리 벡터 파이프라인을 LangChain 표준 컴포넌트 + Qdrant(외부 영속 벡터DB)로 교체하고, `MOCK_RAG` 폴백 경로를 완전히 제거한다.

**Architecture:** 벡터 인덱스를 `rag-server` 프로세스 메모리에서 독립 Qdrant 컨테이너(named volume 영속)로 이전한다. 문서 청크와 정적 QA 지식카드를 `yeonam_knowledge` 단일 컬렉션에 `source_type` payload로 구분해 저장하고, 검색은 `QdrantVectorStore` 한 번의 유사도 호출로 통합한다. LLM 호출은 LCEL 체인(`litellm.acompletion → JsonOutputParser → 필드 보정`)으로 재구성하되 외부 API 계약(webhook payload, 엔드포인트 응답 스키마)은 변경하지 않는다.

**Tech Stack:** Python 3.10 / FastAPI / LangChain (`langchain-core`, `langchain-text-splitters`, `langchain-qdrant`, `langchain-huggingface`) / Qdrant / sentence-transformers (`all-MiniLM-L6-v2`) / litellm / pytest / Docker Compose

---

## 설계 명세서 대비 의도적 편차

원 설계 명세서(`docs/superpowers/specs/2026-07-25-langchain-qdrant-rag-refactor-design.md`) 작성 이후 확인된 사실로 인해 아래 3건은 명세서와 다르게 구현한다. 명세서의 **의도**(ChatLiteLLM 사용, 단일 retriever 호출, Qdrant 없이 단위 테스트)는 모두 보존된다.

| # | 명세서 | 이 계획 | 사유 |
|---|---|---|---|
| 1 | LCEL 체인의 LLM 단계로 `ChatLiteLLM`(`langchain-community`) 사용 | `ChatLiteLLM`을 쓰지 않고 `litellm.acompletion`을 `RunnableLambda`로 감싸 체인에 넣는다 | 두 단계로 확인된 문제다. ① `langchain-community` 저장소는 2026-06-19 아카이브되어 sunset 되었고 `ChatLiteLLM`은 `langchain-litellm`으로 이관됐다. ② 그런데 `langchain-litellm`은 전 버전이 `litellm>=1.65.1`, 0.5.0+ 는 `httpx>=0.28.1`을 요구하는데 이 프로젝트는 `litellm==1.40.11`/`httpx==0.27.0`에 고정돼 있다(pip `ResolutionImpossible`로 확인). 이를 맞추려면 litellm을 37개 마이너 버전 점프시켜야 하고, litellm을 직접 호출하는 범위 밖 모듈 3개(`document_parser.py`, `requirement_extractor.py`, `llm_server`)와 `litellm.exceptions.AuthenticationError` 처리까지 재검증해야 한다. `RunnableLambda` 래핑은 LCEL 체인 구조(프롬프트 → LLM → `JsonOutputParser` → 필드 보정)와 설계 의도를 그대로 유지하면서 기존 핀을 하나도 건드리지 않는다(해상도 검증 완료). 잃는 것은 `ChatLiteLLM` 클래스의 streaming/callbacks인데, 이 용도(단발 비스트리밍 JSON 호출)에는 쓰이지 않는다. **사용자 승인 후 진행(2026-07-27).** |
| 2 | `as_retriever(search_kwargs={"k":5,"score_threshold":0.35})` | `similarity_search_with_score(query, k=...)` 후 파이썬 측에서 threshold 필터 | `as_retriever`는 `List[Document]`만 반환해 score를 잃는다. 그런데 score는 기존 외부 계약(`evidence_list[].score`, `pipelineTrace[].topScore`, `build_prompt`의 `<evidence score=...>`)에 그대로 노출되므로 반드시 보존해야 한다. `similarity_score_threshold` retriever가 내부적으로 호출하는 것과 동일한 API를 직접 쓰는 것이라 검색 의미론은 동일하다. |
| 3 | 단위 테스트에 `InMemoryVectorStore` + `FakeEmbeddings` | `QdrantClient(location=":memory:")` 로컬 모드 + `DeterministicFakeEmbedding` | `qdrant-client`는 서버 없이 동작하는 로컬 모드를 내장한다. `InMemoryVectorStore`와 달리 payload 필터 삭제(`delete_by_file_id`)까지 실제 코드 경로로 검증할 수 있어 커버리지가 넓다. 컨테이너 불필요라는 명세서의 목적은 동일하게 달성된다. |
| 4 | payload 필드명 `source_name` | payload 필드명 `file_name` | 기존 `text_chunker.chunk_document`가 이미 `file_name` 키를 쓰고 있어 그대로 잇는 편이 마이그레이션이 단순하다. evidence dict로 나갈 때 `source_name`으로 매핑되므로 **외부 계약은 명세서 그대로**다. 컬렉션 payload 내부 이름만 다르다. |
| 5 | (명세서에 언급 없음) | 로컬 개발 venv를 **Python 3.12**로 만든다 (프로덕션 의존성은 무변경) | 개발 머신의 기본 `python3`가 3.13인데, 이 프로젝트의 2024년대 핀들(`pydantic==2.7.4`, `pymupdf==1.24.5`)은 cp313 휠이 없어 소스 빌드로 넘어가 실패한다. 핀을 하나씩 올리면 프로덕션 의존성이 계속 바뀌므로 대신 인터프리터를 맞췄다(`brew install python@3.12`). 3.12에서는 **기존 핀 전부가 수정 없이 설치·import된다**(검증 완료). 결과적으로 이 리팩토링은 프로덕션 의존성을 하나도 바꾸지 않는다. **사용자 승인 후 진행(2026-07-27).** |

## 운영 리스크 (구현 전 반드시 인지할 것)

**프로덕션 메모리 증가 — Task 2에서 정면으로 다룬다.**

`rag_server/Dockerfile`은 `requirements.prod.txt`를 사용하며, 이 파일은 `# faiss/sentence-transformers 제외한 경량 requirements 사용` 주석이 명시하듯 EC2 메모리 절약을 위해 ML 라이브러리를 **의도적으로 배제**해 왔다. 그 결과 현재 프로덕션 rag-server는 실벡터 검색을 한 번도 수행한 적이 없고 항상 `_fallback_string_search`(단어 오버랩 문자열 매칭)로 조용히 폴백해 왔다.

이번 리팩토링은 "Qdrant + 로컬 임베딩을 항상 사용"이 전제이므로 `requirements.prod.txt`에 `langchain-huggingface`(→ `sentence-transformers` → `torch`)가 반드시 들어가야 하며, 이는 그 배제 결정을 되돌린다. Task 2는 CPU 전용 torch 휠(`--extra-index-url https://download.pytorch.org/whl/cpu`)로 이미지 크기를 억제하고 실제 증가분을 측정해 기록한다. 측정 후에도 EC2 인스턴스가 감당하지 못하면 후속 조치는 **임베딩을 `fastembed`(ONNX 런타임, torch 불필요)로 교체**하는 것이며, 이는 이번 범위 밖이다.

> **측정 결과(2026-07-27):** rag-server 프로덕션 이미지 크기 리팩토링 전 `958MB` → 후 `1.59GB`. 증가분 `약 632MB`은 대부분 CPU 전용 torch + sentence-transformers 전이 의존이다. 이미지 크기는 대리 지표일 뿐이며, EC2 OOM 위험의 실제 지표는 임베딩 모델 로드 후 런타임 RSS다 — Task 10의 전체 스택 기동 검증에서 확인할 것.

## Global Constraints

- 임베딩 모델은 기존과 동일한 `all-MiniLM-L6-v2`(384차원)를 유지한다. 벡터 차원 상수 `EMBEDDING_DIM = 384`.
- Qdrant 컬렉션 이름은 `yeonam_knowledge`, 거리 척도는 `COSINE`, 벡터는 이름 없는(unnamed) 단일 벡터.
- 외부 API 계약 불변: `/analyze`, `/api/analysis/trigger`, `/api/files/preprocess`, `/api/vectors/{fileId}`, `/api/eval/generate`, `/metrics`의 요청·응답 스키마와 webhook callback payload 구조는 변경하지 않는다. `/health` 응답 바디만 예외적으로 변경한다(아래).
- `evidence` dict 스키마 불변: `{"chunk_id": str, "text": str, "source_name": str, "source_section": str, "score": float}`.
- `MOCK_RAG` 환경변수와 그에 딸린 모든 분기·하드코딩 mock 데이터를 코드베이스에서 완전히 제거한다. `MOCK_LLM`은 그대로 유지하며 손대지 않는다.
- 조용한 폴백 금지: Qdrant 연결 실패·임베딩 로드 실패는 예외를 전파하거나 기동을 실패시킨다. 문자열 매칭 폴백(`_fallback_string_search`)은 부활시키지 않는다.
- Qdrant point ID는 부호 없는 정수 또는 UUID만 허용된다. 사람이 읽는 `chunk_id`(`CHNK-...`, `QA-0001`)는 `uuid.uuid5(CHUNK_NAMESPACE, chunk_id)`로 변환해 point ID로 쓴다. 이로써 재적재가 항상 upsert(멱등)가 된다.
- `QdrantVectorStore`의 기본 payload 레이아웃을 따른다: 본문은 `page_content`, 메타데이터는 `metadata` 하위. 따라서 payload 필터 키는 `metadata.file_id` 형태다.
- 모든 커밋 메시지는 Conventional Commits(`feat:`, `refactor:`, `test:`, `chore:`, `ci:`) + 한국어 본문.
- 테스트 실행 디렉터리는 항상 `rag_server/`이다(모듈들이 top-level import를 쓰기 때문). 명시된 `cd rag_server` 를 생략하지 말 것.
- 로컬 개발 venv는 **Python 3.12**(`/opt/homebrew/opt/python@3.12/bin/python3.12`)로 만든다. 시스템 기본 `python3`(3.13)에서는 `pydantic==2.7.4`/`pymupdf==1.24.5`가 cp313 휠 부재로 설치되지 않는다. **기존 프로덕션 핀은 하나도 바꾸지 않는다.**
- 이 저장소의 셸 환경은 불안정하다: `cd`가 호출 간에 유지되므로 항상 절대 경로를 쓰고, 평범한 명령도 간헐적으로 멈추거나 SIGKILL될 수 있으니 타임아웃 시 단순한 형태로 재시도한다.

---

## File Structure

| 파일 | 변경 | 책임 |
|---|---|---|
| `docker-compose.yml` | 수정 | `qdrant` 서비스 + `qdrant_data` 볼륨 추가, `rag-server`에 `depends_on` |
| `.env.example` | 수정 | `MOCK_RAG` 제거, `QDRANT_URL`/`QDRANT_COLLECTION`/`EMBEDDING_MODEL` 추가 |
| `rag_server/requirements.txt` | 수정 | faiss/sentence-transformers 직접 의존 제거, LangChain 스택 + pytest 추가 |
| `rag_server/requirements.prod.txt` | 수정 | 위와 동일 스택 추가(프로덕션도 실임베딩 수행) |
| `rag_server/Dockerfile` | 수정 | CPU 전용 torch 인덱스 지정 |
| `rag_server/vector_store.py` | **신규** | Qdrant 클라이언트/임베딩 수명주기, 컬렉션 보증, add/delete/search/ping. 벡터DB에 대한 **유일한** 접근 지점 |
| `rag_server/ingestion.py` | **신규** | 정적 지식(`rag_chunks.jsonl`, `knowledge_base/*.json`)을 Document로 로드해 기동 시 Qdrant에 멱등 적재 |
| `rag_server/text_chunker.py` | 수정 | 한국어 전처리(`clean_text`/`detect_section_title`) 유지 + 범용 분할을 `RecursiveCharacterTextSplitter`로 교체, `List[Document]` 반환 |
| `rag_server/retriever.py` | 수정 | `vector_store.search()` 단일 호출 + `exclude_chunk_ids` 후처리로 축소. 지식카드 로딩 책임은 `ingestion.py`로 이관 |
| `rag_server/prompt_builder.py` | 수정 | `build_prompt`(불변) + LCEL 체인(`litellm 호출 → JsonOutputParser → 필드 보정`) |
| `rag_server/main.py` | 수정 | `vector_store` 참조로 교체, `MOCK_RAG` 분기 전면 제거, lifespan에 ingestion, `/health`에 Qdrant 검증 |
| `rag_server/vector_db_manager.py` | **삭제** | `vector_store.py`가 대체 |
| `rag_server/conftest.py` | 수정 | 로컬 모드 Qdrant + `DeterministicFakeEmbedding` 픽스처 |
| `rag_server/test_vector_store.py` | **신규** | `vector_store` 단위 테스트 |
| `rag_server/test_text_chunker.py` | **신규** | 청킹 단위 테스트 |
| `rag_server/test_ingestion.py` | **신규** | 지식카드 로딩/적재 단위 테스트 |
| `rag_server/test_retriever.py` | **신규** | 검색·중복제외 단위 테스트 |
| `rag_server/test_prompt_builder.py` | **신규** | LCEL 체인/파싱/필드 보정 단위 테스트 |
| `rag_server/test_basic.py` | 수정 | `/health` 계약 변경 반영 |
| `.github/workflows/rag-tests.yml` | **신규** | Qdrant 서비스 컨테이너 위에서 pytest 실행 |

---

## Task 1: Qdrant 컨테이너 및 환경변수 도입

**Files:**
- Modify: `docker-compose.yml:32-45` (rag-server), `docker-compose.yml:157-161` (volumes)
- Modify: `.env.example:41-46`
- Modify: `rag_server/.env.example` (rag_server 전용 env 템플릿 — 루트 `.env.example`과 별개로 존재한다)

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `http://qdrant:6333` (컨테이너 네트워크 내부), `QDRANT_URL` / `QDRANT_COLLECTION` / `EMBEDDING_MODEL` 환경변수 이름. 이후 모든 태스크가 이 이름을 참조한다.

- [ ] **Step 1: Qdrant 이미지 태그가 실제로 존재하는지 확인**

Run:
```bash
docker pull qdrant/qdrant:v1.12.4
```
Expected: `Status: Downloaded newer image for qdrant/qdrant:v1.12.4` 또는 `Status: Image is up to date`.

이 태그를 못 받으면 https://hub.docker.com/r/qdrant/qdrant/tags 에서 최신 `v1.x.y` 안정 태그를 확인해 아래 모든 단계의 `v1.12.4`를 그 값으로 치환한다. 이 경우 Task 2의 `qdrant-client` 하한도 같은 마이너 버전으로 맞춘다.

- [ ] **Step 2: `docker-compose.yml`에 qdrant 서비스 추가**

`rag-server` 서비스 블록(32-45행) 바로 앞에 아래를 삽입한다:

```yaml
  qdrant:
    image: qdrant/qdrant:v1.12.4
    container_name: yeonam-qdrant
    expose:
      - "6333"
      - "6334"
    volumes:
      - qdrant_data:/qdrant/storage
    networks:
      - yeonam-network
    restart: always
    labels:
      app: yeonam
      component: qdrant
      phase: "1"

```

- [ ] **Step 3: `rag-server`가 qdrant를 기다리도록 `depends_on` 추가**

`rag-server` 블록에서 `networks:` 바로 위에 삽입:

```yaml
    depends_on:
      - qdrant
```

수정 후 `rag-server` 블록 전체는 다음과 같아야 한다:

```yaml
  rag-server:
    build:
      context: ./rag_server
      dockerfile: Dockerfile
    container_name: yeonam-rag
    ports:
      - "8000:8000"
    expose:
      - "8000"
    env_file:
      - .env
    depends_on:
      - qdrant
    networks:
      - yeonam-network
    restart: always
```

- [ ] **Step 4: named volume 등록**

파일 최하단 `volumes:` 블록을 다음으로 교체:

```yaml
volumes:
  backend_data:
  minio_data:
  prometheus_data:
  grafana_data:
  qdrant_data:
```

- [ ] **Step 5: compose 파일 문법 검증**

Run:
```bash
docker compose config --quiet && echo COMPOSE_OK
```
Expected: `COMPOSE_OK` (경고 없이). `.env` 파일이 없다는 오류가 나면 `cp .env.example .env` 후 재실행한다.

- [ ] **Step 6: Qdrant 단독 기동 및 헬스 확인**

Run:
```bash
docker compose up -d qdrant
sleep 5
curl -sf http://localhost:6333/healthz && echo QDRANT_OK
```

`qdrant` 서비스는 `expose`만 하므로 호스트에서 6333이 안 열려 있다. 위 curl이 실패하면 컨테이너 내부로 확인한다:

```bash
docker compose exec qdrant sh -c "wget -qO- http://localhost:6333/healthz" && echo QDRANT_OK
```
Expected: `healthz check passed` 뒤에 `QDRANT_OK`.

- [ ] **Step 7: `.env.example` 갱신**

`.env.example`의 41-46행(`# RAG 서버 설정` 블록)을 아래로 교체:

```
# ──────────────────────────────────────────────
# RAG 서버 설정
# ──────────────────────────────────────────────
MOCK_LLM=false
BACKEND_URL=http://backend:8080

# ──────────────────────────────────────────────
# 벡터 DB (Qdrant)
# 로컬 단위 테스트에서는 QDRANT_URL=:memory: 사용 가능 (서버 불필요)
# ──────────────────────────────────────────────
QDRANT_URL=http://qdrant:6333
QDRANT_COLLECTION=yeonam_knowledge
EMBEDDING_MODEL=all-MiniLM-L6-v2
```

`MOCK_RAG=false` 줄은 삭제한다.

- [ ] **Step 7b: `rag_server/.env.example` 갱신**

이 파일은 루트 `.env.example`과 별개로 존재하는 rag_server 전용 템플릿이다. 전체를 아래로 교체한다:

```
MOCK_LLM=true
BACKEND_URL=http://localhost:8080
LLM_MODEL=gpt-4o-mini
EMBEDDING_MODEL=all-MiniLM-L6-v2
QDRANT_URL=http://localhost:6333
QDRANT_COLLECTION=yeonam_knowledge
```

두 줄이 사라진다: `MOCK_RAG=true`, 그리고 `EMBEDDING_PROVIDER=local`(삭제 예정인 `vector_db_manager.py`만 읽던 변수로, Task 8 이후 아무도 읽지 않는다).
`QDRANT_URL`이 루트 템플릿의 `http://qdrant:6333`과 달리 `localhost`인 이유: 이 파일은 컨테이너 밖에서 rag_server를 직접 실행할 때 쓰는 템플릿이다.

- [ ] **Step 8: `MOCK_RAG`/`EMBEDDING_PROVIDER`가 설정 파일에 남아있지 않은지 확인**

Run:
```bash
git grep -n "MOCK_RAG\|EMBEDDING_PROVIDER" -- '*.env*' '*.yml' '*.yaml' .github; echo "exit=$?"
```
Expected: 매칭 없음, `exit=1`. (파이썬 소스의 `MOCK_RAG`는 Task 6·7·8에서 제거하므로 여기서는 대상 아님.)

- [ ] **Step 9: 커밋**

```bash
git add docker-compose.yml .env.example
git commit -m "feat: Qdrant 벡터DB 컨테이너 및 환경변수 추가

- qdrant 서비스(v1.12.4) + qdrant_data named volume 추가
- rag-server가 qdrant에 depends_on
- .env.example: MOCK_RAG 제거, QDRANT_URL/QDRANT_COLLECTION/EMBEDDING_MODEL 추가"
```

---

## Task 2: 의존성 스택 교체 및 프로덕션 메모리 영향 측정

**Files:**
- Modify: `rag_server/requirements.txt`
- Modify: `rag_server/requirements.prod.txt`
- Modify: `rag_server/Dockerfile`

**Interfaces:**
- Consumes: 없음
- Produces: 이후 모든 태스크가 import 가능해지는 패키지 — `langchain_core`, `langchain_text_splitters`, `langchain_qdrant.QdrantVectorStore`, `langchain_huggingface.HuggingFaceEmbeddings`, `qdrant_client`. LLM 호출용 별도 LangChain 패키지는 추가하지 않는다 — 기존 `litellm`을 `RunnableLambda`로 감싸 쓴다(의도적 편차 #1 참고).

- [ ] **Step 1: `requirements.txt` 교체**

파일 전체를 아래로 교체한다. `faiss-cpu`/`sentence-transformers` 직접 의존은 제거하되, `sentence-transformers`는 `langchain-huggingface`가 전이 의존으로 끌어온다.

```
fastapi==0.111.0
uvicorn==0.30.1
pydantic==2.7.4
httpx==0.27.0
python-dotenv==1.0.1
boto3==1.34.122
python-multipart==0.0.9
pymupdf==1.24.5
litellm==1.40.11
python-docx==1.1.2
prometheus_client>=0.20.0

# LangChain + Qdrant RAG 스택
langchain-core>=0.3,<2.0
langchain-text-splitters>=0.3,<2.0
langchain-qdrant>=0.2,<2.0
langchain-huggingface>=0.1,<2.0
qdrant-client>=1.12,<2.0

# 테스트
pytest>=8.0
pytest-asyncio>=0.23
```

- [ ] **Step 2: `requirements.prod.txt` 교체**

프로덕션도 실임베딩을 수행해야 하므로 동일 스택을 넣는다. 테스트 의존성만 제외한다.

```
fastapi==0.111.0
uvicorn==0.30.1
pydantic==2.7.4
httpx==0.27.0
python-dotenv==1.0.1
boto3==1.34.122
python-multipart==0.0.9
pymupdf==1.24.5
litellm==1.40.11
python-docx==1.1.2
prometheus_client>=0.20.0

# LangChain + Qdrant RAG 스택
# 주의: langchain-huggingface -> sentence-transformers -> torch 전이 의존이 발생한다.
# Dockerfile에서 CPU 전용 torch 휠 인덱스를 지정해 이미지 크기를 억제한다.
langchain-core>=0.3,<2.0
langchain-text-splitters>=0.3,<2.0
langchain-qdrant>=0.2,<2.0
langchain-huggingface>=0.1,<2.0
qdrant-client>=1.12,<2.0
```

- [ ] **Step 3: `Dockerfile`을 CPU 전용 torch로 고정**

`rag_server/Dockerfile` 전체를 아래로 교체한다. 기존의 `# faiss/sentence-transformers 제외한 경량 requirements 사용` 주석은 더 이상 사실이 아니므로 갱신한다.

```dockerfile
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
```

- [ ] **Step 4: 로컬 가상환경에 설치**

Run:
```bash
cd rag_server && /opt/homebrew/opt/python@3.12/bin/python3.12 -m venv .venv && .venv/bin/pip install --upgrade pip && .venv/bin/pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt
```
Expected: `Successfully installed ...` 로 종료. 해결 실패(`ResolutionImpossible`)가 나면 실패한 패키지의 상한(`<2.0`)을 오류 메시지가 지목한 실제 최신 major에 맞춰 조정한다.

`.venv/`가 `.gitignore`에 없으면 추가한다:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && grep -q '^rag_server/.venv' .gitignore || echo 'rag_server/.venv/' >> .gitignore
```

- [ ] **Step 5: 핵심 import가 실제로 동작하는지 검증**

Run:
```bash
cd rag_server && .venv/bin/python -c "
from langchain_core.documents import Document
from langchain_core.embeddings import DeterministicFakeEmbedding
from langchain_core.output_parsers import JsonOutputParser
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_qdrant import QdrantVectorStore
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_core.runnables import RunnableLambda
from qdrant_client import QdrantClient, models
import litellm
print('IMPORTS_OK')
"
```
Expected: `IMPORTS_OK`

- [ ] **Step 6: 프로덕션 이미지 크기 측정 및 기록**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && docker compose build rag-server && docker image inspect yeonam-rag-server:latest --format '{{.Size}}' 2>/dev/null || docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep -i rag
```
Expected: 이미지 크기가 바이트 또는 `1.8GB` 형태로 출력된다.

측정값을 `docs/superpowers/plans/2026-07-26-langchain-qdrant-rag-refactor.md` 이 파일의 "운영 리스크" 절 끝에 한 줄로 추가한다:

```markdown
> **측정 결과(YYYY-MM-DD):** rag-server 이미지 크기 리팩토링 전 `<이전값>` → 후 `<이후값>`. EC2 인스턴스 여유 메모리 `<값>` 대비 판단: `<수용 가능 / fastembed 전환 필요>`.
```

`<이전값>`은 `git stash` 없이 얻기 어려우므로, 알 수 없으면 `미측정`이라고 적고 이후값만 기록한다.

- [ ] **Step 7: 커밋**

```bash
git add rag_server/requirements.txt rag_server/requirements.prod.txt rag_server/Dockerfile .gitignore docs/superpowers/plans/2026-07-26-langchain-qdrant-rag-refactor.md
git commit -m "chore: LangChain+Qdrant 의존성 스택으로 교체

- faiss-cpu/sentence-transformers 직접 의존 제거
- langchain-core/text-splitters/qdrant/huggingface/litellm, qdrant-client 추가
- ChatLiteLLM 미채택: langchain-litellm이 litellm>=1.65/httpx>=0.28을 요구해
  기존 핀과 충돌하므로 litellm.acompletion을 RunnableLambda로 래핑 (Task 7)
- 프로덕션도 실임베딩을 수행하므로 requirements.prod.txt에 동일 스택 반영,
  CPU 전용 torch 휠 인덱스로 이미지 크기 억제"
```

---

## Task 3: `vector_store.py` — Qdrant 접근 계층

**Files:**
- Create: `rag_server/vector_store.py`
- Create: `rag_server/test_vector_store.py`

**Interfaces:**
- Consumes: Task 2의 `langchain_qdrant`, `langchain_huggingface`, `qdrant_client`. Task 1의 `QDRANT_URL`/`QDRANT_COLLECTION`/`EMBEDDING_MODEL`.
- Produces (이후 태스크가 의존하는 정확한 시그니처):
  ```python
  EMBEDDING_DIM: int = 384
  COLLECTION_NAME: str                      # QDRANT_COLLECTION 환경변수, 기본 "yeonam_knowledge"
  CHUNK_NAMESPACE: uuid.UUID
  def point_id_for(chunk_id: str) -> str
  def build_embeddings() -> Embeddings
  def build_client() -> QdrantClient
  def ensure_collection(client: QdrantClient, dim: int) -> None
  def get_vector_store() -> QdrantVectorStore          # 프로세스 캐시 싱글턴
  def reset_vector_store() -> None                     # 테스트용 캐시 초기화
  def add_documents(documents: list[Document]) -> list[str]   # 반환: point id 목록
  def delete_by_file_id(file_id: str) -> None
  def search(query: str, k: int = 5, score_threshold: float = 0.35) -> list[tuple[Document, float]]
  def ping() -> bool
  ```
  `Document.metadata`는 항상 `{"chunk_id", "file_id", "file_name", "section_title", "source_type"}` 키를 갖는다. `source_type`은 `"document"` 또는 `"knowledge_card"`.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `rag_server/test_vector_store.py`:

```python
"""vector_store 단위 테스트 — Qdrant 로컬 모드(:memory:)에서 실제 코드 경로를 검증한다."""
import pytest
from langchain_core.documents import Document

import vector_store


def _doc(chunk_id, text, file_id="FILE-A", source_type="document"):
    return Document(
        page_content=text,
        metadata={
            "chunk_id": chunk_id,
            "file_id": file_id,
            "file_name": "spec.pdf",
            "section_title": "1. 개요",
            "source_type": source_type,
        },
    )


def test_point_id_is_deterministic_uuid():
    first = vector_store.point_id_for("CHNK-ABC123")
    second = vector_store.point_id_for("CHNK-ABC123")
    other = vector_store.point_id_for("CHNK-XYZ789")

    assert first == second
    assert first != other
    assert len(first) == 36 and first.count("-") == 4


def test_add_documents_is_idempotent_upsert(memory_vector_store):
    vector_store.add_documents([_doc("CHNK-0001", "로그인 실패 시 401을 반환한다")])
    vector_store.add_documents([_doc("CHNK-0001", "로그인 실패 시 401을 반환한다")])

    count = memory_vector_store.client.count(vector_store.COLLECTION_NAME).count
    assert count == 1


def test_search_returns_documents_with_scores(memory_vector_store):
    vector_store.add_documents([
        _doc("CHNK-0001", "로그인 실패 시 401 Unauthorized를 반환해야 한다"),
        _doc("CHNK-0002", "파일 업로드는 20MB로 제한한다"),
    ])

    results = vector_store.search("로그인 실패 시 401 Unauthorized를 반환해야 한다", k=2, score_threshold=0.0)

    assert len(results) == 2
    top_doc, top_score = results[0]
    assert top_doc.metadata["chunk_id"] == "CHNK-0001"
    assert isinstance(top_score, float)
    # 동일 텍스트이므로 결정적 임베딩에서 코사인 유사도가 1.0에 수렴한다
    assert top_score > 0.99


def test_search_applies_score_threshold(memory_vector_store):
    vector_store.add_documents([_doc("CHNK-0001", "로그인 실패 시 401을 반환한다")])

    results = vector_store.search("전혀 무관한 질의", k=5, score_threshold=0.999)

    assert results == []


def test_delete_by_file_id_removes_only_that_file(memory_vector_store):
    vector_store.add_documents([
        _doc("CHNK-0001", "A 파일의 첫 청크", file_id="FILE-A"),
        _doc("CHNK-0002", "B 파일의 첫 청크", file_id="FILE-B"),
    ])

    vector_store.delete_by_file_id("FILE-A")

    remaining = vector_store.search("청크", k=10, score_threshold=0.0)
    remaining_ids = {doc.metadata["chunk_id"] for doc, _ in remaining}
    assert remaining_ids == {"CHNK-0002"}


def test_ensure_collection_rejects_dimension_mismatch(memory_vector_store):
    with pytest.raises(RuntimeError, match="벡터 차원 불일치"):
        vector_store.ensure_collection(memory_vector_store.client, dim=768)


def test_ping_returns_true_when_reachable(memory_vector_store):
    assert vector_store.ping() is True
```

이 테스트들이 쓰는 `memory_vector_store` 픽스처는 Step 2에서 `conftest.py`에 추가한다.

- [ ] **Step 2: `conftest.py`에 로컬 모드 픽스처 추가**

`rag_server/conftest.py` 전체를 아래로 교체한다. 기존의 `faiss`/`sentence_transformers`/`vector_db_manager`/`retriever` 모듈 통짜 mock은 더 이상 필요 없다 — 실제 코드가 로컬 모드 Qdrant 위에서 돌기 때문이다.

```python
"""테스트 공통 픽스처.

Qdrant는 qdrant-client의 로컬 모드(:memory:)로, 임베딩은 결정적 가짜 임베딩으로
대체해 외부 컨테이너나 모델 다운로드 없이 실제 코드 경로를 검증한다.
"""
import os
import sys
from unittest.mock import AsyncMock, MagicMock

import pytest
from langchain_core.embeddings import DeterministicFakeEmbedding

os.environ["QDRANT_URL"] = ":memory:"
os.environ["QDRANT_COLLECTION"] = "yeonam_knowledge_test"
os.environ["MOCK_LLM"] = "true"

# asyncio 큐 워커는 테스트 대상이 아니므로 모듈 단위로 대체한다.
_qm = MagicMock()
_qm.queue_manager.queue.qsize.return_value = 0
_qm.queue_manager.start_worker = MagicMock()
_qm.queue_manager.stop_worker = AsyncMock()
sys.modules.setdefault("queue_manager", _qm)


@pytest.fixture
def fake_embeddings():
    return DeterministicFakeEmbedding(size=384)


@pytest.fixture
def memory_vector_store(monkeypatch, fake_embeddings):
    """vector_store 싱글턴을 로컬 모드 Qdrant + 가짜 임베딩으로 교체한다."""
    import vector_store

    monkeypatch.setattr(vector_store, "build_embeddings", lambda: fake_embeddings)
    vector_store.reset_vector_store()
    store = vector_store.get_vector_store()
    yield store
    vector_store.reset_vector_store()
```

`DeterministicFakeEmbedding(size=384)`은 `EMBEDDING_DIM`과 동일한 384차원을 만들므로 컬렉션 스키마가 실제와 일치한다.

- [ ] **Step 3: 테스트 실패 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_vector_store.py -v
```
Expected: 수집 단계에서 `ModuleNotFoundError: No module named 'vector_store'` 로 전부 ERROR.

- [ ] **Step 4: `vector_store.py` 구현**

Create `rag_server/vector_store.py`:

```python
"""Qdrant 벡터 스토어 접근 계층.

이 모듈이 벡터DB에 대한 유일한 접근 지점이다. 다른 모듈은 qdrant_client나
QdrantVectorStore를 직접 다루지 않는다.
"""
import logging
import os
import uuid
from typing import List, Optional, Tuple

from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_qdrant import QdrantVectorStore
from qdrant_client import QdrantClient, models

logger = logging.getLogger("rag_server.vector_store")

EMBEDDING_DIM = 384
COLLECTION_NAME = os.getenv("QDRANT_COLLECTION", "yeonam_knowledge")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "all-MiniLM-L6-v2")
QDRANT_URL = os.getenv("QDRANT_URL", "http://qdrant:6333")

# chunk_id(사람이 읽는 문자열)를 Qdrant point ID(UUID)로 결정적 변환하기 위한 네임스페이스.
# 값 자체에 의미는 없으나 절대 바꾸면 안 된다 — 바꾸면 기존 포인트가 전부 중복 삽입된다.
CHUNK_NAMESPACE = uuid.UUID("1b671a64-40d5-491e-99b0-da01ff1f3341")

_vector_store: Optional[QdrantVectorStore] = None


def point_id_for(chunk_id: str) -> str:
    """chunk_id를 결정적 UUID로 변환한다. 동일 chunk_id는 항상 동일 point ID가 되어 upsert가 멱등해진다."""
    return str(uuid.uuid5(CHUNK_NAMESPACE, chunk_id))


def build_embeddings() -> Embeddings:
    """로컬 임베딩 모델을 로드한다. 실패는 전파한다 — 조용한 폴백을 두지 않는다."""
    from langchain_huggingface import HuggingFaceEmbeddings

    logger.info(f"임베딩 모델 로드 중: {EMBEDDING_MODEL}")
    embeddings = HuggingFaceEmbeddings(model_name=EMBEDDING_MODEL)
    logger.info("임베딩 모델 로드 완료")
    return embeddings


def build_client() -> QdrantClient:
    """QDRANT_URL이 ':memory:'이면 서버 없는 로컬 모드로 뜬다(테스트용)."""
    if QDRANT_URL == ":memory:":
        logger.info("Qdrant 로컬 모드(:memory:)로 기동")
        return QdrantClient(location=":memory:")
    logger.info(f"Qdrant 연결: {QDRANT_URL}")
    return QdrantClient(url=QDRANT_URL)


def ensure_collection(client: QdrantClient, dim: int) -> None:
    """컬렉션이 없으면 만들고, 있으면 벡터 차원이 일치하는지 검증한다."""
    if client.collection_exists(COLLECTION_NAME):
        existing_dim = client.get_collection(COLLECTION_NAME).config.params.vectors.size
        if existing_dim != dim:
            raise RuntimeError(
                f"벡터 차원 불일치: 컬렉션 '{COLLECTION_NAME}'은 {existing_dim}차원인데 "
                f"현재 임베딩 모델은 {dim}차원이다. 컬렉션을 삭제하고 수동 재적재가 필요하다."
            )
        return

    logger.info(f"컬렉션 생성: {COLLECTION_NAME} (dim={dim}, COSINE)")
    client.create_collection(
        collection_name=COLLECTION_NAME,
        vectors_config=models.VectorParams(size=dim, distance=models.Distance.COSINE),
    )
    # file_id 기반 삭제 필터를 위한 payload 인덱스.
    client.create_payload_index(
        collection_name=COLLECTION_NAME,
        field_name="metadata.file_id",
        field_schema=models.PayloadSchemaType.KEYWORD,
    )


def get_vector_store() -> QdrantVectorStore:
    """프로세스 수명 동안 재사용되는 벡터 스토어 싱글턴."""
    global _vector_store
    if _vector_store is None:
        embeddings = build_embeddings()
        dim = len(embeddings.embed_query("차원 확인용 질의"))
        client = build_client()
        ensure_collection(client, dim)
        _vector_store = QdrantVectorStore(
            client=client,
            collection_name=COLLECTION_NAME,
            embedding=embeddings,
        )
    return _vector_store


def reset_vector_store() -> None:
    """싱글턴을 초기화한다. 테스트에서만 사용한다."""
    global _vector_store
    _vector_store = None


def add_documents(documents: List[Document]) -> List[str]:
    """문서를 upsert한다. metadata['chunk_id']에서 결정적 point ID를 만든다."""
    if not documents:
        return []
    ids = [point_id_for(doc.metadata["chunk_id"]) for doc in documents]
    get_vector_store().add_documents(documents, ids=ids)
    logger.info(f"{len(documents)}개 문서를 '{COLLECTION_NAME}'에 upsert")
    return ids


def delete_by_file_id(file_id: str) -> None:
    """특정 파일에서 유래한 모든 청크를 payload 필터로 즉시 삭제한다(전체 재빌드 불필요)."""
    store = get_vector_store()
    store.client.delete(
        collection_name=COLLECTION_NAME,
        points_selector=models.FilterSelector(
            filter=models.Filter(
                must=[
                    models.FieldCondition(
                        key="metadata.file_id",
                        match=models.MatchValue(value=file_id),
                    )
                ]
            )
        ),
    )
    logger.info(f"file_id={file_id} 청크 삭제 완료")


def search(query: str, k: int = 5, score_threshold: float = 0.35) -> List[Tuple[Document, float]]:
    """유사도 검색 후 threshold 미만을 걸러낸다. score는 코사인 유사도이며 호출자가 그대로 노출한다."""
    hits = get_vector_store().similarity_search_with_score(query, k=k)
    kept = [(doc, float(score)) for doc, score in hits if score >= score_threshold]
    dropped = len(hits) - len(kept)
    if dropped:
        logger.info(f"유사도 {score_threshold} 미만 {dropped}건 제외")
    return kept


def ping() -> bool:
    """Qdrant 연결 상태를 확인한다. /health가 이 결과를 그대로 반영한다."""
    try:
        get_vector_store().client.get_collections()
        return True
    except Exception as e:
        logger.error(f"Qdrant ping 실패: {e}")
        return False
```

- [ ] **Step 5: 테스트 통과 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_vector_store.py -v
```
Expected: `7 passed`

`test_ensure_collection_rejects_dimension_mismatch`가 `config.params.vectors.size` 접근에서 `AttributeError`로 실패하면, 해당 qdrant-client 버전이 named vector dict를 반환하는 것이므로 `ensure_collection`의 그 줄을 다음으로 바꾼다:
```python
        params = client.get_collection(COLLECTION_NAME).config.params.vectors
        existing_dim = params.size if hasattr(params, "size") else params[""].size
```

- [ ] **Step 6: 커밋**

```bash
git add rag_server/vector_store.py rag_server/test_vector_store.py rag_server/conftest.py
git commit -m "feat: Qdrant 벡터 스토어 접근 계층 vector_store.py 추가

- QdrantVectorStore + HuggingFaceEmbeddings 래핑, 싱글턴 수명주기
- chunk_id -> uuid5 결정적 point ID로 upsert 멱등성 보장
- metadata.file_id payload 필터 삭제로 전체 재빌드 제거
- 컬렉션 벡터 차원 불일치 시 명시적 에러
- 테스트는 qdrant-client 로컬 모드(:memory:)로 컨테이너 없이 실행"
```

---

## Task 4: `text_chunker.py` — RecursiveCharacterTextSplitter 도입

**Files:**
- Modify: `rag_server/text_chunker.py` (44-119행 `chunk_document` 전면 교체, 1-42행 `clean_text`/`detect_section_title`은 유지)
- Create: `rag_server/test_text_chunker.py`

**Interfaces:**
- Consumes: Task 3의 `Document.metadata` 스키마(`chunk_id`, `file_id`, `file_name`, `section_title`, `source_type`).
- Produces:
  ```python
  def clean_text(text: str) -> str                      # 변경 없음
  def detect_section_title(line: str) -> str | None     # 변경 없음
  def build_section_documents(raw_text: str, file_id: str, file_name: str) -> list[Document]
  def chunk_document(raw_text: str, file_id: str, file_name: str) -> list[Document]
  ```
  **반환 타입이 `list[dict]`에서 `list[Document]`로 바뀐다.** `chunk_id`는 `f"CHNK-{file_id}-{index:04d}"` 형식의 결정적 값이라 같은 파일을 재처리하면 동일 point를 덮어쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `rag_server/test_text_chunker.py`:

```python
"""text_chunker 단위 테스트."""
from langchain_core.documents import Document

from text_chunker import build_section_documents, chunk_document, clean_text, detect_section_title


def test_clean_text_removes_page_markers_and_page_numbers():
    raw = "--- Page 1 ---\n서론 내용\n1 / 5\n본문 내용\n42\n"

    assert clean_text(raw) == "서론 내용\n본문 내용"


def test_detect_section_title_recognises_korean_chapter():
    assert detect_section_title("제 2 장 시스템 요구사항") == "제 2 장 시스템 요구사항"
    assert detect_section_title("## 개요") == "## 개요"
    assert detect_section_title("1.1 로그인") == "1.1 로그인"
    assert detect_section_title("그냥 평범한 본문 문장입니다") is None


def test_build_section_documents_tags_each_paragraph_with_current_section():
    raw = "1. 로그인\n\n로그인 기능 설명\n\n2. 회원가입\n\n회원가입 기능 설명"

    docs = build_section_documents(raw, "FILE-A", "spec.pdf")

    sections = [d.metadata["section_title"] for d in docs]
    assert sections == ["1. 로그인", "1. 로그인", "2. 회원가입", "2. 회원가입"]
    assert all(d.metadata["file_id"] == "FILE-A" for d in docs)
    assert all(d.metadata["file_name"] == "spec.pdf" for d in docs)


def test_chunk_document_returns_documents_with_required_metadata():
    raw = "1. 개요\n\n" + ("요구사항 문장입니다. " * 200)

    chunks = chunk_document(raw, "FILE-A", "spec.pdf")

    assert len(chunks) > 1
    for chunk in chunks:
        assert isinstance(chunk, Document)
        assert set(chunk.metadata) >= {"chunk_id", "file_id", "file_name", "section_title", "source_type"}
        assert chunk.metadata["source_type"] == "document"
        assert chunk.metadata["file_id"] == "FILE-A"


def test_chunk_document_respects_max_chunk_size():
    raw = "요구사항 문장입니다. " * 500

    chunks = chunk_document(raw, "FILE-A", "spec.pdf")

    assert all(len(c.page_content) <= 1000 for c in chunks)


def test_chunk_ids_are_deterministic_across_runs():
    raw = "1. 개요\n\n" + ("요구사항 문장입니다. " * 200)

    first = [c.metadata["chunk_id"] for c in chunk_document(raw, "FILE-A", "spec.pdf")]
    second = [c.metadata["chunk_id"] for c in chunk_document(raw, "FILE-A", "spec.pdf")]

    assert first == second
    assert first[0] == "CHNK-FILE-A-0000"


def test_chunk_document_on_empty_text_returns_empty_list():
    assert chunk_document("   \n\n  ", "FILE-A", "spec.pdf") == []
```

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_text_chunker.py -v
```
Expected: `ImportError: cannot import name 'build_section_documents' from 'text_chunker'`

- [ ] **Step 3: `text_chunker.py` 구현**

`rag_server/text_chunker.py` 전체를 아래로 교체한다. `clean_text`와 `detect_section_title` 본문은 기존과 완전히 동일하다.

```python
import logging
import re
from typing import List, Optional

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

logger = logging.getLogger("rag_server.text_chunker")

CHUNK_SIZE = 1000
CHUNK_OVERLAP = 150

# 한국어 문서를 문단 -> 줄 -> 문장 -> 어절 순으로 자연스럽게 자르기 위한 구분자 우선순위.
_SPLITTER = RecursiveCharacterTextSplitter(
    chunk_size=CHUNK_SIZE,
    chunk_overlap=CHUNK_OVERLAP,
    separators=["\n\n", "\n", ". ", "。", "! ", "? ", " ", ""],
    keep_separator=False,
)


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


def detect_section_title(line: str) -> Optional[str]:
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


def build_section_documents(raw_text: str, file_id: str, file_name: str) -> List[Document]:
    """한국어 전처리 단계.

    노이즈를 제거하고 문단 단위로 순회하며 현재 섹션 제목을 추적해,
    각 문단을 섹션 메타데이터가 붙은 Document로 만든다.
    길이 기반 분할은 여기서 하지 않는다 — chunk_document가 splitter로 처리한다.
    """
    cleaned = clean_text(raw_text)
    documents: List[Document] = []
    current_section = "General"

    for paragraph in cleaned.split("\n\n"):
        paragraph = paragraph.strip()
        if not paragraph:
            continue

        detected = detect_section_title(paragraph.split("\n")[0])
        if detected:
            current_section = detected

        documents.append(
            Document(
                page_content=paragraph,
                metadata={
                    "file_id": file_id,
                    "file_name": file_name,
                    "section_title": current_section,
                    "source_type": "document",
                },
            )
        )

    return documents


def chunk_document(raw_text: str, file_id: str, file_name: str) -> List[Document]:
    """전처리된 섹션 Document를 길이 기반으로 분할하고 결정적 chunk_id를 부여한다.

    chunk_id가 (file_id, 순번)만으로 결정되므로 같은 파일을 재처리하면
    Qdrant에서 같은 point를 덮어쓴다.
    """
    sections = build_section_documents(raw_text, file_id, file_name)
    chunks = _SPLITTER.split_documents(sections)

    for index, chunk in enumerate(chunks):
        chunk.metadata["chunk_id"] = f"CHNK-{file_id}-{index:04d}"

    logger.info(f"Generated {len(chunks)} chunks for file {file_name} ({file_id})")
    return chunks
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_text_chunker.py -v
```
Expected: `7 passed`

- [ ] **Step 5: 커밋**

```bash
git add rag_server/text_chunker.py rag_server/test_text_chunker.py
git commit -m "refactor: 청킹을 RecursiveCharacterTextSplitter로 교체

- 한국어 전처리(clean_text/detect_section_title/섹션 추적)는 그대로 유지
- 범용 길이 분할만 LangChain splitter에 위임 (chunk_size 1000, overlap 150)
- 반환 타입을 list[dict] -> list[Document]로 변경
- chunk_id를 (file_id, 순번) 기반 결정적 값으로 바꿔 재처리 시 upsert 되도록 함"
```

---

## Task 5: `ingestion.py` — 정적 지식 적재

**Files:**
- Create: `rag_server/ingestion.py`
- Create: `rag_server/test_ingestion.py`

**Interfaces:**
- Consumes: Task 3의 `vector_store.add_documents`, `vector_store.get_vector_store`.
- Produces:
  ```python
  KNOWLEDGE_FILE_ID: str = "__knowledge_base__"
  DEFAULT_KB: list[dict]
  def load_knowledge_documents() -> list[Document]
  def ingest_knowledge_base() -> int          # 반환: 적재한 Document 개수
  ```
  생성되는 Document의 metadata는 `{"chunk_id", "file_id": KNOWLEDGE_FILE_ID, "file_name": <출처 파일명>, "section_title", "source_type": "knowledge_card"}`.
  `file_id`가 고정 상수이므로 사용자 문서 삭제(`delete_by_file_id`)가 지식카드를 건드리지 않는다.

**참고:** 기존 `retriever.py:33-152`의 `load_knowledge_base()`/`DEFAULT_KB`/`knowledge_cards` 로직이 이 모듈로 이관된다(중복 분기는 DRY하게 정리). `embedded/generate_rag_chunks.py`가 만드는 `rag_chunks.jsonl` 스키마(`chunk_id`, `section_title`, `content`, `source_file`, `metadata.keywords`)를 그대로 읽는다. 그 스크립트 자체는 손대지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `rag_server/test_ingestion.py`:

```python
"""ingestion 단위 테스트."""
import json

import pytest
from langchain_core.documents import Document

import ingestion
import vector_store


def test_load_documents_from_jsonl_prefers_rag_chunks(tmp_path, monkeypatch):
    jsonl = tmp_path / "rag_chunks.jsonl"
    jsonl.write_text(
        json.dumps({
            "chunk_id": "QA-0001",
            "section_title": "경계값 분석",
            "content": "경계값 분석은 입력 경계에서 결함이 몰린다는 점을 이용한다.",
            "source_file": "istqb_knowledge_cards.json",
            "metadata": {"keywords": ["테스트 설계"]},
        }, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(jsonl))

    docs = ingestion.load_knowledge_documents()

    assert len(docs) == 1
    doc = docs[0]
    assert doc.page_content == "경계값 분석은 입력 경계에서 결함이 몰린다는 점을 이용한다."
    assert doc.metadata["chunk_id"] == "QA-0001"
    assert doc.metadata["section_title"] == "경계값 분석"
    assert doc.metadata["file_name"] == "istqb_knowledge_cards.json"
    assert doc.metadata["source_type"] == "knowledge_card"
    assert doc.metadata["file_id"] == ingestion.KNOWLEDGE_FILE_ID


def test_load_documents_falls_back_to_knowledge_base_json(tmp_path, monkeypatch):
    kb_dir = tmp_path / "knowledge_base"
    kb_dir.mkdir()
    (kb_dir / "sample_knowledge_cards.json").write_text(
        json.dumps([
            {"category": "보안", "title": "SQL Injection 방어", "content": "PreparedStatement를 사용한다."},
            {"category": "성능", "title": "커넥션 풀", "technique": "부하 테스트", "risk_type": "스레드 고갈"},
        ], ensure_ascii=False),
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(tmp_path / "missing.jsonl"))
    monkeypatch.setattr(ingestion, "KNOWLEDGE_BASE_DIR", str(kb_dir))

    docs = ingestion.load_knowledge_documents()

    assert len(docs) == 2
    assert docs[0].page_content == "PreparedStatement를 사용한다."
    # content가 없는 카드는 구조화 필드를 조합해 본문을 만든다
    assert "테스트 기법: 부하 테스트" in docs[1].page_content
    assert "위험 유형: 스레드 고갈" in docs[1].page_content
    assert {d.metadata["chunk_id"] for d in docs} == {"QA-FILE-0000", "QA-FILE-0001"}


def test_load_documents_falls_back_to_default_kb(tmp_path, monkeypatch):
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(tmp_path / "missing.jsonl"))
    monkeypatch.setattr(ingestion, "KNOWLEDGE_BASE_DIR", str(tmp_path / "missing_dir"))

    docs = ingestion.load_knowledge_documents()

    assert len(docs) == len(ingestion.DEFAULT_KB)
    assert all(d.metadata["source_type"] == "knowledge_card" for d in docs)


def test_ingest_knowledge_base_is_idempotent(memory_vector_store, tmp_path, monkeypatch):
    jsonl = tmp_path / "rag_chunks.jsonl"
    jsonl.write_text(
        json.dumps({
            "chunk_id": "QA-0001",
            "section_title": "경계값 분석",
            "content": "경계값 분석 설명",
            "source_file": "istqb_knowledge_cards.json",
        }, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(jsonl))

    assert ingestion.ingest_knowledge_base() == 1
    assert ingestion.ingest_knowledge_base() == 1

    count = memory_vector_store.client.count(vector_store.COLLECTION_NAME).count
    assert count == 1


def test_ingested_knowledge_survives_document_deletion(memory_vector_store, tmp_path, monkeypatch):
    jsonl = tmp_path / "rag_chunks.jsonl"
    jsonl.write_text(
        json.dumps({
            "chunk_id": "QA-0001",
            "section_title": "경계값 분석",
            "content": "경계값 분석 설명",
            "source_file": "istqb_knowledge_cards.json",
        }, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(ingestion, "RAG_CHUNKS_PATH", str(jsonl))
    ingestion.ingest_knowledge_base()

    vector_store.add_documents([
        Document(page_content="사용자 문서 청크", metadata={
            "chunk_id": "CHNK-FILE-A-0000", "file_id": "FILE-A",
            "file_name": "spec.pdf", "section_title": "1. 개요", "source_type": "document",
        })
    ])
    vector_store.delete_by_file_id("FILE-A")

    count = memory_vector_store.client.count(vector_store.COLLECTION_NAME).count
    assert count == 1
```

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_ingestion.py -v
```
Expected: `ModuleNotFoundError: No module named 'ingestion'`

- [ ] **Step 3: `ingestion.py` 구현**

Create `rag_server/ingestion.py`:

```python
"""정적 QA 지식(지식카드)을 Qdrant에 적재한다.

우선순위:
  1) rag_chunks.jsonl  (embedded/generate_rag_chunks.py 산출물)
  2) knowledge_base/*.json
  3) DEFAULT_KB (하드코딩 최소 세트)

chunk_id가 결정적이라 서버를 여러 번 재기동해도 중복 삽입되지 않는다.
"""
import json
import logging
import os
from typing import Dict, List

from langchain_core.documents import Document

import vector_store

logger = logging.getLogger("rag_server.ingestion")

_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RAG_CHUNKS_PATH = os.path.join(_BASE_DIR, "rag_chunks.jsonl")
KNOWLEDGE_BASE_DIR = os.path.join(_BASE_DIR, "knowledge_base")

# 지식카드 전용 고정 file_id. 사용자 문서 삭제(delete_by_file_id)가 지식카드를 건드리지 않게 한다.
KNOWLEDGE_FILE_ID = "__knowledge_base__"

# content 필드가 없는 카드에서 본문을 조합할 때 쓰는 (필드명, 한국어 라벨) 목록.
_COMPOSED_FIELDS = [
    ("technique", "테스트 기법"),
    ("apply_when", "적용 조건"),
    ("qa_perspective", "QA 관점"),
    ("risk_type", "위험 유형"),
    ("tdd_hint", "TDD 힌트"),
    ("example_scenario", "예시 시나리오"),
    ("evidence", "근거"),
]

DEFAULT_KB: List[Dict] = [
    {
        "category": "보안",
        "title": "사용자 입력값 검증 및 SQL Injection 방어",
        "content": "사용자가 입력한 모든 파라미터는 백엔드 진입 시 즉시 유효성 검사(@Valid)를 거쳐야 하며, SQL 쿼리 빌드 시 PreparedStatement 또는 JPA Criteria API를 사용하여 파라미터를 바인딩해야 합니다.",
    },
    {
        "category": "성능 및 가용성",
        "title": "데이터베이스 커넥션 풀 최적화 및 타임아웃",
        "content": "트래픽 폭주 시 데드락을 방지하기 위해 HikariCP 커넥션 풀 크기는 적절히 셋업되어야 하며, 장시간 수행 쿼리는 Query Timeout(예: 3초)을 명시적으로 부여하여 스레드 고갈을 차단해야 합니다.",
    },
    {
        "category": "API 설계 및 예외 처리",
        "title": "일관된 글로벌 API 에러 응답 체계",
        "content": "백엔드 API는 어떠한 내부 서버 오류가 발생하더라도 사용자에게 Raw Stack Trace를 노출하지 않아야 하며, @RestControllerAdvice를 가동해 사전에 약속된 JSON 형태의 에러 응답 포맷(status, code, message)으로 통일하여 응답해야 합니다.",
    },
    {
        "category": "파일 업로드 및 유효성",
        "title": "파일 업로드 용량 제한 및 확장자 필터링",
        "content": "업로드 파일은 프론트엔드와 백엔드 양측에서 이중 검증을 수행해야 합니다. 최대 용량 20MB 제한을 지키고, 허용된 안전한 확장자(pdf, md, txt, docx)만 통과시키며, 파일 MIME 타입을 직접 검증하여 실행 파일 업로드를 차단해야 합니다.",
    },
]


def _card_document(chunk_id: str, title: str, content: str, source_name: str) -> Document:
    return Document(
        page_content=content,
        metadata={
            "chunk_id": chunk_id,
            "file_id": KNOWLEDGE_FILE_ID,
            "file_name": source_name,
            "section_title": title,
            "source_type": "knowledge_card",
        },
    )


def _compose_content(card: Dict) -> str:
    """content/description이 없는 카드에서 구조화 필드를 한국어 라벨과 함께 이어붙인다."""
    direct = card.get("content") or card.get("description")
    if direct:
        return direct

    parts = []
    for field, label in _COMPOSED_FIELDS:
        value = card.get(field)
        if not value:
            continue
        if isinstance(value, list):
            value = " / ".join(str(item) for item in value)
        parts.append(f"{label}: {value}")
    return " ".join(parts)


def _load_from_jsonl() -> List[Document]:
    documents = []
    with open(RAG_CHUNKS_PATH, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            item = json.loads(line)
            content = item.get("content", "").strip()
            if not content:
                continue
            documents.append(
                _card_document(
                    chunk_id=item.get("chunk_id", "QA-0000"),
                    title=item.get("section_title", "General"),
                    content=content,
                    source_name=item.get("source_file", "unknown_cards.json"),
                )
            )
    logger.info(f"{RAG_CHUNKS_PATH}에서 지식 청크 {len(documents)}건 로드")
    return documents


def _load_from_knowledge_base_dir() -> List[Document]:
    documents = []
    json_files = sorted(f for f in os.listdir(KNOWLEDGE_BASE_DIR) if f.lower().endswith(".json"))

    for file_name in json_files:
        path = os.path.join(KNOWLEDGE_BASE_DIR, file_name)
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            logger.error(f"{file_name} 읽기 실패: {e}")
            continue

        cards = data if isinstance(data, list) else data.get("cards", [])
        # chunk_id 안정성을 위해 파일명 stem을 접두어로 쓴다(확장자/구분자 제거).
        stem = file_name.rsplit(".", 1)[0].replace("_", "-").upper()
        for index, card in enumerate(cards):
            content = _compose_content(card)
            if not content:
                continue
            documents.append(
                _card_document(
                    chunk_id=f"QA-{stem}-{index:04d}",
                    title=card.get("title") or card.get("category", "General"),
                    content=content,
                    source_name=file_name,
                )
            )
        logger.info(f"{file_name}에서 카드 {len(cards)}건 처리")

    return documents


def load_knowledge_documents() -> List[Document]:
    """지식카드를 Document 목록으로 로드한다."""
    if os.path.exists(RAG_CHUNKS_PATH):
        documents = _load_from_jsonl()
        if documents:
            return documents

    if os.path.isdir(KNOWLEDGE_BASE_DIR):
        documents = _load_from_knowledge_base_dir()
        if documents:
            return documents

    logger.warning("지식카드 파일을 찾지 못해 DEFAULT_KB로 대체한다.")
    return [
        _card_document(
            chunk_id=f"QA-DEFAULT-{index:04d}",
            title=card["title"],
            content=card["content"],
            source_name="default_knowledge_base",
        )
        for index, card in enumerate(DEFAULT_KB)
    ]


def ingest_knowledge_base() -> int:
    """지식카드를 Qdrant에 upsert한다. 실패는 전파해 기동을 실패시킨다."""
    documents = load_knowledge_documents()
    vector_store.add_documents(documents)
    logger.info(f"지식카드 {len(documents)}건을 '{vector_store.COLLECTION_NAME}'에 적재")
    return len(documents)
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_ingestion.py -v
```
Expected: `5 passed`

`test_load_documents_falls_back_to_knowledge_base_json`의 chunk_id 기대값이 다르면, 테스트의 기대값을 실제 stem 규칙(`sample_knowledge_cards.json` → `SAMPLE-KNOWLEDGE-CARDS`)에 맞춰 `{"QA-SAMPLE-KNOWLEDGE-CARDS-0000", "QA-SAMPLE-KNOWLEDGE-CARDS-0001"}`로 수정한다.

- [ ] **Step 5: 실제 지식카드 파일로 스모크 확인**

Run:
```bash
cd rag_server && .venv/bin/python -c "
import os
os.environ['QDRANT_URL'] = ':memory:'
import ingestion
docs = ingestion.load_knowledge_documents()
print(f'loaded={len(docs)}')
print('sample_chunk_id=', docs[0].metadata['chunk_id'])
print('sample_source=', docs[0].metadata['file_name'])
assert len(docs) > 10, '지식카드가 너무 적다 - 경로를 확인할 것'
print('SMOKE_OK')
"
```
Expected: `loaded=<수백 단위 숫자>`, `SMOKE_OK`. `rag_chunks.jsonl`이 리포에 있으면 그쪽에서, 없으면 `knowledge_base/*.json` 7개 파일에서 로드된다.

- [ ] **Step 6: 커밋**

```bash
git add rag_server/ingestion.py rag_server/test_ingestion.py
git commit -m "feat: 정적 QA 지식카드 Qdrant 적재 모듈 ingestion.py 추가

- rag_chunks.jsonl -> knowledge_base/*.json -> DEFAULT_KB 우선순위 로딩
- retriever.py에 있던 지식카드 로딩 책임을 이 모듈로 이관하고 중복 분기 정리
- 고정 file_id(__knowledge_base__)로 사용자 문서 삭제와 격리
- 결정적 chunk_id 기반 upsert라 재기동해도 중복 삽입 없음"
```

---

## Task 6: `retriever.py` 단순화

**Files:**
- Modify: `rag_server/retriever.py` (전면 교체 — 257행 → 약 60행)
- Create: `rag_server/test_retriever.py`

**Interfaces:**
- Consumes: Task 3의 `vector_store.search(query, k, score_threshold) -> list[tuple[Document, float]]`, Task 4/5가 넣는 `Document.metadata` 스키마.
- Produces:
  ```python
  def retrieve_evidences(query: str, threshold: float = 0.35, exclude_chunk_ids: set | None = None) -> list[dict]
  ```
  반환 dict 스키마는 **기존과 완전히 동일**: `{"chunk_id", "text", "source_name", "source_section", "score"}`. `main.py`와 `prompt_builder.build_prompt`가 이 키들을 그대로 쓴다.

**제거되는 것:** `DEFAULT_KB`, `load_knowledge_base()`, 모듈 로드 시 `knowledge_cards` 전역 초기화, 키워드 오버랩 점수 계산, 수동 점수 병합, `MOCK_RAG` 분기와 `CHNK-MOCK001/002/003` 하드코딩 데이터.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `rag_server/test_retriever.py`:

```python
"""retriever 단위 테스트."""
from langchain_core.documents import Document

import vector_store
from retriever import retrieve_evidences


def _index(memory_vector_store, *specs):
    vector_store.add_documents([
        Document(page_content=text, metadata={
            "chunk_id": chunk_id,
            "file_id": "FILE-A",
            "file_name": source_name,
            "section_title": section,
            "source_type": source_type,
        })
        for chunk_id, text, source_name, section, source_type in specs
    ])


def test_returns_evidence_dicts_with_legacy_schema(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"))

    evidences = retrieve_evidences("로그인 실패 시 401을 반환한다", threshold=0.0)

    assert len(evidences) == 1
    assert evidences[0] == {
        "chunk_id": "CHNK-0001",
        "text": "로그인 실패 시 401을 반환한다",
        "source_name": "spec.pdf",
        "source_section": "1. 인증",
        "score": evidences[0]["score"],
    }
    assert isinstance(evidences[0]["score"], float)


def test_documents_and_knowledge_cards_rank_together(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"),
           ("QA-0001", "인증 실패 응답 코드 검증은 부정 시나리오의 기본이다", "istqb.json", "부정 테스트", "knowledge_card"))

    evidences = retrieve_evidences("인증 실패", threshold=0.0)

    assert {e["chunk_id"] for e in evidences} == {"CHNK-0001", "QA-0001"}


def test_results_are_sorted_by_score_descending(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"),
           ("CHNK-0002", "파일 업로드는 20MB로 제한한다", "spec.pdf", "2. 업로드", "document"),
           ("CHNK-0003", "리포트는 PDF로 내보낸다", "spec.pdf", "3. 리포트", "document"))

    evidences = retrieve_evidences("로그인 실패 시 401을 반환한다", threshold=0.0)

    scores = [e["score"] for e in evidences]
    assert scores == sorted(scores, reverse=True)
    assert evidences[0]["chunk_id"] == "CHNK-0001"


def test_exclude_chunk_ids_filters_already_used_evidence(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"),
           ("CHNK-0002", "파일 업로드는 20MB로 제한한다", "spec.pdf", "2. 업로드", "document"))

    evidences = retrieve_evidences("로그인 실패 시 401을 반환한다",
                                   threshold=0.0,
                                   exclude_chunk_ids={"CHNK-0001"})

    assert all(e["chunk_id"] != "CHNK-0001" for e in evidences)


def test_threshold_filters_low_similarity(memory_vector_store):
    _index(memory_vector_store,
           ("CHNK-0001", "로그인 실패 시 401을 반환한다", "spec.pdf", "1. 인증", "document"))

    assert retrieve_evidences("완전히 다른 질의", threshold=0.999) == []


def test_empty_collection_returns_empty_list(memory_vector_store):
    assert retrieve_evidences("아무 질의", threshold=0.0) == []


def test_no_mock_rag_branch_remains():
    import retriever
    source = open(retriever.__file__, encoding="utf-8").read()
    assert "MOCK_RAG" not in source
    assert "CHNK-MOCK" not in source
```

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_retriever.py -v
```
Expected: 여러 테스트가 실패한다. 최소한 `test_no_mock_rag_branch_remains`가 `assert "MOCK_RAG" not in source`에서 실패하고, 나머지는 `ModuleNotFoundError: No module named 'vector_db_manager'` 로 ERROR.

- [ ] **Step 3: `retriever.py` 구현**

`rag_server/retriever.py` 전체를 아래로 교체:

```python
"""요구사항 텍스트에 대한 근거(evidence) 검색.

문서 청크와 QA 지식카드가 같은 Qdrant 컬렉션에 있으므로 단 한 번의
유사도 검색으로 함께 랭킹된다. 예전처럼 두 경로를 따로 태우고 점수를
수동 병합하지 않는다.
"""
import logging
from typing import Dict, List, Optional, Set

import vector_store

logger = logging.getLogger("rag_server.retriever")

DEFAULT_TOP_K = 5


def retrieve_evidences(
    query: str,
    threshold: float = 0.35,
    exclude_chunk_ids: Optional[Set[str]] = None,
) -> List[Dict]:
    """질의와 유사한 근거 청크를 점수 내림차순으로 반환한다.

    Args:
        query: 요구사항 텍스트.
        threshold: 코사인 유사도 하한. 미만은 버린다.
        exclude_chunk_ids: 같은 분석 작업의 앞선 요구사항에서 이미 쓴 chunk_id.
                           요구사항 간 근거 중복을 막는다.

    Returns:
        {"chunk_id", "text", "source_name", "source_section", "score"} dict 목록.
    """
    excluded = exclude_chunk_ids or set()

    # 제외될 만큼 여유를 두고 가져와야 필터 후에도 DEFAULT_TOP_K를 채울 수 있다.
    fetch_k = DEFAULT_TOP_K + len(excluded)
    hits = vector_store.search(query, k=fetch_k, score_threshold=threshold)

    evidences: List[Dict] = []
    for document, score in hits:
        chunk_id = document.metadata.get("chunk_id")
        if chunk_id in excluded:
            continue
        evidences.append({
            "chunk_id": chunk_id,
            "text": document.page_content,
            "source_name": document.metadata.get("file_name", "unknown"),
            "source_section": document.metadata.get("section_title", "General"),
            "score": score,
        })

    evidences.sort(key=lambda item: item["score"], reverse=True)
    evidences = evidences[:DEFAULT_TOP_K]

    logger.info(f"'{query[:40]}...' 질의로 근거 {len(evidences)}건 검색")
    return evidences
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_retriever.py -v
```
Expected: `7 passed`

- [ ] **Step 5: 커밋**

```bash
git add rag_server/retriever.py rag_server/test_retriever.py
git commit -m "refactor: retriever를 단일 벡터 검색으로 축소

- 문서 청크 FAISS 검색 + 지식카드 키워드 오버랩 이중 경로와 수동 점수 병합 제거
- vector_store.search() 한 번 호출로 통합, exclude_chunk_ids는 후처리 필터로 유지
- MOCK_RAG 분기와 CHNK-MOCK 하드코딩 근거 데이터 제거
- 지식카드 로딩 책임은 ingestion.py로 이관 (257행 -> 약 60행)
- evidence dict 스키마는 기존과 동일하게 유지"
```

---

## Task 7: `prompt_builder.py` — LCEL 체인 도입

**Files:**
- Modify: `rag_server/prompt_builder.py` (124-201행 `call_llm_with_key` 재구성. 1-122행의 `MOCK_TEST_CASES`/`build_prompt`는 유지)
- Create: `rag_server/test_prompt_builder.py`

**Interfaces:**
- Consumes: Task 2의 `langchain_core.output_parsers.JsonOutputParser`, `langchain_core.runnables.RunnableLambda`, 기존 `litellm`. Task 6이 만드는 evidence dict.
- Produces:
  ```python
  MOCK_TEST_CASES: dict[str, list[dict]]                    # 변경 없음
  def build_prompt(req_text, evidences, custom_prompt, perspectives) -> str   # 변경 없음
  def build_chain(llm_api_key: str | None = None) -> Runnable   # str 프롬프트 -> list[dict]
  async def call_llm_with_key(prompt: str, llm_api_key: str | None = None) -> list[dict]   # 시그니처 변경 없음
  ```

- [ ] **Step 1: 실패하는 테스트 작성**

Create `rag_server/test_prompt_builder.py`:

```python
"""prompt_builder 단위 테스트."""
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import prompt_builder
from prompt_builder import build_prompt, call_llm_with_key


def _evidence(chunk_id="CHNK-0001", score=0.9):
    return {
        "chunk_id": chunk_id,
        "text": "로그인 실패 시 401을 반환한다",
        "source_name": "spec.pdf",
        "source_section": "1. 인증",
        "score": score,
    }


def _litellm_response(content: str):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content))]
    )


def test_build_prompt_embeds_evidence_metadata():
    prompt = build_prompt("로그인 요구사항", [_evidence()], "", [])

    assert '<evidence score="0.90" file="spec.pdf" section="1. 인증">' in prompt
    assert "<chunk_id>CHNK-0001</chunk_id>" in prompt
    assert "<target_requirement>\n로그인 요구사항\n</target_requirement>" in prompt


def test_build_prompt_appends_perspectives_and_custom_prompt():
    prompt = build_prompt("요구사항", [], "보안 위주로", ["보안", "성능"])

    assert "<qa_perspectives>\n보안, 성능\n</qa_perspectives>" in prompt
    assert "<custom_prompt>\n보안 위주로\n</custom_prompt>" in prompt


def test_apply_field_fallbacks_fills_missing_fields():
    result = prompt_builder._apply_field_fallbacks([{"testCaseName": "이름만 있는 케이스"}])

    tc = result[0]
    assert tc["precondition"] == "추가 검토 필요 (기본값 설정됨)"
    assert tc["testSteps"] == "1. 추가 검토 필요 (단계 자동 보정)"
    assert tc["expectedResult"] == "추가 검토 필요 (결과 자동 보정)"
    assert tc["testCaseId"] == "TC-AUTOGEN"
    assert tc["priority"] == "MEDIUM"
    assert tc["confidenceLevel"] == "MEDIUM"
    assert tc["riskTags"] == ["#추가_검토"]
    assert tc["caution"].startswith("추가 검토 필요")


def test_apply_field_fallbacks_preserves_provided_values():
    result = prompt_builder._apply_field_fallbacks([{"testCaseId": "TC-777", "priority": "HIGH"}])

    assert result[0]["testCaseId"] == "TC-777"
    assert result[0]["priority"] == "HIGH"


def test_apply_field_fallbacks_rejects_non_list():
    with pytest.raises(ValueError, match="JSON 배열이 아닙니다"):
        prompt_builder._apply_field_fallbacks({"testCaseId": "TC-1"})


@pytest.mark.asyncio
async def test_mock_llm_returns_mock_test_cases(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "true")

    result = await call_llm_with_key(build_prompt("URL 유효성 요구사항", [], "", []))

    assert result == prompt_builder.MOCK_TEST_CASES["REQ-001"]


@pytest.mark.asyncio
async def test_chain_parses_json_fenced_llm_response(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    payload = [{"testCaseId": "TC-001", "testCaseName": "로그인 실패 검증"}]
    acompletion = AsyncMock(return_value=_litellm_response(
        "설명 문장\n```json\n" + json.dumps(payload, ensure_ascii=False) + "\n```"
    ))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    result = await call_llm_with_key("프롬프트")

    assert result[0]["testCaseId"] == "TC-001"
    assert result[0]["testCaseName"] == "로그인 실패 검증"
    # JsonOutputParser 통과 후 필드 보정이 적용된다
    assert result[0]["priority"] == "MEDIUM"


@pytest.mark.asyncio
async def test_dynamic_api_key_reaches_litellm(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    acompletion = AsyncMock(return_value=_litellm_response('```json\n[{"testCaseId":"TC-1"}]\n```'))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    await call_llm_with_key("프롬프트", llm_api_key="sk-dynamic-key")

    assert acompletion.await_count == 1
    assert acompletion.await_args.kwargs.get("api_key") == "sk-dynamic-key"


@pytest.mark.asyncio
async def test_unparseable_response_falls_back_to_mock(monkeypatch):
    monkeypatch.setenv("MOCK_LLM", "false")
    acompletion = AsyncMock(return_value=_litellm_response("JSON이 전혀 아닌 응답"))
    monkeypatch.setattr("litellm.acompletion", acompletion)

    result = await call_llm_with_key("프롬프트")

    assert result == prompt_builder.MOCK_TEST_CASES["REQ-001"]


@pytest.mark.asyncio
async def test_authentication_error_is_reraised(monkeypatch):
    import litellm

    monkeypatch.setenv("MOCK_LLM", "false")
    error = litellm.exceptions.AuthenticationError(
        message="invalid key", llm_provider="openai", model="gpt-4o-mini"
    )
    monkeypatch.setattr("litellm.acompletion", AsyncMock(side_effect=error))

    with pytest.raises(litellm.exceptions.AuthenticationError):
        await call_llm_with_key("프롬프트")
```

- [ ] **Step 2: pytest-asyncio 설정 추가**

Create `rag_server/pytest.ini`:

```ini
[pytest]
asyncio_mode = auto
```

- [ ] **Step 3: 테스트 실패 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_prompt_builder.py -v
```
Expected: `test_apply_field_fallbacks_*` 3건이 `AttributeError: module 'prompt_builder' has no attribute '_apply_field_fallbacks'`로, `test_dynamic_api_key_reaches_litellm` 등이 실패한다.

- [ ] **Step 4: `prompt_builder.py`의 124-201행을 교체**

`import` 블록(1-7행)을 아래로 교체:

```python
import asyncio
import logging
import os
import re
from typing import Dict, List, Optional

import litellm
from langchain_core.output_parsers import JsonOutputParser
from langchain_core.runnables import Runnable, RunnableLambda
```

(`json` import는 더 이상 필요 없다 — `JsonOutputParser`가 파싱을 담당한다.)

그리고 124행 `async def call_llm_with_key(...)`부터 파일 끝(201행)까지를 아래로 교체:

```python
# LLM이 누락한 필드를 보정하는 기본값. 리포트 렌더링이 빈 필드로 깨지지 않게 한다.
_FIELD_FALLBACKS = {
    "precondition": "추가 검토 필요 (기본값 설정됨)",
    "testSteps": "1. 추가 검토 필요 (단계 자동 보정)",
    "expectedResult": "추가 검토 필요 (결과 자동 보정)",
    "testCaseId": "TC-AUTOGEN",
    "priority": "MEDIUM",
    "confidenceLevel": "MEDIUM",
    "caution": "추가 검토 필요 (가정에 의존한 테스트이므로 실행 시 주의 요망)",
}


def _apply_field_fallbacks(test_cases) -> List[Dict]:
    """JsonOutputParser 출력에 필수 필드 기본값을 채운다."""
    if not isinstance(test_cases, list):
        raise ValueError(f"LLM 응답이 JSON 배열이 아닙니다: {type(test_cases).__name__}")

    for test_case in test_cases:
        for field, default in _FIELD_FALLBACKS.items():
            if not test_case.get(field):
                test_case[field] = default
        if not test_case.get("riskTags"):
            test_case["riskTags"] = ["#추가_검토"]

    return test_cases


def _pick_mock_test_cases(prompt: str) -> List[Dict]:
    """MOCK_LLM 모드에서 프롬프트의 요구사항 텍스트로 mock 세트를 고른다."""
    match = re.search(r'<target_requirement>\s*(.*?)\s*</target_requirement>', prompt, re.DOTALL)
    if match:
        req_text = match.group(1).strip()
        if "업로드" in req_text:
            return MOCK_TEST_CASES["REQ-002"]
    return MOCK_TEST_CASES["REQ-001"]


async def _acall_litellm(prompt: str, llm_api_key: Optional[str] = None) -> str:
    """LCEL 체인의 LLM 단계. litellm을 직접 호출하고 원문 문자열을 반환한다.

    ChatLiteLLM을 쓰지 않는 이유는 계획 문서의 '의도적 편차' #1 참고 —
    langchain-litellm이 litellm>=1.65/httpx>=0.28을 요구해 기존 핀과 충돌한다.
    """
    kwargs = {
        "model": os.getenv("LLM_MODEL", "gpt-4o-mini"),
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
    }
    if llm_api_key and llm_api_key.strip():
        kwargs["api_key"] = llm_api_key.strip()

    response = await litellm.acompletion(**kwargs)
    return response.choices[0].message.content


def build_chain(llm_api_key: Optional[str] = None) -> Runnable:
    """프롬프트 문자열을 받아 테스트케이스 목록을 내는 LCEL 체인을 만든다.

    체인 구성: 프롬프트 문자열 -> litellm 호출 -> JsonOutputParser -> 필드 보정
    비동기 전용 체인이다. `ainvoke`로만 실행한다.
    """
    if llm_api_key and llm_api_key.strip():
        logger.info("Applying dynamic user-provided LLM API key for generation.")

    async def call_llm(prompt: str) -> str:
        return await _acall_litellm(prompt, llm_api_key)

    return (
        RunnableLambda(call_llm)
        | JsonOutputParser()
        | RunnableLambda(_apply_field_fallbacks)
    )


async def call_llm_with_key(prompt: str, llm_api_key: Optional[str] = None) -> List[Dict]:
    """
    Calls LiteLLM with prompt via an LCEL chain. Supports user dynamic API Key injection.
    Falls back to mock data if MOCK_LLM=true.
    """
    if os.getenv("MOCK_LLM", "true").lower() == "true":
        logger.info("MOCK_LLM is enabled. Returning mock test cases.")
        await asyncio.sleep(1.0)
        return _pick_mock_test_cases(prompt)

    logger.info(f"Calling real LLM model for RAG testcase generation: {os.getenv('LLM_MODEL', 'gpt-4o-mini')}")

    try:
        return await build_chain(llm_api_key).ainvoke(prompt)
    except litellm.exceptions.AuthenticationError as e:
        logger.error(f"LiteLLM AuthenticationError caught: {str(e)}")
        raise
    except Exception as e:
        logger.error(
            f"Failed to generate test cases via LLM: {str(e)}. Falling back to mock data.",
            exc_info=True,
        )
        return MOCK_TEST_CASES["REQ-001"]
```

- [ ] **Step 5: 테스트 통과 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_prompt_builder.py -v
```
Expected: `10 passed`

`test_authentication_error_is_reraised`가 `AuthenticationError` 생성자 인자 불일치로 실패하면, 설치된 litellm 버전의 시그니처에 맞춰 테스트의 생성자 호출만 조정한다(구현은 그대로).

- [ ] **Step 6: 커밋**

```bash
git add rag_server/prompt_builder.py rag_server/test_prompt_builder.py rag_server/pytest.ini
git commit -m "refactor: 테스트케이스 생성을 LCEL 체인으로 재구성

- litellm 호출 -> JsonOutputParser -> 필드 보정 체인 구성
- 정규식 기반 json 블록 추출을 JsonOutputParser로 대체
- 8개 if 블록으로 흩어진 필드 기본값을 _FIELD_FALLBACKS 테이블로 DRY하게 정리
- build_prompt/MOCK_TEST_CASES/MOCK_LLM 동작 및 call_llm_with_key 시그니처는 불변
- ChatLiteLLM 대신 litellm.acompletion을 RunnableLambda로 래핑
  (langchain-litellm이 litellm>=1.65/httpx>=0.28을 요구해 기존 핀과 충돌)"
```

---

## Task 8: `main.py` 통합 및 `MOCK_RAG` 제거

**Files:**
- Modify: `rag_server/main.py` (16행 import, 32-178행 `process_job`, 180-186행 lifespan, 223-241행 preprocess, 262-278행 delete, 291-318행 eval, 321-328행 health)
- Modify: `rag_server/test_basic.py`
- Delete: `rag_server/vector_db_manager.py`

**Interfaces:**
- Consumes: Task 3 `vector_store.{add_documents, delete_by_file_id, ping}`, Task 4 `text_chunker.chunk_document -> list[Document]`, Task 5 `ingestion.ingest_knowledge_base`, Task 6 `retriever.retrieve_evidences`, Task 7 `prompt_builder.{build_prompt, call_llm_with_key}`.
- Produces: `/health` 응답 스키마 변경 — `mock_rag` 키 제거, `qdrant` 키 추가, Qdrant 미가용 시 HTTP 503:
  ```json
  {"status": "healthy|unhealthy", "mock_llm": bool, "qdrant": "up|down", "queue_size": int}
  ```

- [ ] **Step 1: 실패하는 테스트 작성**

`rag_server/test_basic.py` 전체를 아래로 교체:

```python
"""Smoke tests for rag_server endpoints."""
import pytest
from fastapi.testclient import TestClient

import vector_store


@pytest.fixture
def client(memory_vector_store, monkeypatch):
    monkeypatch.setattr("ingestion.ingest_knowledge_base", lambda: 0)
    from main import app
    with TestClient(app) as c:
        yield c


def test_health_reports_healthy_when_qdrant_is_up(client):
    resp = client.get("/health")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "healthy"
    assert body["qdrant"] == "up"
    assert "mock_rag" not in body
    assert "queue_size" in body


def test_health_reports_unhealthy_when_qdrant_is_down(client, monkeypatch):
    monkeypatch.setattr(vector_store, "ping", lambda: False)

    resp = client.get("/health")

    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "unhealthy"
    assert body["qdrant"] == "down"


def test_metrics_contains_counter(client):
    resp = client.get("/metrics")

    assert resp.status_code == 200
    assert b"rag_tokens_total" in resp.content


def test_delete_vectors_removes_file_chunks(client, monkeypatch):
    deleted = []
    monkeypatch.setattr(vector_store, "delete_by_file_id", lambda file_id: deleted.append(file_id))

    resp = client.delete("/api/vectors/FILE-A")

    assert resp.status_code == 200
    assert resp.json()["status"] == "success"
    assert deleted == ["FILE-A"]


def test_eval_generate_keeps_response_contract(client, monkeypatch):
    """/api/eval/generate의 응답 스키마는 리팩토링 전후로 동일해야 한다 (evaluation/eval_runner.py가 의존)."""
    async def fake_extract(parsed_documents, api_key):
        return [{"id": "REQ-001", "text": "로그인 실패 시 401을 반환한다"}]

    monkeypatch.setattr("main.extract_requirements", fake_extract)

    resp = client.post("/api/eval/generate", json={
        "text": "1. 인증\n\n로그인 실패 시 401을 반환한다.",
        "perspectives": ["보안"],
    })

    assert resp.status_code == 200
    body = resp.json()
    assert set(body) == {"output", "token_count", "duration_ms"}
    assert isinstance(body["output"], str)
    assert isinstance(body["token_count"], int)
    assert isinstance(body["duration_ms"], int)


def test_no_mock_rag_anywhere_in_rag_server():
    import glob
    import os

    offenders = []
    for path in glob.glob(os.path.join(os.path.dirname(__file__), "*.py")):
        if "MOCK_RAG" in open(path, encoding="utf-8").read():
            offenders.append(os.path.basename(path))

    assert offenders == [], f"MOCK_RAG가 아직 남아있다: {offenders}"
```

`MOCK_LLM=true`가 `conftest.py`에서 기본 설정되므로 이 테스트는 실제 LLM을 호출하지 않는다.

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_basic.py -v
```
Expected: `test_no_mock_rag_anywhere_in_rag_server`가 `MOCK_RAG가 아직 남아있다: ['main.py', 'vector_db_manager.py']`로 실패하고, health 테스트들도 실패한다.

- [ ] **Step 3: import 블록 교체 (`main.py:13-20`)**

```python
from queue_manager import queue_manager
from document_parser import process_and_extract
from text_chunker import chunk_document
import vector_store
import ingestion
from requirement_extractor import extract_requirements
from retriever import retrieve_evidences
from prompt_builder import build_prompt, call_llm_with_key
from webhook_sender import send_callback, send_failure_callback
```

- [ ] **Step 4: `process_job` 교체 (`main.py:32-178`)**

`MOCK_RAG` 분기를 없애고 파이프라인 4단계를 항상 실행한다. 아래로 통째 교체:

```python
async def process_job(job_data: dict):
    analysis_id = job_data.get("analysisId")
    project_id = job_data.get("projectId")
    s3_paths = job_data.get("s3Paths", [])
    perspectives = job_data.get("qaPerspectives", [])
    custom_prompt = job_data.get("customPrompt", "")
    llm_api_key = job_data.get("llmApiKey")

    logger.info(f"Worker processing RAG job {analysis_id}")

    pipeline_trace = []

    try:
        # Step 1: PARSE
        t0 = time.time()
        logger.info("Downloading and parsing documents from S3...")
        parsed_documents = await process_and_extract(s3_paths, llm_api_key)
        pipeline_trace.append({
            "step": "PARSE",
            "status": "SUCCESS",
            "fileCount": len(parsed_documents),
            "durationMs": int((time.time() - t0) * 1000)
        })

        # Step 2: CHUNK
        t0 = time.time()
        logger.info("Cleaning and chunking parsed text...")
        chunks = []
        for doc in parsed_documents:
            chunks.extend(chunk_document(doc["text"], doc["file_id"], doc["file_name"]))
        pipeline_trace.append({
            "step": "CHUNK",
            "status": "SUCCESS",
            "chunkCount": len(chunks),
            "durationMs": int((time.time() - t0) * 1000)
        })

        # Step 3: INDEX
        t0 = time.time()
        logger.info(f"Indexing {len(chunks)} chunks to Qdrant...")
        vector_store.add_documents(chunks)
        pipeline_trace.append({
            "step": "INDEX",
            "status": "SUCCESS",
            "vectorCount": len(chunks),
            "durationMs": int((time.time() - t0) * 1000)
        })

        # Step 4: EXTRACT
        t0 = time.time()
        logger.info("Extracting requirements from document text...")
        requirements = await extract_requirements(parsed_documents, llm_api_key)
        pipeline_trace.append({
            "step": "EXTRACT",
            "status": "SUCCESS",
            "reqCount": len(requirements),
            "durationMs": int((time.time() - t0) * 1000)
        })

        # Step 5: RETRIEVE + GENERATE
        test_cases = []
        top_score = 0.0
        elapsed_retrieve = 0
        seen_chunk_ids: set = set()

        for req in requirements:
            req_id = req["id"]
            req_text = req["text"]

            t0 = time.time()
            evidences = retrieve_evidences(req_text, exclude_chunk_ids=seen_chunk_ids)
            elapsed_retrieve += int((time.time() - t0) * 1000)
            seen_chunk_ids.update(ev["chunk_id"] for ev in evidences)

            for ev in evidences:
                sc = ev.get("score", 0.0)
                if sc is not None and sc > top_score:
                    top_score = sc

            limited_evidences = evidences[:3]

            prompt = build_prompt(req_text, limited_evidences, custom_prompt, perspectives)

            logger.info(f"Calling LLM for requirement {req_id}...")
            raw_test_cases = await call_llm_with_key(prompt, llm_api_key)

            for tc in raw_test_cases:
                tc["requirementId"] = req_id
                tc["requirementText"] = req_text
                tc["evidence_list"] = limited_evidences

                if "testSteps" in tc:
                    if isinstance(tc["testSteps"], str):
                        tc["testSteps"] = [s.strip() for s in tc["testSteps"].split("\n") if s.strip()]
                    elif not isinstance(tc["testSteps"], list):
                        tc["testSteps"] = []
                else:
                    tc["testSteps"] = []

                test_cases.append(tc)

        pipeline_trace.append({
            "step": "RETRIEVE",
            "status": "SUCCESS",
            "topScore": round(top_score, 4),
            "durationMs": elapsed_retrieve
        })

        formatted_data = {
            "analysisId": analysis_id,
            "status": "COMPLETED",
            "summary": f"RAG 기반 분석 완료. 추출된 요구사항 수: {len(requirements)}, 생성된 테스트 케이스 수: {len(test_cases)}.",
            "testCases": test_cases,
            "errorMessage": None,
            "pipelineTrace": pipeline_trace
        }

        success = await send_callback(analysis_id, formatted_data)
        if not success:
            logger.error(f"Failed to send RAG webhook callback for job {analysis_id}")

    except Exception as e:
        logger.error(f"Error while executing RAG worker for job {analysis_id}: {str(e)}", exc_info=True)
        try:
            error_msg = f"RAG 서버 분석 중 예외 발생: {str(e)}"
            if "AuthenticationError" in type(e).__name__ or "api key" in str(e).lower() or "api_key" in str(e).lower():
                error_msg = "API 키 유효성 검증 실패: 유효하지 않은 API 키이거나 만료되었습니다. 키 설정을 재점검해 주세요."
            await send_failure_callback(analysis_id, error_msg)
        except Exception as err:
            logger.error(f"Failed to send failure callback for job {analysis_id}: {str(err)}")
```

- [ ] **Step 5: lifespan에 지식카드 적재 추가 (`main.py:180-186`)**

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: 지식카드를 Qdrant에 적재한다.
    # Qdrant 연결이나 임베딩 로드가 실패하면 예외가 전파되어 기동이 실패한다 (fail-fast).
    count = ingestion.ingest_knowledge_base()
    logger.info(f"Startup ingestion complete: {count} knowledge documents")

    queue_manager.start_worker(process_job)
    yield
    # Shutdown: Stop worker safely
    await queue_manager.stop_worker()
```

- [ ] **Step 6: preprocess/delete/health 엔드포인트 교체**

`preprocess_file_api` (223-241행)에서 `mock_rag` 분기를 제거:

```python
@app.post("/api/files/preprocess")
async def preprocess_file_api(req: PreprocessRequest):
    logger.info(f"Received preprocess request for file: {req.fileId}, path: {req.s3Path}")
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
```

`delete_vectors_api` (262-278행):

```python
@app.delete("/api/vectors/{fileId}")
async def delete_vectors_api(fileId: str):
    logger.info(f"Received delete vectors request for fileId: {fileId}")
    try:
        vector_store.delete_by_file_id(fileId)
        return {"status": "success", "message": f"Vectors for file {fileId} deleted."}
    except Exception as e:
        logger.error(f"Failed to delete vectors for file {fileId}: {str(e)}")
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"status": "error", "message": f"벡터 삭제 오류: {str(e)}"}
        )
```

`health_check` (321-328행):

```python
@app.get("/health")
async def health_check():
    qdrant_up = vector_store.ping()
    return JSONResponse(
        status_code=status.HTTP_200_OK if qdrant_up else status.HTTP_503_SERVICE_UNAVAILABLE,
        content={
            "status": "healthy" if qdrant_up else "unhealthy",
            "mock_llm": os.getenv("MOCK_LLM", "true").lower() == "true",
            "qdrant": "up" if qdrant_up else "down",
            "queue_size": queue_manager.queue.qsize(),
        },
    )
```

- [ ] **Step 7: `eval_generate_rag`의 vector_db_manager 참조 교체 (`main.py:296-297`)**

```python
    parsed_documents = [{"text": req.text, "file_id": "eval-doc", "file_name": "eval.txt"}]
    chunks = chunk_document(req.text, "eval-doc", "eval.txt")
    vector_store.add_documents(chunks)
```

- [ ] **Step 8: `vector_db_manager.py` 삭제**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && git rm rag_server/vector_db_manager.py
```
Expected: `rm 'rag_server/vector_db_manager.py'`

- [ ] **Step 9: 남은 참조가 없는지 확인**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && git grep -n "vector_db_manager\|MOCK_RAG" -- 'rag_server/*.py'; echo "exit=$?"
```
Expected: 매칭 없음, `exit=1` (`git grep`은 추적되지 않는 `.venv/`를 건너뛴다)

- [ ] **Step 10: 전체 테스트 실행**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest -v
```
Expected: `42 passed` (vector_store 7 + text_chunker 7 + ingestion 5 + retriever 7 + prompt_builder 10 + basic 6). 앞선 태스크에서 테스트를 조정했다면 총계는 달라질 수 있다 — 반드시 확인할 것은 **0 failed, 0 error**이다.

- [ ] **Step 11: 커밋**

```bash
git add rag_server/main.py rag_server/test_basic.py
git commit -m "refactor: main.py를 Qdrant 파이프라인으로 전환하고 MOCK_RAG 완전 제거

- vector_db_manager -> vector_store 참조 교체, vector_db_manager.py 삭제
- process_job의 MOCK_RAG 분기 제거, PARSE/CHUNK/INDEX/EXTRACT/RETRIEVE 항상 실행
- preprocess/delete 엔드포인트의 mock 우회 경로 제거
- lifespan에서 지식카드 적재(실패 시 fail-fast)
- /health가 Qdrant 연결을 실제로 검증하고 미가용 시 503 반환"
```

---

## Task 9: 통합 스모크 — 실제 Qdrant 컨테이너 위에서 동작 확인

**Files:**
- Create: `rag_server/test_integration_qdrant.py`

**Interfaces:**
- Consumes: Task 3-8의 전 모듈. 실행 중인 Qdrant 서버(`QDRANT_URL`).
- Produces: `pytest -m integration` 마커로 분리된 통합 테스트. Task 10의 CI가 이걸 돌린다.

- [ ] **Step 1: 통합 테스트 작성**

Create `rag_server/test_integration_qdrant.py`:

```python
"""실제 Qdrant 서버를 대상으로 하는 통합 테스트.

QDRANT_URL이 ':memory:'가 아닐 때만 실행된다(CI의 서비스 컨테이너 / 로컬 docker compose).
실행: QDRANT_URL=http://localhost:6333 pytest test_integration_qdrant.py -m integration -v
"""
import os
import uuid

import pytest
from langchain_core.documents import Document
from langchain_core.embeddings import DeterministicFakeEmbedding

pytestmark = pytest.mark.integration

_QDRANT_URL = os.getenv("QDRANT_URL", ":memory:")

skip_without_server = pytest.mark.skipif(
    _QDRANT_URL == ":memory:",
    reason="실제 Qdrant 서버가 필요하다 (QDRANT_URL 환경변수 설정 필요)",
)


@pytest.fixture
def live_store(monkeypatch):
    """테스트마다 고유 컬렉션을 써서 서로 간섭하지 않게 한다."""
    collection = f"itest_{uuid.uuid4().hex[:8]}"
    monkeypatch.setenv("QDRANT_COLLECTION", collection)

    import importlib
    import vector_store as vs
    importlib.reload(vs)
    monkeypatch.setattr(vs, "build_embeddings", lambda: DeterministicFakeEmbedding(size=384))
    vs.reset_vector_store()

    store = vs.get_vector_store()
    yield vs
    store.client.delete_collection(collection)
    vs.reset_vector_store()
    importlib.reload(vs)


@skip_without_server
def test_upsert_search_delete_roundtrip_against_live_qdrant(live_store):
    live_store.add_documents([
        Document(page_content="로그인 실패 시 401 Unauthorized를 반환해야 한다", metadata={
            "chunk_id": "CHNK-IT-0000", "file_id": "FILE-IT",
            "file_name": "spec.pdf", "section_title": "1. 인증", "source_type": "document",
        }),
        Document(page_content="인증 실패 응답 코드 검증은 부정 시나리오의 기본이다", metadata={
            "chunk_id": "QA-IT-0000", "file_id": "__knowledge_base__",
            "file_name": "istqb.json", "section_title": "부정 테스트", "source_type": "knowledge_card",
        }),
    ])

    hits = live_store.search("로그인 실패 시 401 Unauthorized를 반환해야 한다", k=5, score_threshold=0.0)
    assert {doc.metadata["chunk_id"] for doc, _ in hits} == {"CHNK-IT-0000", "QA-IT-0000"}

    live_store.delete_by_file_id("FILE-IT")

    remaining = live_store.search("인증", k=5, score_threshold=0.0)
    assert {doc.metadata["chunk_id"] for doc, _ in remaining} == {"QA-IT-0000"}


@skip_without_server
def test_ping_succeeds_against_live_qdrant(live_store):
    assert live_store.ping() is True


@skip_without_server
def test_dimension_mismatch_is_detected(live_store):
    with pytest.raises(RuntimeError, match="벡터 차원 불일치"):
        live_store.ensure_collection(live_store.get_vector_store().client, dim=768)
```

- [ ] **Step 2: `pytest.ini`에 마커 등록**

`rag_server/pytest.ini`를 아래로 교체:

```ini
[pytest]
asyncio_mode = auto
markers =
    integration: 실제 Qdrant 서버가 필요한 통합 테스트
```

- [ ] **Step 3: 서버 없이도 스킵되는지 확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest test_integration_qdrant.py -v
```
Expected: `3 skipped` — `conftest.py`가 `QDRANT_URL=:memory:`를 설정하므로 전부 스킵된다.

- [ ] **Step 4: 실제 Qdrant를 띄우고 통합 테스트 실행**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && docker run -d --name qdrant-itest -p 6333:6333 qdrant/qdrant:v1.12.4
sleep 5
cd rag_server && QDRANT_URL=http://localhost:6333 .venv/bin/python -m pytest test_integration_qdrant.py -v -p no:cacheprovider
```
Expected: `3 passed`

`conftest.py`가 import 시점에 `QDRANT_URL=:memory:`를 강제 설정하므로 위 명령의 환경변수가 덮어써진다. 그러면 `conftest.py`의 해당 줄을 아래처럼 바꿔 외부 지정을 존중하게 한다:

```python
os.environ.setdefault("QDRANT_URL", ":memory:")
```

(`os.environ["QDRANT_URL"] = ":memory:"` → `os.environ.setdefault(...)`). 나머지 두 줄(`QDRANT_COLLECTION`, `MOCK_LLM`)도 동일하게 `setdefault`로 바꾼다. 바꾼 뒤 Step 3과 Step 4를 모두 다시 실행해 각각 `3 skipped`, `3 passed`가 나오는지 확인한다.

- [ ] **Step 5: 컨테이너 정리**

Run:
```bash
docker rm -f qdrant-itest
```
Expected: `qdrant-itest`

- [ ] **Step 6: 전체 단위 테스트 재확인**

Run:
```bash
cd rag_server && .venv/bin/python -m pytest -v
```
Expected: 0 failed, 0 error. 통합 테스트 3건은 skipped.

- [ ] **Step 7: 커밋**

```bash
git add rag_server/test_integration_qdrant.py rag_server/pytest.ini rag_server/conftest.py
git commit -m "test: 실제 Qdrant 서버 대상 통합 테스트 추가

- upsert -> search -> payload 필터 삭제 왕복 검증
- 테스트마다 고유 컬렉션을 써서 간섭 방지, 종료 시 정리
- QDRANT_URL 미지정 시 자동 skip되어 로컬 단위 테스트를 방해하지 않음"
```

---

## Task 10: CI 워크플로우 및 배포 반영

**Files:**
- Create: `.github/workflows/rag-tests.yml`
- Modify: `.github/workflows/deploy-ec2.yml`

**Interfaces:**
- Consumes: Task 2의 `requirements.txt`, Task 9의 `integration` 마커, Task 1의 qdrant compose 서비스.
- Produces: PR/push 시 자동 실행되는 rag_server 테스트 게이트.

- [ ] **Step 1: 기존 워크플로우 확인**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && ls .github/workflows/ && grep -n "docker compose up" .github/workflows/deploy-ec2.yml
```
Expected: `deploy-ec2.yml`, `deploy.yml`, `eval.yml`이 보이고, `deploy-ec2.yml`에서 `docker compose up -d --build backend rag-server llm-server` 라인의 행 번호가 출력된다.

- [ ] **Step 2: 테스트 워크플로우 생성**

Create `.github/workflows/rag-tests.yml`:

```yaml
name: rag-server tests

on:
  push:
    paths:
      - 'rag_server/**'
      - '.github/workflows/rag-tests.yml'
  pull_request:
    paths:
      - 'rag_server/**'
      - '.github/workflows/rag-tests.yml'

jobs:
  test:
    runs-on: ubuntu-latest

    services:
      qdrant:
        image: qdrant/qdrant:v1.12.4
        ports:
          - 6333:6333

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-python@v5
        with:
          python-version: '3.10'
          cache: 'pip'
          cache-dependency-path: rag_server/requirements.txt

      - name: Install dependencies (CPU-only torch)
        working-directory: rag_server
        run: |
          python -m pip install --upgrade pip
          pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt

      - name: Wait for Qdrant
        run: |
          for i in $(seq 1 30); do
            if curl -sf http://localhost:6333/healthz; then echo " qdrant ready"; exit 0; fi
            sleep 2
          done
          echo "qdrant did not become ready in time" >&2
          exit 1

      - name: Unit tests (Qdrant local mode, no server)
        working-directory: rag_server
        run: python -m pytest -v -m "not integration"

      - name: Integration tests (live Qdrant service container)
        working-directory: rag_server
        env:
          QDRANT_URL: http://localhost:6333
        run: python -m pytest test_integration_qdrant.py -v -m integration
```

- [ ] **Step 3: 워크플로우 YAML 문법 검증**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && python3 -c "
import yaml, sys
doc = yaml.safe_load(open('.github/workflows/rag-tests.yml'))
assert 'qdrant' in doc['jobs']['test']['services']
steps = [s.get('name', '') for s in doc['jobs']['test']['steps']]
print('steps:', steps)
print('YAML_OK')
"
```
Expected: `YAML_OK`와 함께 5개 스텝 이름이 출력된다. `yaml` 모듈이 없으면 `python3 -m pip install pyyaml` 후 재실행.

- [ ] **Step 4: 배포 워크플로우에 qdrant 서비스 추가**

`.github/workflows/deploy-ec2.yml`에서 `docker compose up -d --build backend rag-server llm-server` 라인을 아래로 교체한다(들여쓰기는 원문 그대로 유지):

```
            docker compose up -d qdrant
            docker compose up -d --build backend rag-server llm-server
```

`qdrant`는 빌드 대상이 아니라 이미지 pull이므로 `--build`와 분리한다.

- [ ] **Step 5: 배포 스크립트에 QDRANT_URL 부재 시 안내 추가**

같은 파일의 위 두 줄 바로 앞에 삽입:

```
            grep -q '^QDRANT_URL=' .env || echo 'QDRANT_URL=http://qdrant:6333' >> .env
            grep -q '^QDRANT_COLLECTION=' .env || echo 'QDRANT_COLLECTION=yeonam_knowledge' >> .env
            sed -i '/^MOCK_RAG=/d' .env
```

EC2 호스트의 기존 `.env`에는 `QDRANT_URL`이 없고 `MOCK_RAG`가 남아있다. 위 세 줄이 최초 배포 시 이를 자동 보정한다.

- [ ] **Step 6: deploy-ec2.yml 문법 검증**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && python3 -c "
import yaml
yaml.safe_load(open('.github/workflows/deploy-ec2.yml'))
print('DEPLOY_YAML_OK')
"
```
Expected: `DEPLOY_YAML_OK`

- [ ] **Step 7: 로컬 전체 스택 기동 검증**

Run:
```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination && docker compose up -d qdrant rag-server
sleep 30
curl -s http://localhost:8000/health | tee /dev/stderr | grep -q '"qdrant":"up"' && echo HEALTH_OK
```
Expected: `{"status":"healthy","mock_llm":...,"qdrant":"up","queue_size":0}` 와 `HEALTH_OK`

실패하면 로그를 확인한다:
```bash
docker compose logs --tail=80 rag-server
```
`Startup ingestion complete: <N> knowledge documents` 로그가 있어야 한다. 임베딩 모델 첫 다운로드로 30초 이상 걸릴 수 있으니 `sleep`을 늘려 재시도한다.

- [ ] **Step 8: 스택 정리**

Run:
```bash
docker compose down
```
Expected: 컨테이너들이 정지·제거된다. `qdrant_data` 볼륨은 유지된다.

- [ ] **Step 9: 커밋**

```bash
git add .github/workflows/rag-tests.yml .github/workflows/deploy-ec2.yml
git commit -m "ci: rag_server 테스트 워크플로우 추가 및 배포에 Qdrant 반영

- Qdrant 서비스 컨테이너 위에서 단위/통합 테스트 분리 실행
- deploy-ec2에서 qdrant 서비스 기동 및 .env의 QDRANT_* 자동 보정, MOCK_RAG 제거"
```

---

## 완료 확인 체크리스트

모든 태스크를 마친 뒤 아래를 순서대로 실행해 전부 통과하는지 확인한다.

- [ ] `cd rag_server && .venv/bin/python -m pytest -v` → 0 failed, 0 error
- [ ] `git grep -n "MOCK_RAG\|vector_db_manager\|faiss\|_fallback_string_search" -- rag_server .env.example docker-compose.yml .github` → 매칭 없음 (`git grep`을 쓰는 이유: `.venv/`의 서드파티 코드를 훑지 않는다)
- [ ] `git status --porcelain` → 미커밋 변경 없음
- [ ] `docker compose up -d && sleep 40 && curl -s localhost:8000/health` → `"status":"healthy"`, `"qdrant":"up"`
- [ ] `docker compose restart rag-server && sleep 40 && curl -s localhost:8000/health` → 재기동 후에도 `healthy` (지식카드가 중복 삽입 없이 재사용되는지 로그로 확인)
- [ ] `docker compose down && docker compose up -d && sleep 40 && docker compose exec qdrant sh -c "wget -qO- http://localhost:6333/collections/yeonam_knowledge"` → `points_count`가 0이 아님 (named volume 영속성 확인 — 이번 리팩토링의 핵심 목표)
