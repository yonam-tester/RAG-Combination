import React from 'react';
import { PipelineTraceStep } from '../services/api';

const STEP_LABELS: Record<string, string> = {
  PARSE:    '1. 문서 파싱',
  CHUNK:    '2. 텍스트 청킹',
  INDEX:    '3. 벡터 인덱싱',
  EXTRACT:  '4. 요구사항 추출',
  RETRIEVE: '5. 유사도 검색',
};

const STEP_ICONS: Record<string, string> = {
  PARSE:    'description',
  CHUNK:    'content_cut',
  INDEX:    'database',
  EXTRACT:  'manage_search',
  RETRIEVE: 'query_stats',
};

function getStepMeta(step: PipelineTraceStep): string {
  const parts: string[] = [];
  if (step.fileCount !== undefined)   parts.push(`${step.fileCount}개 파일`);
  if (step.chunkCount !== undefined)  parts.push(`${step.chunkCount}개 청크`);
  if (step.vectorCount !== undefined) parts.push(`${step.vectorCount}개 벡터`);
  if (step.reqCount !== undefined)    parts.push(`${step.reqCount}개 요구사항`);
  if (step.topScore !== undefined)    parts.push(`최고 유사도 ${Math.round(step.topScore * 100)}%`);
  return parts.join(' · ');
}

function isRealRag(trace: PipelineTraceStep[]): boolean {
  return trace.some(s => s.status === 'SUCCESS');
}

interface Props {
  analysisId: string;
  trace: PipelineTraceStep[];
  onClose: () => void;
}

export const RagTraceModal: React.FC<Props> = ({ analysisId, trace, onClose }) => {
  const real = isRealRag(trace);

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/75 backdrop-blur-sm p-4">
      <div
        className="bg-surface-container-high border border-white/10 rounded-2xl shadow-2xl w-full max-w-lg relative"
        style={{ background: '#1e1e2f' }}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-white/10">
          <div>
            <h3 className="text-lg font-bold bg-gradient-to-r from-primary to-tertiary bg-clip-text text-transparent flex items-center gap-2">
              <span className="material-symbols-outlined text-indigo-400">biotech</span>
              RAG 파이프라인 진단
            </h3>
            <p className="text-[11px] text-slate-500 font-mono mt-0.5">{analysisId}</p>
          </div>
          <div className="flex items-center gap-3">
            <span
              className={`text-xs font-bold px-3 py-1 rounded-full border ${
                real
                  ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                  : 'bg-slate-500/10 text-slate-400 border-slate-500/30'
              }`}
            >
              {real ? '● REAL RAG' : '○ MOCK'}
            </span>
            <button
              onClick={onClose}
              aria-label="닫기"
              className="text-slate-400 hover:text-white transition-colors"
            >
              <span className="material-symbols-outlined">close</span>
            </button>
          </div>
        </div>

        {/* Steps */}
        <div className="px-6 py-4 space-y-3">
          {trace.map((step) => {
            const isSuccess = step.status === 'SUCCESS';
            const isSkipped = step.status === 'SKIPPED';
            const isFailed  = step.status === 'FAILED';
            const meta = getStepMeta(step);

            return (
              <div
                key={step.step}
                className={`flex items-center gap-4 p-3 rounded-xl border ${
                  isSuccess ? 'border-emerald-500/20 bg-emerald-500/5' :
                  isFailed  ? 'border-red-500/20 bg-red-500/5' :
                              'border-white/5 bg-white/3'
                }`}
              >
                {/* Status icon */}
                <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${
                  isSuccess ? 'bg-emerald-500/20 text-emerald-400' :
                  isFailed  ? 'bg-red-500/20 text-red-400' :
                              'bg-white/5 text-slate-600'
                }`}>
                  <span className="material-symbols-outlined text-sm">
                    {isSuccess ? 'check_circle' : isFailed ? 'error' : 'radio_button_unchecked'}
                  </span>
                </div>

                {/* Step info */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="material-symbols-outlined text-sm text-slate-500">
                      {STEP_ICONS[step.step] || 'circle'}
                    </span>
                    <span className={`text-sm font-semibold ${
                      isSuccess ? 'text-slate-200' : isFailed ? 'text-red-300' : 'text-slate-600'
                    }`}>
                      {STEP_LABELS[step.step] || step.step}
                    </span>
                  </div>
                  {meta && (
                    <p className="text-[11px] text-slate-500 mt-0.5 ml-6">{meta}</p>
                  )}
                  {isFailed && step.errorMessage && (
                    <p className="text-[11px] text-red-400 mt-0.5 ml-6">{step.errorMessage}</p>
                  )}
                </div>

                {/* Duration */}
                <span className={`text-[11px] font-mono shrink-0 ${
                  isSkipped ? 'text-slate-600' : 'text-slate-400'
                }`}>
                  {isSkipped ? '-' : `${step.durationMs}ms`}
                </span>
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div className="px-6 pb-5 pt-2 border-t border-white/5">
          <p className="text-[11px] text-slate-600 text-center">
            {real
              ? '실제 문서 기반 RAG 파이프라인이 실행되었습니다.'
              : 'MOCK_RAG=true 모드로 실행되었습니다. 실제 문서 파싱 및 벡터 검색이 생략되었습니다.'}
          </p>
        </div>
      </div>
    </div>
  );
};
