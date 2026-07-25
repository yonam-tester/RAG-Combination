# RAG 파이프라인 LangChain + Qdrant 리팩토링 설계 명세서

**날짜:** 2026-07-25
**목적:** `rag_server`의 자체 구현 FAISS 인메모리 파이프라인을 LangChain 표준 컴포넌트 + Qdrant(외부 영속 벡터DB)로 교체하여, (1) 업계 표준 RAG 스택 경험을 포트폴리오에 반영하고 (2) 인메모리 인덱스의 실질적 문제(재시작 시 데이터 소실, 청크 추가마다 전체 재빌드)를 해결한다.

---

## 1. 배경 및 목표

### 현재 구조의 문제
- `vector_db_manager.py`: FAISS `IndexFlatIP`를 순수 인메모리로 운영. `add_chunks()` 호출마다 전체 청크를 다시 임베딩하고 인덱스를 통째로 재빌드(`_rebuild_faiss_index`). 프로세스가 재시작되면 인덱스가 전부 소실된다.
- `retriever.py`: 문서 청크 벡터 검색과 정적 QA 지식카드 키워드 오버랩 검색을 별도 경로로 수행한 뒤 점수를 수동으로 병합. 로직이 이중화되어 있다.
- `MOCK_RAG`/FAISS 미탑재 시 `_fallback_string_search`(단어 오버랩 기반 문자열 매칭)로 조용히 폴백하는 경로가 다수 존재해 실패 상황이 은폐된다.
- 진행 중인 K8s 전환 설계(`2026-07-23-kubernetes-migration-design.md`)에서 rag-server의 상태 보존 문제(in-memory queue)를 다루고 있는데, 벡터 인덱스도 동일한 종류의 상태 문제를 갖고 있어 함께 정리할 필요가 있다.

### 목표
- 포트폴리오 가치와 실제 아키텍처 개선을 동시에 달성 — LangChain의 `VectorStore`/`Retriever`/`TextSplitter`/LCEL 체인 패턴을 도입하고, 벡터 데이터를 Qdrant(외부 컨테이너, 영속 볼륨)로 이전한다.
- 이 리팩토링을 K8s 전환보다 먼저 수행한다. 벡터 인덱스가 외부화되면 rag-server 자체는 완전히 stateless가 되어, 이후 K8s 매니페스트 설계에서 상태 문제가 Qdrant(하나의 StatefulSet/PVC)로 국한된다.

---

## 2. 아키텍처

```
docker-compose.yml
├── qdrant (신규)             # 단독 컨테이너, REST(6333)/gRPC(6334), named volume로 영속화
├── rag-server
│   ├── ingestion.py (신규)    # 기동 시 rag_chunks.jsonl + knowledge_base/*.json → Qdrant
│   │                          #   "yeonam_knowledge" 컬렉션에 deterministic ID로 upsert
│   ├── vector_store.py (신규, vector_db_manager.py 대체)
│   │                          # QdrantVectorStore + HuggingFaceEmbeddings 래핑
│   ├── text_chunker.py (부분 수정)
│   │                          # 한국어 전처리(clean_text, detect_section_title) 유지
│   │                          # 범용 길이 분할만 RecursiveCharacterTextSplitter로 교체
│   ├── retriever.py (단순화)   # vectorstore.as_retriever() 단일 호출로 축소
│   └── prompt_builder.py (수정) # LCEL 체인: retriever | prompt 조립 | ChatLiteLLM | JsonOutputParser | fallback 보정
```

FAISS 인메모리 인덱스를 Qdrant라는 외부 영속 스토어로 이전한다. `rag-server`는 검색 로직 자체에서 상태를 갖지 않으며, Qdrant가 유일한 stateful 컴포넌트가 된다.

---

## 3. 컴포넌트 상세

| 컴포넌트 | 역할 | 비고 |
|---|---|---|
| `HuggingFaceEmbeddings` | 로컬 임베딩 (`all-MiniLM-L6-v2`, 기존과 동일 모델) | `langchain-huggingface` 패키지. API 비용 없음. MOCK_LLM과는 무관한 별개 설정 |
| `QdrantVectorStore` | 단일 컬렉션 `yeonam_knowledge`에 문서 청크 + QA 지식카드를 함께 저장/검색 | payload: `source_type`(`document` \| `knowledge_card`), `file_id`, `chunk_id`, `section_title`, `source_name` |
| `RecursiveCharacterTextSplitter` | 범용 길이 기반 분할 (chunk_size 500~1000, overlap 150) | 한국어 전처리 결과를 LangChain `Document`로 감싼 뒤 적용. 섹션 제목 감지 등 도메인 로직은 전처리 단계에서 그대로 수행 |
| Retriever | `vectorstore.as_retriever(search_kwargs={"k": 5, "score_threshold": 0.35})` | 문서 청크 + QA 지식카드가 하나의 검색으로 함께 랭킹됨. `exclude_chunk_ids`는 검색 결과에 대한 후처리 필터로 유지 |
| `ChatLiteLLM` | LLM 호출 래퍼 | 내부적으로 litellm을 그대로 사용하므로 `LLM_MODEL` 환경변수 기반 모델 스왑, 요청별 동적 API 키 주입 방식은 변경 없음 |
| LCEL 체인 | `retriever → build_prompt(evidence 포맷) → ChatLiteLLM → JsonOutputParser → 필드 fallback 보정` | JSON 블록 추출은 `JsonOutputParser`가 담당. 테스트케이스 필드별 기본값 보정(현재 `call_llm_with_key` 내부 로직)은 체인 이후 별도 후처리 함수로 유지 |
| `ingestion.py` | 서버 기동 시 정적 지식(문서 청크 아님) 적재 | `chunk_id`를 Qdrant point ID로 사용하는 upsert라 재기동해도 중복 삽입되지 않음 |

### 제거되는 것
- `HAS_FAISS` try/except 폴백 및 FAISS/sentence-transformers 미탑재 시 동작
- `provider == "bedrock"` stub 분기
- `_fallback_string_search` (단어 오버랩 기반 문자열 매칭 폴백)
- `retrieve_evidences`의 `MOCK_RAG` 분기 및 하드코딩된 mock 근거 데이터
- `retriever.py`의 이중 검색(FAISS 벡터 검색 + QA 카드 키워드 오버랩) 후 수동 점수 병합 로직

### 유지되는 것
- `document_parser.py` (pymupdf/docx 직접 파싱) — 출력 텍스트만 LangChain `Document`로 감싸 이후 파이프라인에 연결
- `MOCK_LLM` 환경변수 (LLM 생성 호출만 mock 처리, 이번 리팩토링 범위 밖)
- 웹훅 콜백 payload, `/analyze`·`/files/{fileId}`·`/api/eval/generate` 등 외부 API 계약

---

## 4. 데이터 흐름

**문서 업로드 → 인덱싱**
1. `document_parser.py`로 텍스트 파싱 (변경 없음)
2. `text_chunker.py`: `clean_text`/`detect_section_title`로 노이즈 제거 및 섹션 태깅 → 섹션 단위 텍스트를 `Document(page_content=..., metadata={file_id, file_name, section_title})`로 생성 → `RecursiveCharacterTextSplitter.split_documents()`로 분할
3. `vector_store.add_documents(chunks)` → Qdrant에 upsert (`chunk_id`를 point ID로, `source_type="document"` payload 포함)

**요구사항 검증 → 테스트케이스 생성**
1. `retriever.py`: `vectorstore.as_retriever().invoke(query)` 한 번 호출 → 문서 청크와 QA 지식카드가 score 기준으로 함께 반환
2. `exclude_chunk_ids`에 포함된 청크는 결과에서 후처리로 제외 (동일 분석 작업 내 중복 근거 방지, 기존 동작과 동일)
3. LCEL 체인 실행: `build_prompt`로 XML 프롬프트 조립 → `ChatLiteLLM` 호출 → `JsonOutputParser`로 JSON 추출 → 필드 fallback 보정 → 테스트케이스 리스트 반환

**파일 삭제**
- `vector_store.delete(filter={"file_id": fileId})` — Qdrant payload 필터 삭제로 `delete_file_chunks`를 대체. 전체 재빌드 불필요, 즉시 반영

**서버 기동**
- `ingestion.py`가 `rag_chunks.jsonl` + `knowledge_base/*.json`을 읽어 `yeonam_knowledge` 컬렉션에 upsert. deterministic ID 기반이라 여러 번 재기동해도 안전

---

## 5. 에러 처리

| 상황 | 처리 |
|---|---|
| Qdrant 연결 실패 (기동 시) | `/health`가 Qdrant ping 결과를 반영해 `unhealthy` 반환 (현재 `/health`가 의존성 검증 없이 항상 `healthy`만 반환하는 문제도 함께 해결) |
| Qdrant 연결 실패 (검색/삽입 중) | 예외를 그대로 전파해 job을 실패 처리하고 webhook에 에러 상태 전달. 조용한 mock 폴백은 두지 않는다 — Qdrant가 필수 의존성이 되었으므로 실패는 명확하게 드러나야 한다 |
| 임베딩 모델 로드 실패 | 서버 기동 자체를 fail-fast로 실패시킨다 (현재처럼 폴백 모드로 조용히 넘어가지 않음) |
| LLM 응답 JSON 파싱 실패 | 기존과 동일하게 `MOCK_TEST_CASES["REQ-001"]` 폴백 유지 (LLM 응답 처리는 이번 리팩토링 범위 밖) |
| 임베딩 모델 변경으로 컬렉션 벡터 차원 불일치 | ingestion 단계에서 차원 검증 후 불일치 시 에러 로그로 명시. 자동 마이그레이션은 범위 밖이며 수동 재적재를 가정한다 |

---

## 6. 테스트 전략

- **단위 테스트**: `langchain_core.vectorstores.InMemoryVectorStore` + `FakeEmbeddings`로 Qdrant 없이 retriever/체인 로직 검증 (`test_basic.py`, `conftest.py` 픽스처 교체)
- **통합 테스트/CI**: GitHub Actions에서 Qdrant를 서비스 컨테이너로 띄우고 실제 upsert → search 검증. 로컬은 `docker-compose up`으로 동일 환경 재현
- **회귀 확인**: `/analyze`, `/files/{fileId}`, `/api/eval/generate` 엔드포인트가 인터페이스 변경 없이 동작하는지 e2e로 확인 (webhook payload, API 응답 스키마는 변경하지 않음)

---

## 7. 변경 파일 목록

| 파일 | 변경 유형 |
|---|---|
| `docker-compose.yml` | 수정 — `qdrant` 서비스 및 named volume 추가 |
| `rag_server/requirements.txt` | 수정 — `faiss-cpu`, `sentence-transformers` 제거, `langchain`, `langchain-community`(ChatLiteLLM), `langchain-huggingface`, `langchain-qdrant`, `qdrant-client` 추가. 기존 `litellm` 의존성은 유지 |
| `rag_server/vector_db_manager.py` | 삭제 — `vector_store.py`로 대체 |
| `rag_server/vector_store.py` | 신규 — `QdrantVectorStore` + `HuggingFaceEmbeddings` 래핑, add/delete/search 인터페이스 |
| `rag_server/ingestion.py` | 신규 — 기동 시 정적 지식(rag_chunks.jsonl, knowledge_base/*.json) Qdrant 적재 |
| `rag_server/text_chunker.py` | 수정 — 범용 분할 로직만 `RecursiveCharacterTextSplitter`로 교체, 한국어 전처리는 유지 |
| `rag_server/retriever.py` | 수정 — 이중 검색/병합 로직 제거, 단일 retriever 호출로 축소 |
| `rag_server/prompt_builder.py` | 수정 — LCEL 체인 구성, `JsonOutputParser` 도입, `MOCK_RAG` 분기 제거(`MOCK_LLM` 분기는 유지) |
| `rag_server/main.py` | 수정 — `vector_db_manager` → `vector_store` 참조 교체, `/health`에 Qdrant 연결 검증 추가 |
| `rag_server/conftest.py`, `rag_server/test_basic.py` | 수정 — `InMemoryVectorStore`/`FakeEmbeddings` 기반 픽스처로 교체 |
| `.env.example` | 수정 — `EMBEDDING_PROVIDER`/`MOCK_RAG` 관련 항목 정리, `QDRANT_URL` 추가 |
| `.github/workflows/*.yml` | 수정 — CI에 Qdrant 서비스 컨테이너 추가 |

---

## 8. 범위 밖 (이번 구현 제외)

- Kubernetes 매니페스트/StatefulSet 설계 (`2026-07-23-kubernetes-migration-design.md`에서 이 리팩토링 이후 별도로 다룸)
- LLM 응답 처리 로직(`MOCK_LLM`, JSON 필드 fallback 보정의 세부 규칙) 변경
- 임베딩 모델을 OpenAI/Bedrock API 기반으로 전환하는 작업
- Qdrant 컬렉션의 다중 테넌시/네임스페이스 분리, 벡터 차원 변경 시 자동 마이그레이션
- 기존 rag_chunks.jsonl 생성 스크립트(`embedded/generate_rag_chunks.py`) 자체의 로직 변경
