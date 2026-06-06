import React from 'react';
import { BrowserRouter as Router, Routes, Route, NavLink, Navigate, useNavigate, useLocation } from 'react-router-dom';

// Real Page Components
import { DashboardPage } from './pages/DashboardPage';
import { ProjectSetupPage } from './pages/ProjectSetupPage';
import { DocumentUploadPage } from './pages/DocumentUploadPage';
import { AnalysisResultPage } from './pages/AnalysisResultPage';
import { ReportPreviewPage } from './pages/ReportPreviewPage';

const NavigationWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const isReportPage = location.pathname === '/report-demo';
  const [isApiModalOpen, setIsApiModalOpen] = React.useState(false);
  const [apiKey, setApiKey] = React.useState(localStorage.getItem('yeonam_llm_api_key') || '');
  const [hasApiKey, setHasApiKey] = React.useState(!!localStorage.getItem('yeonam_llm_api_key'));

  const handleSaveApiKey = (key: string) => {
    if (key.trim()) {
      localStorage.setItem('yeonam_llm_api_key', key.trim());
      setApiKey(key.trim());
      setHasApiKey(true);
    } else {
      localStorage.removeItem('yeonam_llm_api_key');
      setApiKey('');
      setHasApiKey(false);
    }
    setIsApiModalOpen(false);
  };

  if (isReportPage) {
    return (
      <div className="min-h-screen bg-background text-on-background flex flex-col" style={{ background: 'linear-gradient(135deg, #0F0F1A 0%, #151528 100%)' }}>
        {children}
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background text-on-background overflow-x-hidden" style={{ background: 'radial-gradient(circle at top left, #0F0F1A, #151528)' }}>
      {/* TopNavBar */}
      <header className="fixed top-0 w-full z-50 flex justify-between items-center px-margin-desktop py-sm bg-surface/60 backdrop-blur-xl border-b border-white/10 shadow-lg shadow-primary/10">
        <div className="flex items-center gap-4">
          <span 
            onClick={() => navigate('/dashboard')}
            className="cursor-pointer font-headline-lg text-headline-lg font-bold bg-gradient-to-r from-primary to-tertiary bg-clip-text text-transparent select-none"
          >
            Yonam Tester
          </span>
        </div>
        
        <div className="flex items-center gap-6">
          <div className="hidden md:flex items-center gap-8 font-body-md text-body-md">
            <NavLink 
              to="/dashboard" 
              className={({ isActive }) => isActive ? "text-primary border-b-2 border-primary pb-1" : "text-on-surface-variant hover:text-on-surface transition-colors"}
            >
              Dashboard
            </NavLink>
            <NavLink 
              to="/upload-demo" 
              className={({ isActive }) => isActive ? "text-primary border-b-2 border-primary pb-1" : "text-on-surface-variant hover:text-on-surface transition-colors"}
            >
              문서 업로드
            </NavLink>
          </div>
          
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsApiModalOpen(true)}
              className={`px-4 py-2 rounded-xl font-bold flex items-center gap-2 border transition-all active:scale-95 ${
                hasApiKey 
                  ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20 shadow-[0_0_15px_rgba(16,185,129,0.15)]' 
                  : 'border-white/10 bg-white/5 text-on-surface-variant hover:bg-white/10'
              }`}
            >
              <span className="material-symbols-outlined">{hasApiKey ? 'vpn_key' : 'key'}</span>
              <span>API Key {hasApiKey ? '설정됨' : '설정'}</span>
            </button>

            <button 
              onClick={() => navigate('/setup')}
              className="primary-gradient px-6 py-2 rounded-xl text-white font-bold flex items-center gap-2 active:scale-95 transition-transform"
              style={{ background: 'linear-gradient(45deg, #6366f1, #8b5cf6)' }}
            >
              <span className="material-symbols-outlined">add_circle</span>
              새 프로젝트 생성
            </button>
          </div>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="pt-32 pb-24 min-h-screen">
        <div className="max-w-7xl mx-auto px-margin-mobile md:px-margin-desktop space-y-lg">
          {children}
        </div>
      </main>

      {/* API Key Modal */}
      {isApiModalOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/75 backdrop-blur-sm">
          <div className="bg-surface-container-high border border-white/10 rounded-2xl p-6 shadow-2xl w-full max-w-md relative" style={{ background: '#1e1e2f' }}>
            <h3 className="text-xl font-bold mb-2 bg-gradient-to-r from-primary to-tertiary bg-clip-text text-transparent">
              LLM API Key 설정
            </h3>
            <p className="text-sm text-on-surface-variant mb-4">
              OpenAI API Key를 브라우저에 안전하게 저장합니다. 이 키는 로컬 스토리지에 보관되며 분석 요청 시에만 사용됩니다.
            </p>
            
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-on-surface-variant mb-1">API KEY</label>
                <input
                  type="password"
                  placeholder="sk-..."
                  defaultValue={apiKey}
                  onCopy={(e) => e.preventDefault()}
                  id="api-key-input"
                  className="w-full px-4 py-2.5 bg-surface-container-lowest border border-white/10 rounded-xl text-on-surface placeholder:text-on-surface-variant/40 focus:outline-none focus:border-primary/50 text-sm font-mono"
                />
              </div>
            </div>

            <div className="flex justify-end gap-3 mt-6">
              <button
                onClick={() => {
                  handleSaveApiKey('');
                }}
                className="px-4 py-2 rounded-xl bg-red-500/10 text-red-400 border border-red-500/20 hover:bg-red-500/20 font-bold text-sm transition-all"
              >
                삭제
              </button>
              <button
                onClick={() => setIsApiModalOpen(false)}
                className="px-4 py-2 rounded-xl bg-white/5 text-on-surface hover:bg-white/10 font-bold text-sm transition-all"
              >
                취소
              </button>
              <button
                onClick={() => {
                  const input = document.getElementById('api-key-input') as HTMLInputElement;
                  handleSaveApiKey(input ? input.value : '');
                }}
                className="px-5 py-2 rounded-xl text-white font-bold text-sm transition-all active:scale-95"
                style={{ background: 'linear-gradient(45deg, #6366f1, #8b5cf6)' }}
              >
                저장
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const App: React.FC = () => {
  return (
    <Router>
      <NavigationWrapper>
        <Routes>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/setup" element={<ProjectSetupPage />} />
          <Route path="/upload-demo" element={<DocumentUploadPage />} />
          <Route path="/analysis-demo" element={<AnalysisResultPage />} />
          <Route path="/report-demo" element={<ReportPreviewPage />} />
        </Routes>
      </NavigationWrapper>
    </Router>
  );
};

export default App;
