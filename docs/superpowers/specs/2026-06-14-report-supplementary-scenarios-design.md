# 보고서 보완 시나리오 생성 기능 설계

**날짜**: 2026-06-14  
**상태**: 확정

## 개요

현재 분석 결과로 생성된 TestCase는 LLM/Mock이 반환하는 수에 고정된다. 보고서 생성 시점에 사용자가 원하는 총 시나리오 수(2~10)를 지정하면, 기존 TestCase가 부족할 경우 LLM을 한 번 더 호출해 차이만큼 보완 시나리오를 생성하고 보고서에 합산 출력한다.

## 목표

- 기존 TestCase 데이터 및 RAG 분석 결과를 변경하지 않는다.
- 보완 시나리오는 Report 단위로만 저장된다 (재사용 불필요).
- 최대 10개 제한으로 LLM 환각을 억제한다.
- LLM 보완 호출 실패 시 기존 TC만으로 보고서를 생성하는 graceful fallback을 제공한다.

## 데이터 흐름

```
사용자: 보고서 생성 UI에서 targetScenarioCount (2~10) 선택
  │
  ▼
POST /api/analyses/{id}/reports
  { reportFormat, testCaseIds, targetScenarioCount: N }
  │
  ▼
ReportService.generateReport()
  ├─ 기존 TestCase 수 조회 (예: 2개)
  ├─ targetScenarioCount > 기존 수 → 차이(N-2)개 보완 요청
  ├─ LlmClient.generateSupplementaryScenarios(context, count) 호출
  ├─ 응답 → Report.additionalScenarios(TEXT) 에 JSON 직렬화 저장
  └─ 렌더링: 원본 TestCase + 보완 시나리오 합산 출력
```

### LLM 보완 호출 컨텍스트 (DB에서 조달, S3 재다운로드 없음)

| 항목 | 출처 |
|---|---|
| QA 관점 | `AnalysisJob.qaPerspective` |
| 분석 요약 | `AnalysisJob.summary` |
| 기존 TC 이름 목록 | `TestCase.testCaseName` 목록 (중복 방지용) |
| 필요 개수 | `targetScenarioCount - existingCount` |

## 변경 컴포넌트

### 백엔드

| 컴포넌트 | 변경 내용 |
|---|---|
| `ReportCreateRequest` | `targetScenarioCount` 필드 추가 (int, 기본값 0 = 현재 수 유지) |
| `Report` (domain) | `additional_scenarios TEXT` 컬럼 추가 |
| `LlmClient` (interface) | `generateSupplementaryScenarios(String summary, String qaPerspective, List<String> existingNames, int count)` 메서드 추가 |
| `BedrockLlmClient` | 위 메서드 구현 — 중복 방지 프롬프트로 N개 생성 |
| `MockLlmClient` | 위 메서드 구현 — count 수만큼 고정 JSON 반환 |
| `ReportService` | 보완 시나리오 생성 로직 추가, `Report.additionalScenarios` 저장 |
| `ReportAssemblyService` | `Report.additionalScenarios` JSON 역직렬화 후 model에 포함 |
| `ReportRenderEngine` | 원본 TC 렌더링 후 "보완 시나리오" 섹션 추가 |

### 프론트엔드

| 컴포넌트 | 변경 내용 |
|---|---|
| `AnalysisResultPage` | 보고서 생성 패널에 시나리오 수 입력 추가 (숫자 입력 또는 슬라이더, 범위 2~10, 기본값=현재 TC 수) |
| `api.ts` (ReportCreateRequest 타입) | `targetScenarioCount?: number` 추가 |

## 보완 시나리오 JSON 구조 (`additional_scenarios` 컬럼)

```json
[
  {
    "testCaseName": "보완 시나리오 이름",
    "testScenario": "시나리오 내용",
    "expectedResult": "기대 결과",
    "priority": "MEDIUM"
  }
]
```

기존 TestCase 전체 필드(precondition, testSteps, Evidence 등)보다 단순화 — 보완 시나리오는 LLM 추가 호출 결과이므로 핵심 4필드만 유지한다.

## 보완 LLM 프롬프트 (BedrockLlmClient)

```
당신은 QA 아키텍트입니다. 아래 기존 테스트 케이스와 중복되지 않는 새로운 테스트 케이스를 {N}개 생성하세요.

[기존 테스트 케이스 이름 목록]
{existingNames}

[QA 관점]
{qaPerspective}

[분석 요약]
{summary}

반드시 {N}개만 생성하고, 다음 형식의 순수 JSON 배열로만 반환하세요:
[{"testCaseName":"...","testScenario":"...","expectedResult":"...","priority":"HIGH|MEDIUM|LOW"}]
```

## 유효성 검사 & 엣지 케이스

| 상황 | 처리 방식 |
|---|---|
| `targetScenarioCount` = 0 또는 미입력 | LLM 추가 호출 없이 기존 TC만 보고서 출력 |
| `targetScenarioCount` ≤ 기존 TC 수 | 기존 TC만 출력 (줄이는 기능 미구현) |
| `targetScenarioCount` > 10 | 백엔드 400 Bad Request |
| LLM 보완 호출 실패 | 기존 TC만으로 보고서 생성 (graceful fallback, 예외 미발생) |
| LLM이 요청보다 적게 반환 | 반환된 것만 사용 |
| LLM이 N개 초과 반환 | 앞에서 N개만 슬라이스 |
| `testCaseIds` 미선택 | 기존 동작 유지 (전체 TC 사용) |

## 변경하지 않는 것

- `TestCase`, `Requirement`, `Evidence`, `RiskItem` 테이블 및 분석 파이프라인
- 기존 보고서 생성/다운로드/삭제 흐름
- `targetScenarioCount` 미전달 시 기존 동작 100% 호환
