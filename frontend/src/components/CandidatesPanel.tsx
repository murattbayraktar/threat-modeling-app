import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { KnowledgeChunk, ThreatCandidate } from '../api/types';
import { SOURCE_LABEL, STRIDE_LABEL } from '../lib/labels';

/** Kural motorunun ürettiği adaylar ve her aday için RAG ile getirilen kaynaklar (LLM'e giden bağlam). */
export default function CandidatesPanel({ reviewId, version }: { reviewId: string; version: number }) {
  const [candidates, setCandidates] = useState<ThreatCandidate[]>([]);
  const [retrieval, setRetrieval] = useState<{ mode?: string; perCandidate?: Record<string, { id: string; score: number; lexical: number; vector: number }[]>; chunks?: Record<string, KnowledgeChunk> }>({});
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    api.candidates(reviewId).then(setCandidates);
    api.retrieval(reviewId).then((r) => setRetrieval(r as typeof retrieval));
  }, [reviewId, version]);

  if (candidates.length === 0) {
    return <div className="card muted">Adaylar, model onaylandıktan sonra kural motoru tarafından üretilir.</div>;
  }
  const sel = candidates.find((c) => c.id === selected) ?? candidates[0];
  const hits = retrieval.perCandidate?.[sel.id] ?? [];
  return (
    <div className="split">
      <div className="grow card flush">
        <div className="card-head">
          <h2 style={{ margin: 0 }}>Kural tabanlı aday tehditler ({candidates.length})</h2>
          <span className="muted small">Arama: {retrieval.mode ?? '–'}</span>
        </div>
        <div className="scroll-x">
          <table className="tbl">
            <thead><tr><th>Kural</th><th>STRIDE</th><th>Hedef</th><th>Aday başlık</th><th>Kaynak</th></tr></thead>
            <tbody>
              {candidates.map((c) => (
                <tr key={c.id} className={'clickable' + (c.id === sel.id ? ' selected' : '')} onClick={() => setSelected(c.id)}>
                  <td className="mono">{c.ruleId}</td>
                  <td>{STRIDE_LABEL[c.stride]}</td>
                  <td>{c.targetName}</td>
                  <td>{c.title}</td>
                  <td>{retrieval.perCandidate?.[c.id]?.length ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <aside className="side-panel card stack">
        <h2>{sel.title}</h2>
        <p className="small" style={{ margin: 0 }}>{sel.description}</p>
        <div className="small muted">Kural {sel.ruleId} · varsayılan olasılık {sel.defaultLikelihood}, etki {sel.defaultImpact}</div>
        <div className="small"><strong>RAG sorgusu:</strong> {sel.ragQuery}</div>
        <h3>Getirilen kaynaklar</h3>
        {hits.length === 0 && <span className="muted small">Kaynak yok.</span>}
        {hits.map((h) => {
          const k = retrieval.chunks?.[h.id];
          return (
            <div key={h.id} className="stack" style={{ gap: 2, borderTop: '1px solid var(--line-2)', paddingTop: 8 }}>
              <div className="row between"><strong className="small">{h.id}</strong><span className="pill gray">skor {h.score}</span></div>
              <span className="small">{k?.title}</span>
              <span className="small muted">{SOURCE_LABEL[k?.source ?? ''] ?? k?.source}</span>
              {k?.mitigation && <span className="small">Önlem: {k.mitigation}</span>}
            </div>
          );
        })}
      </aside>
    </div>
  );
}
