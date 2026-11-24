import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { ReviewSummary } from '../api/types';
import { LEVEL_LABEL, STATUS_LABEL, fmtDate, levelClass } from '../lib/labels';

const STATUS_CLASS: Record<string, string> = {
  IN_PROGRESS: 'pill blue', WAITING_MODEL_APPROVAL: 'pill orange', WAITING_LLM: 'pill purple',
  IN_EXPERT_REVIEW: 'pill blue', COMPLETED: 'pill green', FAILED: 'pill red',
};

export default function ReviewList() {
  const [items, setItems] = useState<ReviewSummary[] | null>(null);
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<string>('ALL');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = () => api.reviews().then(setItems).catch((e) => setError(e.message));
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

  const count = (s: string) => items?.filter((i) => i.review.status === s).length ?? 0;
  const shown = (items ?? []).filter((i) =>
    (filter === 'ALL' || i.review.status === filter) &&
    (q === '' || `${i.review.code} ${i.review.title} ${i.review.businessUnit} ${i.review.analyst}`.toLowerCase().includes(q.toLowerCase())));

  return (
    <>
      <div className="row between">
        <div className="stack" style={{ gap: 4 }}>
          <h1>İncelemeler</h1>
          <span className="muted">Güvenlik ekibine gelen proje dokümanları ve inceleme aşamaları</span>
        </div>
        <div className="row">
          <input type="search" placeholder="Kayıt, proje veya analist ara" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Ara" style={{ width: 260 }} />
          <Link className="btn primary" to="/new">+ Yeni inceleme</Link>
        </div>
      </div>
      {error && <div className="notice error">{error}</div>}
      <div className="grid">
        <div className="card stat"><span className="muted small">İşleniyor</span><span className="v">{count('IN_PROGRESS') + count('WAITING_LLM')}</span></div>
        <div className="card stat"><span className="muted small">Model onayı bekliyor</span><span className="v">{count('WAITING_MODEL_APPROVAL')}</span></div>
        <div className="card stat"><span className="muted small">Uzman incelemesinde</span><span className="v">{count('IN_EXPERT_REVIEW')}</span></div>
        <div className="card stat"><span className="muted small">Tamamlanan</span><span className="v">{count('COMPLETED')}</span></div>
      </div>
      <div className="card flush">
        <div className="card-head">
          <div className="row">
            {['ALL', 'WAITING_MODEL_APPROVAL', 'IN_EXPERT_REVIEW', 'WAITING_LLM', 'COMPLETED', 'FAILED'].map((s) => (
              <button key={s} className={'btn small' + (filter === s ? ' primary' : '')} onClick={() => setFilter(s)}>
                {s === 'ALL' ? 'Tümü' : STATUS_LABEL[s]}
              </button>
            ))}
          </div>
        </div>
        <div className="scroll-x">
          <table className="tbl">
            <thead>
              <tr><th>Kayıt</th><th>Proje</th><th>Birim</th><th>Durum</th><th>Risk özeti</th><th>Karar bekleyen</th><th>LLM</th><th>Analist</th><th>Güncelleme</th></tr>
            </thead>
            <tbody>
              {items === null && <tr><td colSpan={9} className="muted">Yükleniyor…</td></tr>}
              {items !== null && shown.length === 0 && (
                <tr><td colSpan={9} className="muted">Kayıt yok. <Link to="/new">Yeni inceleme başlatın</Link> veya örnek projelerden birini deneyin.</td></tr>
              )}
              {shown.map(({ review: r, threatLevels, pendingDecisions }) => (
                <tr key={r.id}>
                  <td className="muted"><Link to={`/reviews/${r.id}`}>{r.code}</Link></td>
                  <td style={{ fontWeight: 600 }}><Link to={`/reviews/${r.id}`} style={{ color: 'inherit', textDecoration: 'none' }}>{r.title}</Link></td>
                  <td>{r.businessUnit}</td>
                  <td><span className={STATUS_CLASS[r.status]}>{STATUS_LABEL[r.status]}</span></td>
                  <td>
                    <div className="row" style={{ gap: 4 }}>
                      {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].filter((l) => threatLevels[l]).map((l) => (
                        <span key={l} className={levelClass(l)}>{threatLevels[l]} {LEVEL_LABEL[l].toLowerCase()}</span>
                      ))}
                      {Object.keys(threatLevels).length === 0 && <span className="muted">–</span>}
                    </div>
                  </td>
                  <td>{pendingDecisions || '–'}</td>
                  <td><span className="pill gray">{r.llmMode === 'BROWSER' ? 'Tarayıcı' : 'Sunucu'}</span></td>
                  <td>{r.analyst}</td>
                  <td className="muted">{fmtDate(r.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
