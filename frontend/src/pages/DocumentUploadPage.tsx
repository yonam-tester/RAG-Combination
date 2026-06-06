import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { projectApi, fileApi, analysisApi, UploadedFile, api } from '../services/api';

export const DocumentUploadPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [projectId, setProjectId] = useState('');
  
  // Tab/Step control
  const [step, setStep] = useState(1); // 1: Upload & Configure, 2: Analysis Polling
  const [files, setFiles] = useState<UploadedFile[]>([]);
  const [uploadType, setUploadType] = useState<'REQUIREMENT_SPEC' | 'REFERENCE'>('REQUIREMENT_SPEC');
  
  // Recommendations and settings
  const [selectedPerspectives, setSelectedPerspectives] = useState<string[]>([]);
  const [customPrompt, setCustomPrompt] = useState('');
  const [selectedDocIds, setSelectedDocIds] = useState<string[]>([]);
  const [recommendationReason, setRecommendationReason] = useState('');

  useEffect(() => {
    const doneFiles = files.filter(f => f.status === 'DONE').map(f => f.documentId);
    setSelectedDocIds(doneFiles);
  }, [files]);
  
  // Job triggers
  const [analysisProgress, setAnalysisProgress] = useState(0);
  const [analysisMessage, setAnalysisMessage] = useState('대기 중...');
  const [isSandboxMode, setIsSandboxMode] = useState(() => {
    const saved = localStorage.getItem('yeonam_sandbox_mode');
    return saved !== null ? saved === 'true' : false; // Default to false (Live LLM Mode enabled)
  });
  
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  const GITHUB_README_SIMULATION_FLAG = 'README.md';

  useEffect(() => {
    fetchProjects();
  }, []);

  const fetchProjects = async () => {
    try {
      const response = await projectApi.getAll();
      
      const urlProjId = searchParams.get('projectId');
      if (urlProjId && response.data.some(p => p.projectId === urlProjId)) {
        setProjectId(urlProjId);
      } else if (response.data.length > 0) {
        setProjectId(response.data[0].projectId);
      }

      // Check if step parameter is explicitly passed
      const urlStep = searchParams.get('step');
      if (urlStep === '2') {
        setStep(1); // start at configure but prefilled
      }
    } catch (err) {
      console.error(err);
      setError('프로젝트 정보를 불러오는 데 실패했습니다.');
    }
  };

  useEffect(() => {
    if (projectId) {
      fetchProjectFiles(projectId);
    }
  }, [projectId]);

  useEffect(() => {
    let intervalId: any = null;
    const hasUnfinishedFiles = files.some(
      f => f.status !== 'DONE' && f.status !== 'FAILED'
    );

    if (projectId && hasUnfinishedFiles) {
      intervalId = setInterval(() => {
        fetchProjectFiles(projectId);
      }, 2000);
    }

    return () => {
      if (intervalId) {
        clearInterval(intervalId);
      }
    };
  }, [files, projectId]);

  const fetchRecommendations = async (pId: string) => {
    try {
      const response = await analysisApi.getRecommendations(pId);
      if (response.data) {
        setSelectedPerspectives(response.data.recommendedPerspectives || []);
        setRecommendationReason(response.data.reason || '');
      }
    } catch (err) {
      console.error("Failed to fetch QA recommendations", err);
    }
  };

  const fetchProjectFiles = async (pId: string) => {
    try {
      const response = await fileApi.getByProject(pId);
      setFiles(response.data);
      // Fetch recommendations on file list updates
      fetchRecommendations(pId);
    } catch (err) {
      console.error(err);
    }
  };

  const formatFileSize = (bytes: number) => {
    if (!bytes) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  };

  const validateFile = (file: File): string => {
    const allowed = ['pdf', 'md', 'txt', 'docx'];
    const ext = file.name.substring(file.name.lastIndexOf('.') + 1).toLowerCase();
    
    if (!allowed.includes(ext)) {
      return `지원하지 않는 파일 형식입니다: .${ext} (PDF, MD, TXT, DOCX 가능)`;
    }
    
    const maxSize = 20 * 1024 * 1024; // 20MB
    if (file.size > maxSize) {
      return `파일 용량이 20MB 초과 제한에 걸렸습니다: ${(file.size / 1024 / 1024).toFixed(1)}MB`;
    }

    return '';
  };

  const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const fileList = event.target.files;
    if (!fileList || fileList.length === 0 || !projectId) return;

    const file = fileList[0];
    const validationError = validateFile(file);
    if (validationError) {
      setError(validationError);
      return;
    }

    setError('');
    setUploading(true);

    try {
      await fileApi.upload(projectId, file, uploadType);
      fetchProjectFiles(projectId);
    } catch (err: any) {
      console.error(err);
      setError(err.response?.data?.message || '파일 업로드 실패: 서버 연결 상태를 확인해 주세요.');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
  };

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    if (uploading || !projectId) return;

    const file = e.dataTransfer.files[0];
    if (!file) return;

    const validationError = validateFile(file);
    if (validationError) {
      setError(validationError);
      return;
    }

    setError('');
    setUploading(true);

    try {
      await fileApi.upload(projectId, file, uploadType);
      fetchProjectFiles(projectId);
    } catch (err: any) {
      console.error(err);
      setError(err.response?.data?.message || '파일 드롭 업로드에 실패했습니다.');
    } finally {
      setUploading(false);
    }
  };

  const handleDeleteFile = async (fileId: string) => {
    if (confirm('이 원본 문서를 S3 및 DB에서 영구 삭제하시겠습니까?')) {
      try {
        await fileApi.delete(fileId);
        fetchProjectFiles(projectId);
      } catch (err) {
        console.error(err);
        setError('문서 파기에 실패했습니다.');
      }
    }
  };

  const handlePerspectiveToggle = (p: string) => {
    if (selectedPerspectives.includes(p)) {
      setSelectedPerspectives(selectedPerspectives.filter(item => item !== p));
    } else {
      setSelectedPerspectives([...selectedPerspectives, p]);
    }
  };

  const [currentAnalysisId, setCurrentAnalysisId] = useState('');

  const handleStartAnalysis = async () => {
    if (!projectId) return;
    if (selectedDocIds.length === 0) {
      setError('분석할 문서를 최소 하나 이상 선택해 주세요.');
      return;
    }
    if (selectedPerspectives.length === 0) {
      setError('최소 하나의 QA 검증 관점을 선택해 주세요.');
      return;
    }

    setLoading(true);
    setError('');

    try {
      const response = await analysisApi.start(projectId, {
        targetDocumentIds: selectedDocIds,
        qaPerspectives: selectedPerspectives,
        customPrompt,
        llmApiKey: localStorage.getItem('yeonam_llm_api_key') || '',
      });

      const newAnalysisId = response.data.analysisId;
      setCurrentAnalysisId(newAnalysisId);
      setStep(2);
      
      // Trigger status polling
      startPolling(newAnalysisId);
    } catch (err: any) {
      console.error(err);
      setError(err.response?.data?.message || 'AI 분석 트리거에 실패했습니다.');
      setLoading(false);
    }
  };

  const startPolling = (anlId: string) => {
    let checkCount = 0;
    const interval = setInterval(async () => {
      try {
        const response = await analysisApi.getStatus(anlId);
        setAnalysisProgress(response.data.progressPercentage);
        setAnalysisMessage(response.data.message);

        // Simulation for Callback triggers (Demo mode):
        // If external FastAPI doesn't reply in 4 polls (8s), we automatically send mock results to callback 
        // to support full testing flow even if user has no Python servers running.
        checkCount++;
        if (isSandboxMode && response.data.status === 'PROCESSING' && checkCount === 4) {
          triggerOfflineMockCallback(anlId);
        }

        if (response.data.status === 'COMPLETED') {
          clearInterval(interval);
          setLoading(false);
          // Redirect to results visualization page
          navigate(`/analysis-demo?analysisId=${anlId}`);
        } else if (response.data.status === 'FAILED') {
          clearInterval(interval);
          setError(response.data.message || 'AI 테스트 시나리오 분리가 실패했습니다.');
          setLoading(false);
        }
      } catch (err) {
        console.error(err);
        clearInterval(interval);
        setError('분석 상태 폴링 중 오류가 발생했습니다.');
        setLoading(false);
      }
    }, 2000);
  };

  // Helper function to mock analysis callback during offline sandbox testing
  const triggerOfflineMockCallback = async (anlId: string) => {
    try {
      await apiForCallbackTrigger(anlId);
    } catch (err) {
      console.error('Offline callback mock trigger failed', err);
    }
  };

  // Raw mock payload callback trigger
  const apiForCallbackTrigger = async (anlId: string) => {
    const payload = {
      summary: "연암 테스터 로그인 및 결제 시스템 모듈 요구사항 명세 검증 완료. 회원 등급별 인증 관리 및 결제 실패 예외 흐름 확인.",
      testCases: [
        {
          testCaseId: "TC-2026-0001",
          requirementId: "REQ-2026-0001",
          requirementText: "로그인 시 잘못된 비밀번호를 5회 연속 입력하면 계정이 10분간 잠금 상태로 전환되어야 한다.",
          testCaseName: "패스워드 5회 연속 실패 계정 잠금 검증",
          testScenario: "사용자가 잘못된 비밀번호를 5회 연속 입력하여 계정이 실제로 잠기고 10분간 로그인이 거부되는지 확인한다.",
          precondition: "활성화된 사용자 계정이 존재하며 현재 잠겨있지 않아야 한다.",
          testSteps: [
            "1. 로그인 UI 페이지에 접속한다.",
            "2. 올바른 아이디와 틀린 비밀번호를 입력하고 로그인 버튼을 누른다. (총 5회 반복)",
            "3. 5번째 실패 후 화면에 '계정이 10분간 잠겼습니다'라는 경고 문구가 출력되는지 확인한다.",
            "4. 6번째 시도로 올바른 비밀번호를 입력하고 로그인을 시도했을 때, 차단 알림이 표시되며 로그인되지 않는지 검증한다."
          ],
          expectedResult: "5회 연속 실패 시 계정이 즉시 잠기며, 올바른 비밀번호를 넣더라도 10분간은 로그인 차단 에러 메시지가 표출된다.",
          priority: "HIGH",
          confidenceLevel: "HIGH",
          riskTags: ["인증_실패", "보안_위험"],
          evidences: [
            {
              chunkId: "CHK-001",
              evidenceText: "로그인 시 잘못된 비밀번호를 5회 연속 입력하면 계정이 잠금 상태로 전환되어야 한다.",
              sourceName: "요구사항_명세서.md",
              sourceSection: "3.2 사용자 인증 및 보안 설정"
            }
          ]
        },
        {
          testCaseId: "TC-2026-0002",
          requirementId: "REQ-2026-0002",
          requirementText: "결제 모듈 호출 중 카드 한도 초과 오류 발생 시, 트랜잭션이 롤백되고 사용자에게 한도 초과 사유를 노출해야 한다.",
          testCaseName: "결제 한도 초과 오류 발생 시 롤백 및 사유 노출",
          testScenario: "카드 결제 처리 중 잔액/한도 부족 오류를 발생시켜 구매 처리가 롤백되고 알림창이 정확히 뜨는지 확인한다.",
          precondition: "상품 구매 화면에서 결제 수단이 등록된 상태여야 한다.",
          testSteps: [
            "1. 상품을 장바구니에 담고 결제하기 버튼을 누른다.",
            "2. 잔액이 부족한 테스트용 신용카드 정보를 입력한다.",
            "3. 결제 승인 요청을 진행하고 네트워크 승인 거부 코드를 확인한다.",
            "4. 상품 구매 내역이 비어 있는지(롤백) 확인하고 UI상 한도초과 팝업을 확인한다."
          ],
          expectedResult: "결제는 실패하고 주문 내역은 롤백되며 '신용카드 한도 초과로 결제할 수 없습니다' 메시지가 화면에 노출된다.",
          priority: "MEDIUM",
          confidenceLevel: "HIGH",
          riskTags: ["결제_오류", "입력값_오류"],
          evidences: [
            {
              chunkId: "CHK-002",
              evidenceText: "결제 API가 오류 응답(한도 초과 등)을 반환하면 주문 처리를 즉시 롤백하고 원인을 경고창으로 제공해야 함.",
              sourceName: "README.md",
              sourceSection: "4. 결제 API 인터페이스 규격"
            }
          ]
        }
      ],
      missingItems: [
        "세션 가로채기(Hijacking) 대응을 위한 리프레시 토큰의 DB 유효시간 갱신 처리가 명세서상에 부착되어 있지 않습니다.",
        "결제 타임아웃(30초 이상 응답 지연) 상황에서의 백업 재시도(Retry) 알고리즘 정의가 필요합니다."
      ]
    };

    // Make direct POST call to endpoint as if Python server is returning it
    await api.post(`/internal/analysis/${anlId}/callback`, payload);
  };



  return (
    <div className="max-w-7xl mx-auto space-y-8 animate-fade-in">
      {/* Tab/Step header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="material-symbols-outlined text-indigo-400 text-3xl">description</span>
          <div>
            <h2 className="text-2xl font-bold text-white">문서 업로드 및 분석 설정</h2>
            <p className="text-secondary text-sm">요구사항 기획서 파일을 업로드하고, QA 분석 관점을 선정해 TDD 시나리오를 구성합니다.</p>
          </div>
        </div>

        <div className="flex items-center gap-2 text-xs font-mono text-slate-500 bg-white/5 p-2 rounded-lg border border-white/5">
          <span className={`px-2 py-1 rounded ${step === 1 ? 'bg-indigo-500/10 text-indigo-300 border border-indigo-500/20' : 'bg-white/5'}`}>1. 업로드 및 설정</span>
          <span className="material-symbols-outlined text-xs">arrow_forward</span>
          <span className={`px-2 py-1 rounded ${step === 2 ? 'bg-indigo-500/10 text-indigo-300 border border-indigo-500/20' : 'bg-white/5'}`}>2. AI 분석 추론</span>
        </div>
      </div>

      {error && (
        <div className="flex items-start gap-3 p-4 rounded-lg bg-red-500/10 border border-red-500/20 text-red-200 text-sm">
          <span className="material-symbols-outlined text-red-400">error</span>
          <span>{error}</span>
        </div>
      )}

      {step === 1 && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-gutter">
          {/* Left Column: Drag & Drop upload + Uploaded files */}
          <div className="lg:col-span-7 space-y-gutter">
            {/* File Upload Section */}
            <div className="glass-panel p-md rounded-xl space-y-4">
              <div className="flex items-center gap-sm mb-md">
                <span className="material-symbols-outlined text-primary">upload_file</span>
                <h2 className="font-headline-lg-mobile text-headline-lg-mobile font-semibold text-white">문서 업로드</h2>
              </div>

              <div className="flex items-center gap-3">
                <label className="text-xs font-semibold text-slate-400">문서 구분:</label>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setUploadType('REQUIREMENT_SPEC')}
                    className={`px-3 py-1 rounded text-xs font-medium border transition-all ${
                      uploadType === 'REQUIREMENT_SPEC' 
                        ? 'bg-indigo-500/10 border-indigo-500/30 text-indigo-300' 
                        : 'bg-white/5 border-white/5 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    요구사항 기획서
                  </button>
                  <button
                    type="button"
                    onClick={() => setUploadType('REFERENCE')}
                    className={`px-3 py-1 rounded text-xs font-medium border transition-all ${
                      uploadType === 'REFERENCE' 
                        ? 'bg-indigo-500/10 border-indigo-500/30 text-indigo-300' 
                        : 'bg-white/5 border-white/5 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    참고자료/지식베이스
                  </button>
                </div>
              </div>

              {/* Drag & Drop Area */}
              <div
                onDragOver={handleDragOver}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
                className="group border-2 border-dashed border-outline-variant hover:border-primary rounded-xl p-10 text-center cursor-pointer bg-white/5 hover:bg-white/10 transition-all space-y-3"
              >
                <input
                  type="file"
                  ref={fileInputRef}
                  onChange={handleFileUpload}
                  className="hidden"
                  accept=".pdf,.md,.txt,.docx"
                  disabled={uploading}
                />
                
                {uploading ? (
                  <div className="space-y-2 py-4">
                    <span className="material-symbols-outlined text-4xl text-primary animate-spin">progress_activity</span>
                    <p className="text-sm font-semibold text-slate-300">MinIO 스토리지 전송 중...</p>
                  </div>
                ) : (
                  <>
                    <span className="material-symbols-outlined text-5xl text-on-surface-variant group-hover:text-primary transition-colors mb-sm" style={{ fontVariationSettings: "'wght' 300" }}>cloud_upload</span>
                    <div className="space-y-1">
                      <p className="text-sm font-semibold text-slate-300">마우스 드롭 또는 클릭하여 파일 업로드</p>
                      <p className="text-xs text-slate-500">PDF, MD, TXT, DOCX 형식 (최대 20MB 제한)</p>
                    </div>
                  </>
                )}
              </div>
            </div>

            {/* Uploaded files list */}
            <div className="glass-panel p-md rounded-xl space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-bold text-white text-sm">업로드된 파일 목록 ({files.length})</h3>
                {files.length > 0 && <span className="text-[10px] text-indigo-400 font-mono">Ready to Analyze</span>}
              </div>
              
              {files.length === 0 ? (
                <div className="text-center py-8 text-slate-600 text-sm">
                  아직 구성된 문서가 없습니다.
                </div>
              ) : (
                <div className="space-y-2.5 max-h-72 overflow-y-auto pr-1">
                  {files.map((file) => (
                    <div key={file.documentId} className="flex items-center justify-between p-3 rounded bg-white/5 border border-white/5 group hover:border-white/10 transition-all">
                      <div className="flex items-center gap-3 min-w-0 pr-4">
                        <input
                          type="checkbox"
                          disabled={file.status !== 'DONE'}
                          checked={selectedDocIds.includes(file.documentId)}
                          onChange={() => {
                            if (selectedDocIds.includes(file.documentId)) {
                              setSelectedDocIds(selectedDocIds.filter(id => id !== file.documentId));
                            } else {
                              setSelectedDocIds([...selectedDocIds, file.documentId]);
                            }
                          }}
                          className="w-4 h-4 rounded border-outline-variant bg-surface-container text-primary focus:ring-primary/40 cursor-pointer shrink-0 disabled:opacity-30 disabled:cursor-not-allowed"
                        />
                        <span className={`material-symbols-outlined text-lg ${file.fileName === GITHUB_README_SIMULATION_FLAG ? "text-indigo-400 animate-pulse" : "text-slate-400"}`}>description</span>
                        <div className="min-w-0">
                          <span className="block text-sm text-slate-200 truncate font-mono">{file.fileName}</span>
                          <span className="text-[10px] text-slate-500 block mt-0.5">
                            {file.fileType === 'REQUIREMENT_SPEC' ? '요구사항 기획서' : '참고자료'} · {formatFileSize(file.fileSizeByte)} · {file.status}
                          </span>
                        </div>
                      </div>
                      <button
                        onClick={() => handleDeleteFile(file.documentId)}
                        className="p-1 text-slate-500 hover:text-red-400 hover:bg-red-500/5 rounded transition-all shrink-0"
                        title="파일 삭제"
                      >
                        <span className="material-symbols-outlined text-sm">delete</span>
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Right Column: Perspectives & Analysis Trigger */}
          <div className="lg:col-span-5 flex flex-col gap-gutter">
            {/* QA Analysis Perspectives */}
            <div className="glass-panel p-md rounded-xl space-y-6">
              <div className="flex items-center gap-sm mb-4">
                <span className="material-symbols-outlined text-tertiary">psychology</span>
                <h2 className="font-headline-lg-mobile text-headline-lg-mobile font-semibold text-white">QA 분석 관점 설정</h2>
              </div>

              {recommendationReason && (
                <div className="p-3 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 text-xs leading-relaxed animate-fade-in flex items-start gap-2">
                  <span className="material-symbols-outlined text-sm shrink-0 mt-0.5">info</span>
                  <span>{recommendationReason}</span>
                </div>
              )}




              {/* Base Perspectives */}
              <div className="space-y-3 pt-2">
                <h3 className="text-xs font-label-caps text-on-surface-variant mb-sm uppercase tracking-wider">기본 QA 관점</h3>
                <div className="grid grid-cols-2 gap-sm">
                  {['SECURITY', 'BACKEND', 'FRONTEND', 'PERFORMANCE', 'API'].map((p) => {
                    const isSelected = selectedPerspectives.includes(p);
                    
                    return (
                      <label key={p} className="flex items-center gap-base cursor-pointer group select-none">
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={() => handlePerspectiveToggle(p)}
                          className="w-5 h-5 rounded border-outline-variant bg-surface-container text-primary focus:ring-primary/40 cursor-pointer"
                        />
                        <span className={`text-body-sm group-hover:text-primary transition-colors text-xs ${isSelected ? 'text-primary font-semibold' : 'text-slate-300'}`}>
                          {p}
                        </span>
                      </label>
                    );
                  })}
                </div>
              </div>
            </div>

            {/* Custom Prompt & Trigger */}
            <div className="glass-panel p-md rounded-xl flex-1 flex flex-col space-y-4">
              <div className="flex items-center gap-sm">
                <span className="material-symbols-outlined text-secondary">smart_toy</span>
                <h2 className="font-headline-lg-mobile text-headline-lg-mobile font-semibold text-white">커스텀 AI 프롬프트</h2>
              </div>
              <p className="text-xs text-on-surface-variant">AI에게 특정 도메인 규칙이나 주의가 필요한 비즈니스 로직을 명시하세요.</p>
              
              <textarea
                className="flex-1 w-full bg-surface-container-lowest/50 border border-white/10 rounded-lg p-md font-code-md text-code-md text-on-surface placeholder:text-on-surface-variant/40 resize-none glass-panel focus:glass-panel-heavy min-h-[100px]"
                value={customPrompt}
                onChange={(e) => setCustomPrompt(e.target.value)}
                placeholder="특정 요구사항 검증에 집중하도록 AI에게 지시할 추가 프롬프트를 입력하세요. 예: '신용카드 번호 마스킹 정책이 준수되었는지 집중적으로 확인해줘.'"
              />

              {/* Sandbox Demo Mode Toggle */}
              <div className="flex items-center justify-between p-3.5 rounded-xl bg-white/5 border border-white/5">
                <div className="flex items-center gap-2.5">
                  <span className="material-symbols-outlined text-sm text-indigo-400">science</span>
                  <div className="text-left">
                    <span className="text-xs font-bold text-white block">샌드박스 데모 모드 (오프라인)</span>
                    <span className="text-[10px] text-slate-400 block mt-0.5">활성화 시 AI 서버 없이 8초 후 가상 결과를 생성합니다.</span>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    const nextVal = !isSandboxMode;
                    setIsSandboxMode(nextVal);
                    localStorage.setItem('yeonam_sandbox_mode', String(nextVal));
                  }}
                  className={`w-12 h-6 rounded-full p-1 transition-colors duration-200 focus:outline-none flex items-center shrink-0 ${
                    isSandboxMode ? 'bg-indigo-500' : 'bg-slate-700'
                  }`}
                >
                  <div
                    className={`w-4 h-4 rounded-full bg-white transition-transform duration-200 ${
                      isSandboxMode ? 'transform translate-x-6' : ''
                    }`}
                  />
                </button>
              </div>

              <div className="flex flex-col gap-sm pt-2">
                <button
                  onClick={handleStartAnalysis}
                  disabled={selectedDocIds.length === 0 || loading}
                  className="w-full py-md rounded-xl bg-gradient-to-r from-[#6366F1] to-[#8B5CF6] text-white font-headline-lg-mobile font-bold flex items-center justify-center gap-sm hover:brightness-110 active:scale-[0.98] transition-all glow-indigo disabled:opacity-40 disabled:pointer-events-none"
                >
                  <span className="material-symbols-outlined text-lg">bolt</span>
                  분석 시작
                </button>
                <button
                  onClick={() => navigate('/dashboard')}
                  className="w-full py-2.5 rounded-xl glass-panel-heavy border-white/10 text-on-surface font-semibold flex items-center justify-center gap-sm hover:bg-white/10 active:scale-[0.98] transition-all text-xs"
                >
                  <span className="material-symbols-outlined text-sm">arrow_back</span>
                  이전으로
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="glass-panel p-10 max-w-2xl mx-auto space-y-8 animate-fade-in relative overflow-hidden">
          {/* Subtle Background Glow */}
          <div className="absolute inset-0 bg-indigo-500/5 blur-3xl rounded-full scale-75 pointer-events-none" />

          <div className="flex flex-col md:flex-row items-center gap-8">
            {/* Circular Progress Spinner */}
            <div className="relative w-28 h-28 flex-shrink-0">
              <svg className="w-full h-full -rotate-90" viewBox="0 0 36 36">
                <path 
                  className="text-white/10" 
                  d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831" 
                  fill="none" 
                  stroke="currentColor" 
                  strokeWidth="2.5"
                />
                <path 
                  className="text-primary transition-all duration-500 ease-out" 
                  d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831" 
                  fill="none" 
                  stroke="currentColor" 
                  strokeDasharray={`${analysisProgress}, 100`} 
                  strokeLinecap="round" 
                  strokeWidth="2.5"
                />
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <span className="font-bold text-xl text-primary font-mono">{analysisProgress}%</span>
              </div>
            </div>

            <div className="flex-1 text-center md:text-left space-y-2">
              <h3 className="text-xl font-bold text-white flex items-center justify-center md:justify-start gap-2">
                <span className="material-symbols-outlined text-primary animate-spin">progress_activity</span>
                AI 분석 진행 중...
              </h3>
              <p className="text-slate-400 text-xs leading-relaxed max-w-md mx-auto md:mx-0">
                S3 물리 저장소의 업로드 명세서를 파싱하고, RAG 지식베이스 검색 및 거대 언어 모델(LLM)에 조립하여 요구사항과 테스트 시나리오를 설계하고 있습니다.
              </p>
            </div>
          </div>

          {/* Stepper Steps */}
          <div className="border-t border-white/5 pt-6">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 relative">
              <div className="absolute top-4 left-0 w-full h-0.5 bg-white/5 -z-10 hidden md:block"></div>
              
              {/* Step 1: 문서 파싱 */}
              <div className="flex flex-col items-center gap-2">
                {analysisProgress >= 25 ? (
                  <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs font-bold">
                    <span className="material-symbols-outlined text-[16px]">check</span>
                  </div>
                ) : (
                  <div className="w-8 h-8 rounded-full bg-primary/20 border border-primary/40 flex items-center justify-center text-primary text-xs font-bold animate-pulse">
                    1
                  </div>
                )}
                <span className={`font-body-sm text-xs ${analysisProgress >= 25 ? 'text-primary font-bold' : 'text-on-surface'}`}>1. 문서 파싱</span>
              </div>

              {/* Step 2: 지식베이스 검색 */}
              <div className="flex flex-col items-center gap-2">
                {analysisProgress >= 50 ? (
                  <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs font-bold">
                    <span className="material-symbols-outlined text-[16px]">check</span>
                  </div>
                ) : analysisProgress >= 25 ? (
                  <div className="w-8 h-8 rounded-full bg-primary/20 border border-primary/40 flex items-center justify-center text-primary text-xs font-bold animate-pulse">
                    2
                  </div>
                ) : (
                  <div className="w-8 h-8 rounded-full bg-surface-container border border-white/10 flex items-center justify-center text-slate-500 text-xs font-bold">
                    2
                  </div>
                )}
                <span className={`font-body-sm text-xs ${analysisProgress >= 50 ? 'text-primary font-bold' : analysisProgress >= 25 ? 'text-on-surface' : 'text-on-surface-variant'}`}>2. 지식베이스 검색</span>
              </div>

              {/* Step 3: 테스트 케이스 생성 */}
              <div className="flex flex-col items-center gap-2">
                {analysisProgress >= 75 ? (
                  <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs font-bold">
                    <span className="material-symbols-outlined text-[16px]">check</span>
                  </div>
                ) : analysisProgress >= 50 ? (
                  <div className="w-8 h-8 rounded-full bg-primary/20 border border-primary/40 flex items-center justify-center text-primary text-xs font-bold animate-pulse">
                    3
                  </div>
                ) : (
                  <div className="w-8 h-8 rounded-full bg-surface-container border border-white/10 flex items-center justify-center text-slate-500 text-xs font-bold">
                    3
                  </div>
                )}
                <span className={`font-body-sm text-xs ${analysisProgress >= 75 ? 'text-primary font-bold' : analysisProgress >= 50 ? 'text-on-surface' : 'text-on-surface-variant'}`}>3. 테스트 케이스 생성</span>
              </div>

              {/* Step 4: 무결성 검증 */}
              <div className="flex flex-col items-center gap-2">
                {analysisProgress >= 100 ? (
                  <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs font-bold">
                    <span className="material-symbols-outlined text-[16px]">check</span>
                  </div>
                ) : analysisProgress >= 75 ? (
                  <div className="w-8 h-8 rounded-full bg-primary/20 border border-primary/40 flex items-center justify-center text-primary text-xs font-bold animate-pulse">
                    4
                  </div>
                ) : (
                  <div className="w-8 h-8 rounded-full bg-surface-container border border-white/10 flex items-center justify-center text-slate-500 text-xs font-bold">
                    4
                  </div>
                )}
                <span className={`font-body-sm text-xs ${analysisProgress >= 100 ? 'text-primary font-bold' : analysisProgress >= 75 ? 'text-on-surface' : 'text-on-surface-variant'}`}>4. 무결성 검증</span>
              </div>
            </div>
          </div>

          <div className="text-center pt-2 border-t border-white/5">
            <span className="text-[11px] text-slate-500 font-mono block">
              작업 단위 ID: {currentAnalysisId || '대기 중...'}
            </span>
            <span className="text-[10px] text-slate-400 block mt-1">
              상태: {analysisMessage}
            </span>
          </div>

          {error && (
            <div className="flex flex-col gap-3 p-4 rounded-lg bg-red-500/10 border border-red-500/20 text-red-200 text-sm mt-4 animate-fade-in">
              <div className="flex items-start gap-3">
                <span className="material-symbols-outlined text-red-400 shrink-0">error</span>
                <span className="text-left font-sans">{error}</span>
              </div>
              <button
                onClick={() => {
                  setError('');
                  setStep(1);
                }}
                className="mt-2 py-1.5 px-4 rounded-lg bg-red-500/20 border border-red-500/30 text-white text-xs font-semibold hover:bg-red-500/30 transition-all self-end"
              >
                설정 화면으로 돌아가기
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

