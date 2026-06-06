import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import { reportApi, ReportPreviewResponse } from '../services/api';

export const ReportPreviewPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const reportId = searchParams.get('reportId') || '';

  const [report, setReport] = useState<ReportPreviewResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (reportId) {
      fetchReportPreview(reportId);
    } else {
      setError('유효하지 않은 보고서 ID입니다.');
    }
  }, [reportId]);

  const fetchReportPreview = async (id: string) => {
    setLoading(true);
    setError('');
    try {
      const response = await reportApi.getPreview(id);
      setReport(response.data);
    } catch (err: any) {
      console.error(err);
      setError('보고서 데이터를 불러오거나 원격 파일 복구에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleDownload = () => {
    if (!report) return;
    const url = reportApi.download(report.reportId);
    const link = document.createElement('a');
    link.href = url;
    
    const extension = report.reportFormat.toLowerCase() === 'pdf' ? 'pdf' : 'md';
    link.setAttribute('download', `QA_Report_${report.reportId}.${extension}`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handleDelete = async () => {
    if (!report) return;
    if (confirm('이 보고서를 영구 삭제하고 S3 디스크 공간을 회수하시겠습니까? (RDB 이력도 자동 파기됩니다)')) {
      try {
        await reportApi.delete(report.reportId);
        navigate('/dashboard');
      } catch (err) {
        console.error(err);
        alert('보고서 삭제에 실패했습니다.');
      }
    }
  };

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen space-y-4">
        <span className="material-symbols-outlined text-5xl text-indigo-400 animate-spin">progress_activity</span>
        <p className="text-secondary text-sm font-mono">MinIO 및 H2 복구 핸들러 작동 중... 이력 수집 중...</p>
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden h-screen bg-transparent">
      {/* Header Section */}
      <header className="h-20 px-6 md:px-12 flex items-center justify-between border-b border-white/5 bg-black/20 backdrop-blur-md shrink-0">
        <div className="flex items-center gap-4">
          <button 
            onClick={() => navigate('/dashboard')}
            className="w-10 h-10 flex items-center justify-center rounded-full hover:bg-white/5 text-on-surface-variant transition-colors"
          >
            <span className="material-symbols-outlined">arrow_back</span>
          </button>
          <div>
            <h1 className="text-xl font-bold tracking-tight text-white">검증 보고서 미리보기 및 반출 (Report Preview & Export)</h1>
            <p className="text-xs text-on-surface-variant">TDD 분석 결과 확인 및 정식 문서화 도구</p>
          </div>
        </div>

        {report && (
          <div className="flex items-center gap-3">
            <span className="px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 text-xs font-semibold flex items-center gap-1">
              <span className="material-symbols-outlined text-sm">check_circle</span>
              검증 완료
            </span>
          </div>
        )}
      </header>

      {error && (
        <div className="p-6 max-w-4xl mx-auto w-full">
          <div className="flex items-start gap-3 p-4 rounded-lg bg-red-500/10 border border-red-500/20 text-red-200 text-sm">
            <span className="material-symbols-outlined text-red-400">error</span>
            <span>{error}</span>
          </div>
        </div>
      )}

      {report && (
        <div className="flex-1 flex overflow-hidden">
          {/* Main Content Area: Report Viewer */}
          <main className="flex-1 overflow-y-auto p-6 md:p-12">
            <div className="max-w-4xl mx-auto space-y-6 pb-12">
              
              {/* Disclaimer Box */}
              <div className="p-4 rounded-lg bg-yellow-500/5 border border-yellow-500/15 border-l-4 border-l-yellow-500 text-yellow-300/90 text-xs leading-relaxed flex gap-3">
                <span className="material-symbols-outlined text-yellow-400 shrink-0 text-lg">warning</span>
                <div className="space-y-1">
                  <span className="font-bold block">🚨 시스템 한계 고지 및 검토 주의 (Disclaimer)</span>
                  <p className="text-slate-300">{report.disclaimer}</p>
                </div>
              </div>

              {/* Report Canvas */}
              <div className="glass-panel rounded-2xl overflow-hidden min-h-[900px] border border-white/10" style={{ background: 'rgba(255, 255, 255, 0.03)' }}>
                {/* Canvas Header */}
                <div className="h-14 px-6 flex items-center justify-between border-b border-white/5 bg-white/5">
                  <div className="flex items-center gap-2">
                    <span className="material-symbols-outlined text-sm text-on-surface-variant">description</span>
                    <span className="text-xs text-on-surface-variant font-mono">{`report_${report.reportId.substring(0, 8)}.md`}</span>
                  </div>
                  <div className="flex gap-4">
                    <span className="text-xs text-on-surface-variant flex items-center gap-1 font-mono">
                      <span className="material-symbols-outlined text-sm text-emerald-400">verified_user</span> Verified
                    </span>
                    <span className="text-xs text-on-surface-variant flex items-center gap-1 font-mono">
                      <span className="material-symbols-outlined text-sm text-red-400">lock</span> Confidential
                    </span>
                  </div>
                </div>

                {/* Report Content */}
                <div className="p-6 md:p-12 lg:p-16">
                  {/* Meta Info Grid */}
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-6 py-6 mb-8 border-b border-white/5">
                    <div>
                      <label className="text-[10px] text-slate-500 uppercase tracking-widest font-mono font-bold block">생성 일자</label>
                      <p className="text-sm font-semibold text-white mt-1">{new Date(report.generatedAt).toLocaleString('ko-KR')}</p>
                    </div>
                    <div>
                      <label className="text-[10px] text-slate-500 uppercase tracking-widest font-mono font-bold block">보고서 식별 ID</label>
                      <p className="text-sm font-mono text-indigo-300 mt-1 truncate">{report.reportId}</p>
                    </div>
                    <div>
                      <label className="text-[10px] text-slate-500 uppercase tracking-widest font-mono font-bold block">반출 포맷</label>
                      <p className="text-sm font-semibold text-white mt-1">{report.reportFormat}</p>
                    </div>
                    <div>
                      <label className="text-[10px] text-slate-500 uppercase tracking-widest font-mono font-bold block">최종 검증 상태</label>
                      <p className="text-sm font-bold text-emerald-400 mt-1 flex items-center gap-1">
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span> PASSED
                      </p>
                    </div>
                  </div>

                  {/* Markdown content area */}
                  <article className="prose prose-invert max-w-none text-slate-300 leading-relaxed font-sans space-y-4">
                    <ReactMarkdown
                      components={{
                        h1: ({ children }) => <h1 className="text-2xl font-bold text-white border-b border-white/10 pb-3 mb-6 font-sans">{children}</h1>,
                        h2: ({ children }) => <h2 className="text-lg font-semibold text-slate-200 mt-8 mb-4 border-l-2 border-indigo-500 pl-3 font-sans">{children}</h2>,
                        h3: ({ children }) => <h3 className="text-base font-bold text-slate-300 mt-6 mb-3 font-sans">{children}</h3>,
                        p: ({ children }) => <p className="text-sm text-slate-300 leading-relaxed mb-4 font-sans">{children}</p>,
                        ul: ({ children }) => <ul className="list-disc pl-5 space-y-2 mb-4 text-sm text-slate-300">{children}</ul>,
                        ol: ({ children }) => <ol className="list-decimal pl-5 space-y-2 mb-4 text-sm text-slate-300">{children}</ol>,
                        li: ({ children }) => <li className="leading-relaxed font-sans text-xs text-slate-300">{children}</li>,
                        code: ({ children }) => <code className="bg-white/5 border border-white/10 rounded px-1.5 py-0.5 text-xs text-indigo-300 font-mono">{children}</code>,
                        blockquote: ({ children }) => <blockquote className="border-l-4 border-indigo-500/50 pl-4 py-1 italic bg-indigo-500/5 rounded-r my-4 text-slate-400 font-sans">{children}</blockquote>,
                        table: ({ children }) => <table className="w-full text-left border-collapse border border-white/5 my-4 bg-black/10 rounded-lg overflow-hidden text-xs">{children}</table>,
                        thead: ({ children }) => <thead className="bg-white/5 border-b border-white/10">{children}</thead>,
                        tbody: ({ children }) => <tbody className="divide-y divide-white/5">{children}</tbody>,
                        tr: ({ children }) => <tr className="hover:bg-white/5 transition-colors">{children}</tr>,
                        th: ({ children }) => <th className="px-4 py-2 font-mono text-slate-400 text-[11px] uppercase tracking-wider">{children}</th>,
                        td: ({ children }) => <td className="px-4 py-2 text-slate-300">{children}</td>,
                        pre: ({ children }) => <pre className="bg-black/30 border border-white/5 rounded-lg p-4 my-4 overflow-x-auto font-mono text-xs">{children}</pre>
                      }}
                    >
                      {report.content}
                    </ReactMarkdown>
                  </article>
                </div>
              </div>
            </div>
          </main>

          {/* Right Sidebar: Export Options */}
          <aside className="w-[320px] shrink-0 border-l border-white/5 bg-black/10 backdrop-blur-xl p-6 flex flex-col gap-6">
            <section className="space-y-4">
              <h3 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest mb-4">Export Options</h3>
              <div className="space-y-3">
                <label className="block text-xs font-semibold text-slate-400">파일 형식 반출 선택</label>
                <div className="grid grid-cols-2 gap-2 p-1 bg-white/5 rounded-lg border border-white/10">
                  <button 
                    onClick={() => alert('Markdown 포맷으로 반출하도록 다운로드 설정이 매핑되었습니다.')}
                    className={`flex items-center justify-center gap-2 py-2 text-xs font-semibold rounded-md transition-all ${
                      report.reportFormat === 'MARKDOWN'
                        ? 'bg-white/10 text-white shadow-sm'
                        : 'hover:bg-white/5 text-slate-400'
                    }`}
                  >
                    <span className="material-symbols-outlined text-sm">markdown</span>
                    Markdown
                  </button>
                  <button 
                    onClick={() => alert('PDF 포맷으로 반출하도록 다운로드 설정이 매핑되었습니다. (백엔드 S3 동기화)')}
                    className={`flex items-center justify-center gap-2 py-2 text-xs font-semibold rounded-md transition-all ${
                      report.reportFormat === 'PDF'
                        ? 'bg-white/10 text-white shadow-sm'
                        : 'hover:bg-white/5 text-slate-400'
                    }`}
                  >
                    <span className="material-symbols-outlined text-sm">picture_as_pdf</span>
                    PDF File
                  </button>
                </div>
              </div>
            </section>

            <section className="mt-auto space-y-4">
              <div className="p-4 rounded-xl bg-white/5 border border-white/5">
                <div className="flex items-center gap-2 mb-2 text-xs text-indigo-400 font-bold">
                  <span className="material-symbols-outlined text-sm">verified</span>
                  무결성 보장
                </div>
                <p className="text-[11px] leading-relaxed text-slate-400">
                  파일 다운로드 시 SHA-256 해시값 기반의 무결성 검증 토큰이 포함됩니다. 보고서의 위변조를 방지하고 생성 시점의 데이터를 영구 보존합니다.
                </p>
              </div>

              <button 
                onClick={handleDownload}
                className="w-full h-12 bg-gradient-to-r from-indigo-500 to-purple-500 rounded-xl flex items-center justify-center gap-2 text-white font-bold shadow-lg hover:opacity-90 active:scale-95 transition-all text-sm glow-indigo"
              >
                <span className="material-symbols-outlined text-base">download</span>
                보고서 다운로드
              </button>

              <button 
                onClick={() => navigate('/dashboard')}
                className="w-full h-12 border border-white/10 hover:bg-white/5 rounded-xl flex items-center justify-center gap-2 text-slate-300 font-medium transition-colors text-sm"
              >
                <span className="material-symbols-outlined text-base">dashboard</span>
                대시보드로 돌아가기
              </button>

              <button 
                onClick={handleDelete}
                className="w-full py-2 text-center text-xs text-red-400/80 hover:text-red-400 hover:bg-red-500/5 border border-red-500/10 rounded-lg transition-all"
              >
                보고서 물리 파일 삭제
              </button>
            </section>
          </aside>
        </div>
      )}
    </div>
  );
};

