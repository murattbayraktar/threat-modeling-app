import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { ReviewDetail as Detail } from '../api/types';
import PipelineProgress from '../components/PipelineProgress';
import BrowserLlmPanel from '../components/BrowserLlmPanel';
import ModelEditor from '../components/ModelEditor';
import ThreatReview from '../components/ThreatReview';
import ReportView from '../components/ReportView';
import TracePanel from '../components/TracePanel';
import DocumentsPanel from '../components/DocumentsPanel';
import CandidatesPanel from '../components/CandidatesPanel';
import { STATUS_LABEL } from '../lib/labels';

type Tab = 'documents' | 'model' | 'candidates' | 'threats' | 'report' | 'trace';

const TABS: { id: Tab; label: string }[] = [
  { id: 'documents', label: 'Dokümanlar' },
  { id: 'model', label: 'Mimari model' },
  { id: 'candidates', label: 'Adaylar ve kaynaklar' },
  { id: 'threats', label: 'Tehditler' },
  { id: 'report', label: 'Rapor' },
  { id: 'trace', label: 'İzlenebilirlik' },
];

function defaultTab(d: Detail): Tab {
  switch (d.review.status) {
    case 'WAITING_MODEL_APPROVAL': return 'model';
    case 'IN_EXPERT_REVIEW': return 'threats';
    case 'COMPLETED': return 'report';
    default: return d.modelVersion ? 'model' : 'documents';
  }
}

export default function ReviewDetail() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [tab, setTab] = useState<Tab | null>(null);
  const [version, setVersion] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const d = await api.review(id!);
      setDetail((prev) => {
        if (!prev || prev.review.status !== d.review.status || prev.modelVersion !== d.modelVersion || prev.threats !== d.threats) {
          setVersion((v) => v + 1);
        }
        // Akış yeni bir analist adımına geçtiyse ilgili sekmeyi aç
        if (prev && prev.review.status !== d.review.status) setTab(defaultTab(d));
        return d;
      });
      setTab((t) => t ?? defaultTab(d));
    } catch (e) {
      setError((e as Error).message);
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  // Akış ilerlerken sık, beklerken seyrek yenile
  useEffect(() => {
    if (!detail) return;
    const active = detail.running || detail.review.status === 'IN_PROGRESS' || detail.review.status === 'WAITING_LLM';
    const t = setTimeout(load, active ? 1200 : 6000);
    return () => clearTimeout(t);
  }, [detail, load]);

  const refresh = useCallback(() => {
    setVersion((v) => v + 1);
    load();
  }, [load]);

  if (error) return <div className="notice error">{error}</div>;
  if (!detail) return <div className="muted">Yükleniyor…</div>;
  const r = detail.review;
  const failed = detail.steps.find((s) => s.status === 'FAILED');

  return (
    <>
      <div className="row between">
        <div className="stack" style={{ gap: 4 }}>
          <div className="muted small"><Link to="/">İncelemeler</Link> / {r.code}</div>
          <div className="row">
            <h1>{r.title}</h1>
            <span className="pill blue">{STATUS_LABEL[r.status]}</span>
            <span className="pill gray">LLM: {r.llmMode === 'BROWSER' ? 'Tarayıcı' : 'Sunucu'}</span>
          </div>
          <span className="muted small">{[r.businessUnit, r.dataClassification, r.analyst && `Analist: ${r.analyst}`].filter(Boolean).join(' · ')}</span>
        </div>
        <div className="row no-print">
          {failed && <button className="btn primary" onClick={() => api.retry(r.id).then(refresh).catch((e) => setError(e.message))}>Başarısız adımı yeniden dene</button>}
          {r.llmMode === 'BROWSER' && <button className="btn" onClick={() => api.setLlmMode(r.id, 'SERVER').then(refresh)}>Sunucu LLM'ine geç</button>}
        </div>
      </div>

      <PipelineProgress steps={detail.steps} />

      {r.llmMode === 'BROWSER' && detail.pendingLlmCallId && (
        <BrowserLlmPanel key={detail.pendingLlmCallId} reviewId={r.id} onChanged={refresh} />
      )}

      <div className="tabs no-print" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} className={'tab' + (tab === t.id ? ' active' : '')} onClick={() => setTab(t.id)}>
            {t.label}
            {t.id === 'threats' && detail.threats > 0 ? ` (${detail.threats})` : ''}
          </button>
        ))}
      </div>

      {tab === 'documents' && <DocumentsPanel reviewId={r.id} documents={detail.documents} version={version} />}
      {tab === 'model' && <ModelEditor reviewId={r.id} version={version} onChanged={refresh} />}
      {tab === 'candidates' && <CandidatesPanel reviewId={r.id} version={version} />}
      {tab === 'threats' && <ThreatReview detail={detail} version={version} onChanged={refresh} />}
      {tab === 'report' && <ReportView review={r} version={version} onChanged={refresh} />}
      {tab === 'trace' && <TracePanel reviewId={r.id} version={version} />}
    </>
  );
}
