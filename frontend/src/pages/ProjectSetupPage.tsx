import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { projectApi } from '../services/api';

export const ProjectSetupPage: React.FC = () => {
  const navigate = useNavigate();
  const [projectName, setProjectName] = useState('');
  const [description, setDescription] = useState('');
  const [githubUrl, setGithubUrl] = useState('');
  const [githubBranch, setGithubBranch] = useState('main');
  
  const [urlError, setUrlError] = useState('');
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');

  const githubRegex = /^(https?:\/\/)?(www\.)?github\.com\/[a-zA-Z0-9-_.]+\/[a-zA-Z0-9-_.]+(\/.*)?$/;

  useEffect(() => {
    if (githubUrl.trim() === '') {
      setUrlError('');
      return;
    }

    if (!githubRegex.test(githubUrl.trim())) {
      setUrlError('❌ 유효하지 않은 GitHub URL 형식이거나 접근 불가능한 리포지토리입니다. Public 리포지토리 주소를 확인해주세요.');
    } else {
      setUrlError('');
    }
  }, [githubUrl]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (projectName.trim() === '') {
      setErrorMessage('프로젝트명을 입력해 주세요.');
      return;
    }
    if (githubUrl.trim() !== '' && !githubRegex.test(githubUrl.trim())) {
      setErrorMessage('GitHub URL 주소를 확인해 주세요.');
      return;
    }

    setLoading(true);
    setErrorMessage('');

    try {
      const response = await projectApi.create({
        projectName,
        description,
        githubUrl: githubUrl.trim() !== '' ? githubUrl.trim() : undefined,
        githubBranch: githubBranch.trim() !== '' ? githubBranch.trim() : undefined,
      });

      // Navigate to file upload for the new project
      navigate(`/upload-demo?projectId=${response.data.projectId}`);
    } catch (err: any) {
      console.error(err);
      if (err.response?.data?.message) {
        setErrorMessage(err.response.data.message);
      } else {
        setErrorMessage('프로젝트 등록 실패: 서버 상태 또는 네트워크 설정을 확인해 주세요.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="w-full max-w-3xl mx-auto glass-panel p-md md:p-lg rounded-xl animate-in fade-in slide-in-from-bottom-4 duration-700 relative overflow-hidden">
      {/* Background Decorative Orbs */}
      <div className="absolute top-[-10%] right-[-5%] w-[400px] h-[400px] rounded-full bg-indigo-500/5 blur-[100px] -z-10 pointer-events-none"></div>
      <div className="absolute bottom-[-10%] left-[-5%] w-[300px] h-[300px] rounded-full bg-purple-500/5 blur-[100px] -z-10 pointer-events-none"></div>

      {/* Header Section */}
      <div className="mb-lg">
        <h1 className="font-headline-lg text-headline-lg text-on-surface mb-xs flex items-center gap-2">
          <span className="material-symbols-outlined text-primary text-3xl">folder_shared</span>
          프로젝트 셋업 (Project Setup)
        </h1>
        <p className="text-on-surface-variant font-body-md text-body-md">새로운 분석 대상을 등록하기 위해 프로젝트의 초기 환경을 구성합니다.</p>
      </div>

      {errorMessage && (
        <div className="flex items-start gap-3 p-4 rounded-lg bg-red-500/10 border border-red-500/20 text-red-200 text-sm mb-6">
          <span className="material-symbols-outlined text-red-400">error</span>
          <span>{errorMessage}</span>
        </div>
      )}

      {/* Form */}
      <form onSubmit={handleSubmit} className="space-y-md">
        {/* Project Name */}
        <div className="space-y-xs">
          <label className="font-label-caps text-label-caps text-on-surface-variant block">PROJECT NAME <span className="text-red-400">*</span></label>
          <input 
            className="w-full bg-white/5 border border-white/10 rounded-lg p-md text-on-surface focus:ring-2 focus:ring-primary/50 focus:border-primary transition-all backdrop-blur-md outline-none text-slate-200 text-sm" 
            placeholder="프로젝트 이름을 입력하세요..." 
            type="text"
            value={projectName}
            onChange={(e) => setProjectName(e.target.value)}
            disabled={loading}
            required
          />
        </div>

        {/* Description & Key Functions */}
        <div className="space-y-xs">
          <label className="font-label-caps text-label-caps text-on-surface-variant block">DESCRIPTION & KEY FUNCTIONS</label>
          <textarea 
            className="w-full bg-white/5 border border-white/10 rounded-lg p-md text-on-surface focus:ring-2 focus:ring-primary/50 focus:border-primary transition-all backdrop-blur-md outline-none resize-none text-slate-200 text-sm" 
            placeholder="프로젝트의 주요 기능과 검증 대상 범위에 대해 기술하세요..." 
            rows={4}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            disabled={loading}
          />
        </div>

        {/* GitHub URL */}
        <div className="space-y-xs">
          <label className="font-label-caps text-label-caps text-on-surface-variant block">GITHUB URL (선택)</label>
          <div className="relative">
            <input 
              className={`w-full font-mono bg-white/5 border rounded-lg p-md pr-10 text-on-surface transition-all backdrop-blur-md outline-none text-slate-200 text-sm ${
                urlError ? 'border-red-500/50 focus:ring-red-500/30' : 'border-white/10 focus:ring-primary/50 focus:border-primary'
              }`} 
              type="text" 
              placeholder="https://github.com/owner/repository"
              value={githubUrl}
              onChange={(e) => setGithubUrl(e.target.value)}
              disabled={loading}
            />
            {urlError && (
              <span className="material-symbols-outlined absolute right-4 top-1/2 -translate-y-1/2 text-error">error</span>
            )}
          </div>
          {urlError && (
            <p className="text-error text-xs mt-xs flex items-center gap-xs">
              {urlError}
            </p>
          )}
        </div>

        {/* Default Branch & Status */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-md">
          <div className="space-y-xs">
            <label className="font-label-caps text-label-caps text-on-surface-variant block">DEFAULT BRANCH</label>
            <input 
              className="w-full font-mono bg-white/5 border border-white/10 rounded-lg p-md text-on-surface focus:ring-2 focus:ring-primary/50 transition-all backdrop-blur-md outline-none text-slate-200 text-sm" 
              type="text" 
              placeholder="main"
              value={githubBranch}
              onChange={(e) => setGithubBranch(e.target.value)}
              disabled={loading}
            />
          </div>

          {/* Integration Status */}
          <div className="space-y-xs flex flex-col justify-end">
            <label className="font-label-caps text-label-caps text-on-surface-variant block">INTEGRATION STATUS</label>
            <div className="inline-flex items-center gap-xs px-sm py-md rounded-lg bg-surface-container-high/60 border border-white/5 h-[46px]">
              {githubUrl.trim() === '' ? (
                <>
                  <span className="w-2 h-2 rounded-full bg-slate-500 mr-2"></span>
                  <span className="font-label-caps text-label-caps text-slate-400 font-bold text-xs">로컬 모드 (LOCAL ONLY)</span>
                </>
              ) : urlError ? (
                <>
                  <span className="w-2 h-2 rounded-full bg-red-400 mr-2"></span>
                  <span className="font-label-caps text-label-caps text-red-400 font-bold text-xs">연동 실패 (INVALID)</span>
                </>
              ) : (
                <>
                  <span className="w-2 h-2 rounded-full bg-yellow-400 animate-pulse mr-2"></span>
                  <span className="font-label-caps text-label-caps text-yellow-400 font-bold text-xs">연동 대기 중 (PENDING)</span>
                </>
              )}
            </div>
          </div>
        </div>

        {/* Decorative Visual Element (Bento-lite) */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-base pt-md">
          <div className="p-md glass-panel rounded-lg flex flex-col items-center justify-center text-center">
            <span className="material-symbols-outlined text-secondary mb-xs text-xl">security</span>
            <span className="text-[10px] font-bold tracking-wider font-mono text-indigo-300">AUTO-SCAN</span>
          </div>
          <div className="p-md glass-panel rounded-lg flex flex-col items-center justify-center text-center border-t-2 border-t-primary/30">
            <span className="material-symbols-outlined text-primary mb-xs text-xl">speed</span>
            <span className="text-[10px] font-bold tracking-wider font-mono text-indigo-300">HIGH PERF</span>
          </div>
          <div className="p-md glass-panel rounded-lg flex flex-col items-center justify-center text-center">
            <span className="material-symbols-outlined text-tertiary mb-xs text-xl">cloud_done</span>
            <span className="text-[10px] font-bold tracking-wider font-mono text-indigo-300">CLOUD SYNC</span>
          </div>
        </div>

        {/* Actions */}
        <div className="flex flex-col md:flex-row gap-md pt-lg">
          <button 
            type="submit"
            disabled={loading || !!urlError}
            className="flex-1 primary-gradient py-md rounded-lg font-bold text-white flex items-center justify-center gap-sm transition-all active:scale-[0.98] disabled:opacity-40 disabled:pointer-events-none"
            style={{ background: 'linear-gradient(45deg, #6366F1, #8B5CF6)' }}
          >
            {loading ? (
              <>
                <span className="material-symbols-outlined animate-spin">progress_activity</span>
                연동 검증 및 생성 중...
              </>
            ) : (
              <>
                연동 검증 및 생성
                <span className="material-symbols-outlined">launch</span>
              </>
            )}
          </button>
          <button 
            type="button"
            onClick={() => navigate('/dashboard')}
            className="px-12 py-md bg-white/5 border border-white/10 rounded-lg font-semibold hover:bg-white/10 transition-all active:scale-[0.98] text-slate-300"
          >
            취소
          </button>
        </div>
      </form>
    </div>
  );
};
