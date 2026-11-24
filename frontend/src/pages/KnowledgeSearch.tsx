import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { RetrievedChunk } from '../api/types';
import { SOURCE_LABEL } from '../lib/labels';

/** Bilgi tabanında arama: RAG'in getirme katmanı LLM'e gitmeden doğrudan kullanılabilir. */
export default function KnowledgeSearch() {
  const [info, setInfo] = useState<{ mode: string; counts: Record<string, number>; total: number; warnings: string[] } | null>(null);
  const [q, setQ] = useState('servisler arası kimlik doğrulama');
  const [sources, setSources] = useState<string[]>([]);
  const [tags, setTags] = useState('');
  const [results, setResults] = useState<RetrievedChunk[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.knowledgeSources().then(setInfo);
  }, []);

  async function search() {
    setError(null);
    try {
      setResults(await api.knowledgeSearch(q, sources, tags.split(',').map((t) => t.trim()).filter(Boolean), 10));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <div className="stack" style={{ gap: 4 }}>
        <h1>Bilgi tabanı (RAG)</h1>
        <span className="muted">OWASP, STRIDE, MITRE ATT&CK, banka kontrol kataloğu ve geçmiş incelemelerde arama. Kaynaklar datasets/knowledge-base altındadır.</span>
      </div>
      {info && (
        <div className="card row between">
          <div className="row">
            {Object.entries(info.counts).map(([s, n]) => <span key={s} className="pill gray">{SOURCE_LABEL[s] ?? s}: {n}</span>)}
          </div>
          <div className="row">
            <span className="small muted">Arama modu: {info.mode} · {info.total} kayıt</span>
            <button className="btn small" onClick={() => api.reloadKnowledge().then(() => api.knowledgeSources().then(setInfo))}>Yeniden yükle</button>
          </div>
        </div>
      )}
      <section className="card stack">
        <div className="row">
          <input style={{ flex: 1, minWidth: 260 }} value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && search()} aria-label="Sorgu" />
          <input style={{ width: 240 }} placeholder="Etiket önceliği (ör. SPOOFING, API)" value={tags} onChange={(e) => setTags(e.target.value)} />
          <button className="btn primary" onClick={search}>Ara</button>
        </div>
        <div className="row small">
          {Object.keys(SOURCE_LABEL).map((s) => (
            <label key={s} className="row" style={{ gap: 4 }}>
              <input type="checkbox" checked={sources.includes(s)} onChange={(e) => setSources(e.target.checked ? [...sources, s] : sources.filter((x) => x !== s))} />
              {SOURCE_LABEL[s]}
            </label>
          ))}
        </div>
      </section>
      {error && <div className="notice error">{error}</div>}
      {results.map((r) => (
        <div key={r.chunk.id} className="card stack" style={{ gap: 6 }}>
          <div className="row between">
            <div className="row"><span className="mono">{r.chunk.id}</span><strong>{r.chunk.title}</strong></div>
            <span className="small muted">skor {r.score} (BM25 {r.lexicalScore}{r.vectorScore ? `, vektör ${r.vectorScore}` : ''})</span>
          </div>
          <span className="small">{r.chunk.text}</span>
          {r.chunk.mitigation && <span className="small"><strong>Önlem:</strong> {r.chunk.mitigation}</span>}
          <div className="row small" style={{ gap: 4 }}>
            <span className="pill blue">{SOURCE_LABEL[r.chunk.source] ?? r.chunk.source}</span>
            {r.chunk.tags.map((t) => <span key={t} className="pill gray">{t}</span>)}
            {r.chunk.reference?.startsWith('http') && <a href={r.chunk.reference} target="_blank" rel="noreferrer">kaynak</a>}
          </div>
        </div>
      ))}
    </>
  );
}
