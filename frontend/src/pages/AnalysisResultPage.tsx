import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { analysisApi, reportApi, TestCase, Evidence } from '../services/api';

// Subcomponent: Evidence Accordion
const EvidenceAccordion: React.FC<{ evidence: Evidence }> = ({ evidence }) => {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <div className="mt-2 p-3 bg-surface-container-lowest rounded-lg font-code-sm text-on-surface-variant border-l-2 border-primary">
      <div 
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center justify-between cursor-pointer text-body-sm text-primary hover:underline select-none"
      >
        <span className="font-semibold text-xs flex items-center gap-1">
          <span className="material-symbols-outlined text-sm">menu_book</span>
          근거: {evidence.sourceName} {evidence.sourceSection && `(${evidence.sourceSection})`}
          {evidence.score !== undefined && evidence.score !== null && (
            <span className="ml-2 text-[10px] text-amber-500 font-mono">({Math.round(evidence.score * 100)}% 유사도)</span>
          )}
        </span>
        <span className="material-symbols-outlined transition-transform duration-200" style={{ transform: isOpen ? 'rotate(180deg)' : 'rotate(0deg)' }}>
          expand_more
        </span>
      </div>
      
      {isOpen && (
        <p className="mt-2 text-xs text-slate-400 leading-relaxed font-sans whitespace-pre-wrap">
          {evidence.evidenceText}
        </p>
      )}
    </div>
  );
};

// Subcomponent: ContentAccordion for TDD & Negative Scenario
const ContentAccordion: React.FC<{ title: string; content: string; icon: string; isMonospace?: boolean; iconColor?: string }> = ({ title, content, icon, isMonospace, iconColor }) => {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <div className="mt-2 rounded-lg border border-white/5 bg-black/10 overflow-hidden transition-all duration-300">
      <div 
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        className="flex items-center justify-between p-3 cursor-pointer text-xs font-semibold text-slate-300 hover:text-white hover:bg-white/5 select-none transition-colors"
      >
        <span className="flex items-center gap-1.5">
          <span className={`material-symbols-outlined text-sm ${iconColor || 'text-indigo-400'}`}>{icon}</span>
          {title}
        </span>
        <span className="material-symbols-outlined transition-transform duration-200" style={{ transform: isOpen ? 'rotate(180deg)' : 'rotate(0deg)' }}>
          expand_more
        </span>
      </div>
      
      <div 
        className={`transition-all duration-300 ease-in-out overflow-hidden ${
          isOpen ? 'max-h-[500px] border-t border-white/5 p-3' : 'max-h-0'
        }`}
      >
        <p className={`text-xs leading-relaxed whitespace-pre-wrap ${isMonospace ? 'font-mono text-indigo-300 bg-black/30 p-2 rounded border border-white/5' : 'text-slate-400'}`}>
          {content}
        </p>
      </div>
    </div>
  );
};

// Subcomponent: TestCaseCard
const TestCaseCard: React.FC<{ testCase: TestCase; isSelected: boolean; onToggle: () => void }> = ({ testCase, isSelected, onToggle }) => {
  const hasInsufficientEvidence = !testCase.evidences || testCase.evidences.length === 0 || testCase.evidences.every(ev => ev.score !== undefined && ev.score !== null && ev.score < 0.6);

  const getPriorityBadge = (priority: string) => {
    switch (priority.toUpperCase()) {
      case 'HIGH':
        return (
          <span className="bg-red-500/10 text-red-400 text-[10px] font-bold px-2 py-0.5 rounded flex items-center gap-1 border border-red-500/20">
            <span className="w-1.5 h-1.5 rounded-full bg-red-500"></span> HIGH
          </span>
        );
      case 'MEDIUM':
        return (
          <span className="bg-white/10 text-on-surface-variant text-[10px] font-bold px-2 py-0.5 rounded flex items-center gap-1 border border-white/10">
            <span className="w-1.5 h-1.5 rounded-full bg-on-surface-variant"></span> MEDIUM
          </span>
        );
      case 'LOW':
        return (
          <span className="bg-green-500/10 text-green-400 text-[10px] font-bold px-2 py-0.5 rounded flex items-center gap-1 border border-green-500/20">
            <span className="w-1.5 h-1.5 rounded-full bg-green-500"></span> LOW
          </span>
        );
      default:
        return (
          <span className="bg-secondary-container text-secondary text-[10px] font-bold px-2 py-0.5 rounded flex items-center gap-1 border border-secondary/30">
            <span className="w-1.5 h-1.5 rounded-full bg-secondary"></span> {priority}
          </span>
        );
    }
  };

  const getCategoryBadge = (category?: string) => {
    if (!category) return null;
    let colorClass = 'bg-slate-500/10 text-slate-400 border-slate-500/20';
    switch (category.toLowerCase()) {
      case 'test_level':
        colorClass = 'bg-blue-500/10 text-blue-400 border-blue-500/20';
        break;
      case 'test_technique':
        colorClass = 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
        break;
      case 'non_functional':
        colorClass = 'bg-amber-500/10 text-amber-400 border-amber-500/20';
        break;
      case 'qa_concept':
        colorClass = 'bg-purple-500/10 text-purple-400 border-purple-500/20';
        break;
    }
    return (
      <span className={`text-[10px] px-2 py-0.5 rounded border ${colorClass} font-mono shrink-0`}>
        {category}
      </span>
    );
  };

  const getTechniqueBadge = (technique?: string) => {
    if (!technique) return null;
    return (
      <span className="bg-indigo-500/10 text-indigo-300 text-[10px] px-2 py-0.5 rounded border border-indigo-500/20 font-sans shrink-0">
        {technique}
      </span>
    );
  };

  return (
    <div 
      onClick={onToggle}
      className={`glass-panel p-md rounded-xl hover:bg-white/5 transition-all duration-300 flex flex-col gap-md cursor-pointer select-none border ${
        isSelected 
          ? 'border-indigo-500/50 bg-indigo-500/5 shadow-[0_0_15px_rgba(99,102,241,0.1)]' 
          : hasInsufficientEvidence 
            ? 'border-amber-500/40 bg-amber-500/5 shadow-[0_0_15px_rgba(245,158,11,0.05)]' 
            : testCase.priority === 'HIGH' ? 'border-red-500/20' : 'border-white/5'
      }`}
    >
      <div className="flex flex-col gap-sm">
        <div className="flex justify-between items-center flex-wrap gap-2" onClick={(e) => e.stopPropagation()}>
          <div className="flex items-center gap-3 flex-wrap">
            <input 
              type="checkbox"
              checked={isSelected}
              onChange={onToggle}
              className="rounded border-white/20 bg-white/5 text-indigo-500 focus:ring-indigo-500 focus:ring-offset-0 w-4 h-4 cursor-pointer"
            />
            <span className="font-code-md text-secondary text-xs font-mono">{testCase.testCaseId}</span>
            {getCategoryBadge(testCase.category)}
            {getTechniqueBadge(testCase.technique)}
          </div>
          {getPriorityBadge(testCase.priority)}
        </div>
        <h4 className="font-headline-lg-mobile text-on-surface text-base font-bold leading-snug">{testCase.testCaseName}</h4>
        
        {hasInsufficientEvidence && (
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-amber-500/10 text-amber-500 border border-amber-500/20 w-fit text-[10px] font-semibold select-none">
            <span className="material-symbols-outlined text-xs">warning</span>
            근거 부족 (추가 검토 필요)
          </div>
        )}

        {testCase.riskTags && testCase.riskTags.length > 0 && (
          <div className="flex flex-wrap gap-xs">
            {testCase.riskTags.map((tag) => (
              <span key={tag} className="text-code-sm text-on-surface-variant bg-white/5 px-2 py-0.5 rounded text-[11px] font-mono">
                #{tag}
              </span>
            ))}
          </div>
        )}
      </div>

      <div className="space-y-3">
        <div className="p-3 bg-black/20 rounded-lg border border-white/5">
          <div className="text-label-caps text-outline text-[10px] font-mono text-slate-500 uppercase">PRE-CONDITIONS</div>
          <p className="text-body-sm text-on-surface-variant text-xs mt-1">{testCase.precondition || '사전 조건 없음'}</p>
        </div>

        <div className="space-y-1">
          <div className="text-label-caps text-outline text-[10px] font-mono text-slate-500 uppercase">PROCEDURE</div>
          <ol className="text-body-sm text-on-surface text-xs space-y-1 pl-4 list-decimal leading-relaxed">
            {testCase.testSteps.map((step, idx) => (
              <li key={idx}>{step}</li>
            ))}
          </ol>
        </div>

        <div className="space-y-1">
          <div className="text-label-caps text-outline text-[10px] font-mono text-slate-500 uppercase">EXPECTED RESULT</div>
          <p className="text-body-sm text-on-surface text-xs mt-1 bg-white/5 p-2 rounded border border-white/5">{testCase.expectedResult}</p>
        </div>
      </div>

      {testCase.tddHint && (
        <div onClick={(e) => e.stopPropagation()}>
          <ContentAccordion 
            title="[💡 TDD 개발 구현 가이드]" 
            content={testCase.tddHint} 
            icon="lightbulb" 
            isMonospace={true} 
            iconColor="text-yellow-400"
          />
        </div>
      )}

      {testCase.negativeScenario && (
        <div onClick={(e) => e.stopPropagation()}>
          <ContentAccordion 
            title="[⚠️ Happy Path 너머 예외 검증 시나리오]" 
            content={testCase.negativeScenario} 
            icon="warning" 
            isMonospace={false} 
            iconColor="text-red-400"
          />
        </div>
      )}

      {testCase.evidences && testCase.evidences.length > 0 && (
        <div className="space-y-2 pt-2 border-t border-white/5" onClick={(e) => e.stopPropagation()}>
          {testCase.evidences.map((ev) => (
            <EvidenceAccordion key={ev.evidenceId} evidence={ev} />
          ))}
        </div>
      )}
    </div>
  );
};


export const AnalysisResultPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const analysisId = searchParams.get('analysisId') || '';

  const [summary, setSummary] = useState('');
  const [testCases, setTestCases] = useState<TestCase[]>([]);
  const [selectedTestCaseIds, setSelectedTestCaseIds] = useState<string[]>([]);
  const [missingItems, setMissingItems] = useState<string[]>([]);
  
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // Report Modal
  const [isReportModalOpen, setIsReportModalOpen] = useState(false);
  const [reportFormat, setReportFormat] = useState<'MARKDOWN' | 'PDF'>('MARKDOWN');
  const [reportGenerating, setReportGenerating] = useState(false);

  useEffect(() => {
    if (analysisId) {
      fetchAnalysisResults(analysisId);
    } else {
      setError('유효하지 않은 분석 ID입니다.');
    }
  }, [analysisId]);

  const fetchAnalysisResults = async (id: string) => {
    setLoading(true);
    try {
      const response = await analysisApi.getResults(id);
      setSummary(response.data.summary);
      setTestCases(response.data.testCases);
      if (response.data.testCases) {
        setSelectedTestCaseIds(response.data.testCases.map((tc: TestCase) => tc.testCaseId));
      }
      setMissingItems(response.data.missingItems || []);
    } catch (err: any) {
      console.error(err);
      setError('분석 결과를 불러오는 데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleGenerateReport = async () => {
    setReportGenerating(true);
    try {
      const response = await reportApi.generate(analysisId, reportFormat, selectedTestCaseIds);
      setIsReportModalOpen(false);
      
      // Redirect to report preview page
      navigate(`/report-demo?reportId=${response.data.reportId}`);
    } catch (err: any) {
      console.error(err);
      alert('보고서 생성에 실패했습니다.');
    } finally {
      setReportGenerating(false);
    }
  };

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center py-20 space-y-4">
        <span className="material-symbols-outlined text-4xl text-indigo-400 animate-spin">progress_activity</span>
        <p className="text-secondary text-sm font-mono">분석 정보 디코드 및 시각화 데이터 로딩 중...</p>
      </div>
    );
  }

  // Extract unique caution statements from test cases
  const cautions = testCases
    .map(tc => tc.caution)
    .filter((c): c is string => !!c && c.trim() !== '')
    .filter((value, index, self) => self.indexOf(value) === index);

  return (
    <div className="space-y-8 animate-fade-in max-w-7xl mx-auto">
      {/* 1. Top Progress Monitor (100% Completed status) */}
      <section className="mb-gutter">
        <div className="glass-panel p-lg rounded-xl flex flex-col md:flex-row items-center gap-lg">
          <div className="relative w-24 h-24 flex-shrink-0">
            <svg className="w-full h-full -rotate-90" viewBox="0 0 36 36">
              <path className="text-white/10" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831" fill="none" stroke="currentColor" strokeWidth="2.5"></path>
              <path className="text-primary" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831" fill="none" stroke="currentColor" strokeDasharray="100, 100" strokeWidth="2.5"></path>
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="font-bold text-headline-lg-mobile text-primary">100%</span>
            </div>
          </div>
          <div className="flex-1 text-center md:text-left">
            <h2 className="font-headline-lg text-primary text-xl font-bold mb-sm">AI TDD 분석 검증 완료</h2>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-sm relative pt-4">
              <div className="absolute top-7 left-0 w-full h-0.5 bg-white/10 -z-10 hidden md:block"></div>
              <div className="flex flex-col items-center gap-xs">
                <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs">
                  <span className="material-symbols-outlined text-[14px]">check</span>
                </div>
                <span className="font-body-sm text-xs text-primary font-bold">1. 문서 파싱</span>
              </div>
              <div className="flex flex-col items-center gap-xs">
                <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs">
                  <span className="material-symbols-outlined text-[14px]">check</span>
                </div>
                <span className="font-body-sm text-xs text-primary font-bold">2. 지식베이스 검색</span>
              </div>
              <div className="flex flex-col items-center gap-xs">
                <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs">
                  <span className="material-symbols-outlined text-[14px]">check</span>
                </div>
                <span className="font-body-sm text-xs text-primary font-bold">3. 테스트 케이스 생성</span>
              </div>
              <div className="flex flex-col items-center gap-xs">
                <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs">
                  <span className="material-symbols-outlined text-[14px]">check</span>
                </div>
                <span className="font-body-sm text-xs text-primary font-bold">4. 무결성 검증</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {error && (
        <div className="flex items-start gap-3 p-4 rounded-lg bg-red-500/10 border border-red-500/20 text-red-200 text-sm">
          <span className="material-symbols-outlined text-red-400">error</span>
          <span>{error}</span>
        </div>
      )}

      {!error && (
        <div className="space-y-6">
          {/* 2. Requirement Summary Card */}
          <section className="mb-gutter">
            <div className="glass-panel p-lg rounded-xl border border-primary/20 relative overflow-hidden" style={{ background: 'linear-gradient(rgba(15, 15, 26, 0.8), rgba(15, 15, 26, 0.8)) padding-box, linear-gradient(45deg, #6366F1, #8B5CF6) border-box' }}>
              <div className="flex flex-col md:flex-row justify-between items-start md:items-center mb-md gap-4">
                <div>
                  <div className="font-label-caps text-secondary text-xs font-mono uppercase tracking-wider">PROJECT SPECIFICATION SUMMARY</div>
                  <h3 className="font-headline-lg text-on-surface text-lg font-bold mt-1">추출된 시스템 요구사항 분석 명세서</h3>
                </div>
                <div className="bg-primary/10 px-4 py-2 rounded-lg border border-primary/30 shrink-0">
                  <span className="text-primary font-code-sm text-xs font-mono">STATUS: COMPLETED</span>
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-md border-t border-white/5 pt-4 mt-4">
                <div className="space-y-2">
                  <div className="text-on-surface-variant font-body-sm text-xs text-slate-400 flex items-center gap-1.5">
                    <span className="material-symbols-outlined text-sm">target</span>
                    핵심 검증 목표
                  </div>
                  <p className="text-xs text-on-surface leading-relaxed text-slate-200">{summary || '프로젝트 기획서에서 분석된 목표입니다.'}</p>
                </div>
                <div className="space-y-2">
                  <div className="text-on-surface-variant font-body-sm text-xs text-slate-400 flex items-center gap-1.5">
                    <span className="material-symbols-outlined text-sm">zoom_out</span>
                    검증 범위
                  </div>
                  <ul className="text-xs text-on-surface list-disc list-inside space-y-1 text-slate-200">
                    <li>비즈니스 흐름 정합성</li>
                    <li>입력 및 예외 처리 무결성</li>
                    <li>권한 및 보안 정책 준수</li>
                  </ul>
                </div>
                <div className="space-y-2">
                  <div className="text-on-surface-variant font-body-sm text-xs text-slate-400 flex items-center gap-1.5">
                    <span className="material-symbols-outlined text-sm">database</span>
                    식별된 분석 문서 ID
                  </div>
                  <p className="text-[11px] font-mono text-secondary bg-secondary/5 p-2 rounded break-all">{analysisId}</p>
                </div>
              </div>
            </div>
          </section>

          {/* 3. Test Case Results Grid */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 items-start">
            {/* Left Column: Test Cases list */}
            <div className="lg:col-span-2 space-y-4">
              <div className="flex justify-between items-center pb-2">
                <h3 className="font-bold text-white text-base flex items-center gap-2">
                  <span className="material-symbols-outlined text-indigo-400">checklist</span>
                  생성된 테스트 케이스 시나리오 ({testCases.length})
                </h3>
                {testCases.length > 0 && (
                  <label className="flex items-center gap-2 text-xs text-slate-400 cursor-pointer select-none hover:text-white transition-colors">
                    <input
                      type="checkbox"
                      checked={selectedTestCaseIds.length === testCases.length && testCases.length > 0}
                      onChange={() => {
                        if (selectedTestCaseIds.length === testCases.length) {
                          setSelectedTestCaseIds([]);
                        } else {
                          setSelectedTestCaseIds(testCases.map(tc => tc.testCaseId));
                        }
                      }}
                      className="rounded border-white/20 bg-white/5 text-indigo-500 focus:ring-indigo-500 w-4 h-4 cursor-pointer"
                    />
                    전체 선택 ({selectedTestCaseIds.length}/{testCases.length})
                  </label>
                )}
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {testCases.map((tc) => (
                  <TestCaseCard 
                    key={tc.testCaseId} 
                    testCase={tc} 
                    isSelected={selectedTestCaseIds.includes(tc.testCaseId)}
                    onToggle={() => {
                      setSelectedTestCaseIds(prev => 
                        prev.includes(tc.testCaseId) 
                          ? prev.filter(id => id !== tc.testCaseId) 
                          : [...prev, tc.testCaseId]
                      );
                    }}
                  />
                ))}
              </div>
            </div>

            {/* Right Column: Missing Items warnings */}
            <div className="lg:col-span-1 space-y-6">
              <div className="glass-panel p-md rounded-xl border border-orange-500/20 bg-orange-500/5 space-y-4">
                <h4 className="font-bold text-orange-400 text-sm flex items-center gap-2">
                  <span className="material-symbols-outlined">warning</span>
                  기획 명세서 누락 분석
                </h4>
                <p className="text-slate-400 text-xs leading-relaxed">
                  AI가 기획서 텍스트의 앞뒤 맥락과 도메인 표준을 비교해 누락으로 판단되는 예외 흐름 후보를 추론했습니다.
                </p>
                
                {missingItems.length === 0 ? (
                  <p className="text-slate-500 text-xs">탐지된 명세 누락 사항이 없습니다.</p>
                ) : (
                  <div className="space-y-3 pt-2 border-t border-white/5">
                    {missingItems.map((item, idx) => (
                      <div key={idx} className="flex gap-2 text-xs text-orange-200 border-t border-white/5 pt-3 first:border-0 first:pt-0">
                        <span className="text-orange-400 shrink-0 font-bold">•</span>
                        <p className="leading-relaxed font-sans">{item}</p>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Cautions dynamic panel */}
              <div className="glass-panel p-md rounded-xl border border-white/10 space-y-4">
                <h4 className="font-bold text-indigo-300 text-sm flex items-center gap-2">
                  <span className="material-symbols-outlined text-sm">verified_user</span>
                  테스트 실행 및 환경 주의 사항
                </h4>
                <p className="text-slate-400 text-xs leading-relaxed">
                  AI가 요구사항 분석 결과와 지식 데이터베이스를 교차 검증하여 도출해 낸 테스트 실행 시의 제약 조건 및 환경 제약 사항입니다.
                </p>

                {cautions.length === 0 ? (
                  <p className="text-[11px] leading-relaxed text-slate-500 italic">
                    식별된 테스트 설계 분석 상 특이사항이 없습니다. 원본 데이터 무결성이 정상 보장됩니다.
                  </p>
                ) : (
                  <div className="space-y-3 pt-2 border-t border-white/5">
                    {cautions.map((item, idx) => (
                      <div key={idx} className="flex gap-2 text-[11px] text-slate-300 border-t border-white/5 pt-3 first:border-0 first:pt-0">
                        <span className="text-indigo-400 shrink-0 font-bold">•</span>
                        <p className="leading-relaxed font-sans">{item}</p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 4. Bottom Actions */}
      <section className="mt-lg flex flex-col md:flex-row gap-md justify-center pt-8 border-t border-white/5">
        <button 
          onClick={() => {
            if (selectedTestCaseIds.length === 0) {
              alert('보고서에 수록할 테스트 케이스를 최소 1개 이상 선택해 주세요.');
              return;
            }
            setIsReportModalOpen(true);
          }}
          className="px-xl py-4 rounded-xl bg-gradient-to-r from-[#6366F1] to-[#8B5CF6] text-white font-bold text-sm glow-indigo hover:brightness-110 active:scale-95 transition-all flex items-center justify-center gap-2"
        >
          선택된 테스트 케이스로 보고서 생성
          <span className="material-symbols-outlined text-sm">arrow_forward</span>
        </button>
        <button 
          onClick={() => navigate('/dashboard')}
          className="px-lg py-4 rounded-xl glass-panel text-on-surface font-bold text-sm hover:bg-white/10 active:scale-95 transition-all flex items-center justify-center gap-2 text-slate-300"
        >
          대시보드로 돌아가기
          <span className="material-symbols-outlined text-sm">dashboard</span>
        </button>
      </section>

      {/* Report Format Selection Modal */}
      {isReportModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="glass-panel-heavy max-w-sm w-full p-6 space-y-6 border-white/20">
            <h3 className="text-lg font-bold text-white flex items-center gap-2">
              <span className="material-symbols-outlined text-indigo-400">task_alt</span> 보고서 산출 포맷 선택
            </h3>

            <div className="space-y-3">
              <label className="flex items-center gap-3 p-3 rounded border border-white/5 bg-white/5 cursor-pointer hover:bg-white/10 transition-all select-none">
                <input
                  type="radio"
                  name="format"
                  checked={reportFormat === 'MARKDOWN'}
                  onChange={() => setReportFormat('MARKDOWN')}
                  className="text-indigo-500 focus:ring-indigo-500 cursor-pointer"
                />
                <div className="text-left">
                  <span className="block text-sm font-semibold text-slate-200">Markdown 문서 (.md)</span>
                  <span className="block text-[10px] text-slate-500">깃허브 위키나 마크다운 뷰어와 연동에 최적화</span>
                </div>
              </label>

              <label className="flex items-center gap-3 p-3 rounded border border-white/5 bg-white/5 cursor-pointer hover:bg-white/10 transition-all select-none">
                <input
                  type="radio"
                  name="format"
                  checked={reportFormat === 'PDF'}
                  onChange={() => setReportFormat('PDF')}
                  className="text-indigo-500 focus:ring-indigo-500 cursor-pointer"
                />
                <div className="text-left">
                  <span className="block text-sm font-semibold text-slate-200">PDF 문서 (.pdf)</span>
                  <span className="block text-[10px] text-slate-500">서식 템플릿과 주의문구가 정렬된 인쇄용 반출 최적화</span>
                </div>
              </label>
            </div>

            <div className="flex justify-end gap-3 pt-2">
              <button
                onClick={() => setIsReportModalOpen(false)}
                className="btn-secondary text-xs px-4 py-2 font-bold rounded-lg"
                disabled={reportGenerating}
              >
                취소
              </button>
              <button
                onClick={handleGenerateReport}
                className="btn-primary text-xs px-4 py-2 font-bold rounded-lg"
                disabled={reportGenerating}
              >
                {reportGenerating ? (
                  <span className="flex items-center gap-1">
                    <span className="material-symbols-outlined text-xs animate-spin">progress_activity</span>
                    생성 중...
                  </span>
                ) : (
                  '보고서 생성 실행'
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

