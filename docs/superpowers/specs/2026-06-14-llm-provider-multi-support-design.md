# LLM 멀티 프로바이더 지원 및 MockLlmClient 폐기 설계

## 목표

`MockLlmClient`를 완전히 제거하고, AWS Bedrock 외에 OpenAI / LiteLLM / Anthropic 등 API 키만으로 사용 가능한 LLM 프로바이더를 지원한다. 이를 통해 AWS 자격증명 없이도 시스템 전체를 실행할 수 있게 한다.

## 배경 및 문제 인식

- 현재 `LlmClientConfiguration`의 기본값이 `MockLlmClient`이므로 (`matchIfMissing = true`), 설정 없이 실행하면 Mock이 활성화됨
- `MockLlmClient.generateSupplementaryScenarios()`는 프로젝트 도메인과 무관한 "보완 시나리오 N" 플레이스홀더를 반환 — 입력 파라미터(summary, qaPerspective)를 완전히 무시
- `MockLlmClient.generateTestCases()`도 인증/로그인 고정 시나리오를 반환 — 어떤 프로젝트든 동일한 도메인 응답
- AWS Bedrock만 지원하므로 OpenAI / LiteLLM / Anthropic API 키를 가진 사용자는 사용 불가

## 아키텍처

### LlmClient 구현체

```
LlmClient (interface)
├── BedrockLlmClient      — AWS SDK, llm.provider=bedrock
└── OpenAiCompatibleLlmClient — HTTP RestTemplate, llm.provider=openai (기본값)
                               OpenAI / LiteLLM / Anthropic proxy 공용
```

### 프로바이더 선택 (LlmClientConfiguration)

| llm.provider | 구현체 | 비고 |
|---|---|---|
| `openai` (기본값) | `OpenAiCompatibleLlmClient` | api.openai.com/v1 |
| `litellm` | `OpenAiCompatibleLlmClient` | localhost:4000/v1 |
| `bedrock` | `BedrockLlmClient` | AWS SDK |

`llm.provider` 미설정 시 `openai`가 기본값으로 적용된다 (`matchIfMissing = true`를 openai 빈에 부여).

### 설정 예시

```properties
# OpenAI 직접 사용
llm.provider=openai
llm.api-key=sk-...
llm.model=gpt-4o

# LiteLLM 프록시 (Claude, Gemini 등 any provider)
llm.provider=openai
llm.base-url=http://localhost:4000/v1
llm.api-key=anything
llm.model=claude-3-5-sonnet-20241022

# AWS Bedrock
llm.provider=bedrock
aws.region=us-east-1
aws.bedrock.model-id=anthropic.claude-3-haiku-20240307-v1:0
```

## 변경 파일

### 삭제
- `backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java`

### 신규
- `backend/src/main/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClient.java`

### 수정
- `backend/src/main/java/com/yeonam/tester/config/LlmClientConfiguration.java`
- `backend/src/main/java/com/yeonam/tester/llm/BedrockLlmClient.java` (프롬프트 개선)
- `backend/src/test/java/com/yeonam/tester/Phase9Tests.java` (Mockito 전환)

## OpenAiCompatibleLlmClient 설계

OpenAI `/chat/completions` 스펙을 따르는 RestTemplate 기반 HTTP 클라이언트.

### 생성자 주입 필드
- `baseUrl`: 기본값 `https://api.openai.com/v1`
- `apiKey`: 필수
- `model`: 기본값 `gpt-4o`

### HTTP 요청 구조
```
POST {baseUrl}/chat/completions
Content-Type: application/json
Authorization: Bearer {apiKey}

{
  "model": "{model}",
  "messages": [
    {"role": "system", "content": "{systemPrompt}"},
    {"role": "user",   "content": "{userPrompt}"}
  ],
  "max_tokens": 4096
}
```

### 응답 파싱
`choices[0].message.content` 추출 후 마크다운 코드블록(` ```json `) 제거 처리 (BedrockLlmClient와 동일 로직).

## 프롬프트 설계 (BedrockLlmClient + OpenAiCompatibleLlmClient 공통)

### generateSupplementaryScenarios

**System:**
```
당신은 소프트웨어 QA 아키텍트입니다. 기존 테스트 케이스의 검증 사각지대를 보완하는
추가 테스트 시나리오를 설계합니다.
출력은 반드시 마크다운 없는 순수 JSON 배열만 반환하십시오.
```

**User:**
```
[분석 요약]
{summary}

[QA 검증 관점]
{qaPerspective}

[기존 TC 목록] (아래 항목과 중복되지 않게 생성하세요)
- {tc1}
- {tc2}
...

반드시 {count}개만 생성하세요. 형식:
[{"testCaseName":"...","testScenario":"...","expectedResult":"...","priority":"HIGH|MEDIUM|LOW"}]
```

`max_tokens`: 2048 (4개 필드, 충분)

### generateTestCases

기존 `BedrockLlmClient` 프롬프트 유지 (`OpenAiCompatibleLlmClient`에 동일하게 적용).

## Phase9Tests Mockito 전환

### 삭제 (2개)
MockLlmClient를 직접 `new`로 생성하는 단위 테스트는 MockLlmClient가 없어지므로 삭제:
- `testMockLlmClientGeneratesCorrectSupplementaryCount()`
- `testMockLlmClientGeneratesOneScenario()`

### 유지 및 @MockBean 전환 (3개)
```java
@SpringBootTest
class Phase9Tests {

    @MockBean
    private LlmClient llmClient;

    @BeforeEach
    void setUpLlmMock() {
        given(llmClient.generateSupplementaryScenarios(any(), any(), any(), anyInt()))
            .willReturn("""
                [
                  {"testCaseName":"보완 TC-1","testScenario":"시나리오 1","expectedResult":"결과 1","priority":"HIGH"},
                  {"testCaseName":"보완 TC-2","testScenario":"시나리오 2","expectedResult":"결과 2","priority":"MEDIUM"},
                  {"testCaseName":"보완 TC-3","testScenario":"시나리오 3","expectedResult":"결과 3","priority":"LOW"}
                ]
                """);
        given(llmClient.generateTestCases(any(), any(), any()))
            .willReturn("{}");
    }

    // 기존 3개 @Transactional 통합 테스트 그대로 유지
}
```

## 비고

- `SupplementaryScenarioDto` 필드 변경 없음 (4개 필드 유지) — 렌더링 확장은 별도 스펙
- `MOCK_LLM` 환경변수는 rag_server(Python) 전용이며 이번 변경 범위 외
- `llm.api-key` 미설정 시 Spring Boot 기동 오류 발생 — 운영 배포 전 필수 설정
