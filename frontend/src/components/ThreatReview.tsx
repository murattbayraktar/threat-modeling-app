import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import type { Decision, Mitigation, ReviewDetail, Stride, SystemModel, ThreatView } from '../api/types';
import { DECISION_LABEL, LEVEL_LABEL, SOURCE_LABEL, STRIDE_LABEL, STRIDE_SHORT, decisionClass, levelClass } from '../lib/labels';

/** Uzman incelemesi: tehditleri kabul/red/yanlış pozitif olarak işaretleme, risk ve önlem düzenleme. */
export default function ThreatReview({ detail, version, onChanged }: { detail: ReviewDetail; version: number; onChanged: () => void }) {
  const reviewId = detail.review.id;
  const editable = detail.steps.some((s) => s.stage === 'EXPERT_REVIEW' && s.status === 'WAITING_USER');
  const [threats, setThreats] = useState<ThreatView[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [filters, setFilters] = useState({ level: '', stride: '', decision: '', q: '' });
  const [model, setModel] = useState<SystemModel | null>(null);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () => api.threats(reviewId).then(setThreats);
  useEffect(() => {
    load();
    api.model(reviewId).then((m) => setModel(m.current?.model ?? null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reviewId, version]);

  const shown = useMemo(() => threats
    .filter((t) => !filters.level || t.level === filters.level)
    .filter((t) => !filters.stride || t.stride === filters.stride)
    .filter((t) => !filters.decision || t.decision === filters.decision)
    .filter((t) => !filters.q || `${t.title} ${t.targetName} ${t.code}`.toLowerCase().includes(filters.q.toLowerCase()))
    .sort((a, b) => (b.score ?? 0) - (a.score ?? 0)), [threats, filters]);

  if (threats.length === 0 && !editable) {
    return <div className="card muted">Tehditler, model onaylandıktan sonra kural motoru + RAG + LLM ile üretilir.</div>;
  }
  const sel = threats.find((t) => t.id === selected) ?? shown[0];
  const decided = threats.filter((t) => t.decision !== 'PENDING').length;
  const pct = threats.length ? Math.round((decided / threats.length) * 100) : 0;
  const byLevel = (l: string) => threats.filter((t) => t.level === l && t.decision !== 'REJECTED' && t.decision !== 'FALSE_POSITIVE').length;

  async function update(t: ThreatView, u: Parameters<typeof api.updateThreat>[2]) {
    setError(null);
    try {
      const updated = await api.updateThreat(reviewId, t.id, u);
      setThreats((prev) => prev.map((x) => (x.id === t.id ? updated : x)));
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function complete() {
    setError(null);
    try {
      await api.completeReview(reviewId);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function bulkAccept() {
    const pending = shown.filter((t) => t.decision === 'PENDING');
    if (!confirm(`${pending.length} tehdit kabul edilecek. Devam edilsin mi?`)) return;
    for (const t of pending) await update(t, { decision: 'ACCEPTED' });
  }

  return (
    <div className="stack">
      <div className="grid">
        <div className="card stack"><span className="muted small">Risk dağılımı</span>
          <div className="row" style={{ gap: 4 }}>{['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((l) => <span key={l} className={levelClass(l)}>{byLevel(l)} {LEVEL_LABEL[l].toLowerCase()}</span>)}</div>
        </div>
        <div className="card stack"><span className="muted small">STRIDE dağılımı</span>
          <div className="row" style={{ gap: 4 }}>{Object.keys(STRIDE_SHORT).map((s) => <span key={s} className="pill gray">{STRIDE_SHORT[s]} {threats.filter((t) => t.stride === s).length}</span>)}</div>
        </div>
        <div className="card stack"><span className="muted small">Analist kararı</span>
          <strong style={{ fontSize: 20 }}>{decided} / {threats.length}</strong>
          <div className="meter"><div style={{ width: `${pct}%` }} /></div>
        </div>
      </div>
      {error && <div className="notice error">{error}</div>}
      {editable && (
        <div className="row no-print">
          <button className="btn success" onClick={complete}>İncelemeyi tamamla ve rapor üret</button>
          <button className="btn" onClick={bulkAccept}>Filtredeki bekleyenleri kabul et</button>
          <button className="btn" onClick={() => setAdding(!adding)}>+ Manuel tehdit</button>
        </div>
      )}
      {adding && model && <NewThreatForm model={model} onCancel={() => setAdding(false)} onSave={async (n) => {
        try {
          await api.addThreat(reviewId, n);
          setAdding(false);
          load();
          onChanged();
        } catch (e) { setError((e as Error).message); }
      }} />}
      <div className="split">
        <section className="grow card flush">
          <div className="card-head">
            <div className="row">
              <select aria-label="Risk filtresi" value={filters.level} onChange={(e) => setFilters({ ...filters, level: e.target.value })}>
                <option value="">Tüm riskler</option>{Object.keys(LEVEL_LABEL).map((l) => <option key={l} value={l}>{LEVEL_LABEL[l]}</option>)}
              </select>
              <select aria-label="STRIDE filtresi" value={filters.stride} onChange={(e) => setFilters({ ...filters, stride: e.target.value })}>
                <option value="">Tüm STRIDE</option>{Object.keys(STRIDE_LABEL).map((s) => <option key={s} value={s}>{STRIDE_LABEL[s]}</option>)}
              </select>
              <select aria-label="Karar filtresi" value={filters.decision} onChange={(e) => setFilters({ ...filters, decision: e.target.value })}>
                <option value="">Tüm kararlar</option>{Object.keys(DECISION_LABEL).map((d) => <option key={d} value={d}>{DECISION_LABEL[d]}</option>)}
              </select>
              <input type="search" placeholder="Ara" value={filters.q} onChange={(e) => setFilters({ ...filters, q: e.target.value })} />
            </div>
          </div>
          <div className="scroll-x">
            <table className="tbl">
              <thead><tr><th>ID</th><th>Tehdit</th><th>Hedef</th><th>STRIDE</th><th>Risk</th><th>Skor</th><th>Karar</th></tr></thead>
              <tbody>
                {shown.map((t) => (
                  <tr key={t.id} className={'clickable' + (sel?.id === t.id ? ' selected' : '')} onClick={() => setSelected(t.id)}>
                    <td className="muted">{t.code}</td>
                    <td style={{ minWidth: 260 }}>
                      <strong>{t.title}</strong>
                      <div className="row" style={{ gap: 4, marginTop: 2 }}>
                        {t.aiAdditional && <span className="pill purple">AI ek önerisi</span>}
                        {!t.ai.applicable && <span className="pill gray">AI: uygulanmaz</span>}
                        {t.ai.fromRuleTemplate && <span className="pill gray">kural şablonu</span>}
                        {t.ruleId === 'MANUAL' && <span className="pill blue">manuel</span>}
                      </div>
                    </td>
                    <td>{t.targetName}</td>
                    <td><span className="pill gray">{STRIDE_SHORT[t.stride]}</span></td>
                    <td><span className={levelClass(t.level)}>{LEVEL_LABEL[t.level ?? ''] ?? '–'}</span></td>
                    <td>{t.score ?? '–'}{t.expert.score != null && <span className="small muted"> (uzman)</span>}</td>
                    <td><span className={decisionClass(t.decision)}>{DECISION_LABEL[t.decision]}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
        {sel && <ThreatDetail key={sel.id} threat={sel} editable={editable} onUpdate={(u) => update(sel, u)} />}
      </div>
    </div>
  );
}

function ThreatDetail({ threat: t, editable, onUpdate }: { threat: ThreatView; editable: boolean; onUpdate: (u: Parameters<typeof api.updateThreat>[2]) => void }) {
  const [title, setTitle] = useState(t.title);
  const [description, setDescription] = useState(t.description);
  const [l, setL] = useState<number | ''>(t.expert.likelihood ?? '');
  const [i, setI] = useState<number | ''>(t.expert.impact ?? '');
  const [rationale, setRationale] = useState(t.expert.rationale ?? '');
  const [note, setNote] = useState(t.expert.note ?? '');
  const [mitigations, setMitigations] = useState<Mitigation[]>(t.mitigations);
  const textChanged = title !== t.title || description !== t.description;
  const riskChanged = (l !== '' && l !== t.expert.likelihood) || (i !== '' && i !== t.expert.impact);
  const mitChanged = JSON.stringify(mitigations) !== JSON.stringify(t.mitigations);

  function decide(decision: Decision) {
    onUpdate({
      decision,
      title: textChanged ? title : undefined,
      description: textChanged ? description : undefined,
      likelihood: riskChanged && l !== '' ? l : undefined,
      impact: riskChanged && i !== '' ? i : undefined,
      rationale: riskChanged ? rationale : undefined,
      mitigations: mitChanged ? mitigations : undefined,
      note: note || undefined,
    });
  }

  const sys = t.system;
  return (
    <aside className="side-panel card stack" aria-label="Tehdit detayı">
      <div className="row" style={{ gap: 6 }}>
        <span className="pill gray">{t.code}</span>
        <span className="pill gray">{STRIDE_LABEL[t.stride]}</span>
        <span className={levelClass(t.level)}>{LEVEL_LABEL[t.level ?? ''] ?? '–'}</span>
        <span className={decisionClass(t.decision)}>{DECISION_LABEL[t.decision]}</span>
      </div>
      {editable ? <input value={title} onChange={(e) => setTitle(e.target.value)} style={{ fontWeight: 600 }} aria-label="Başlık" /> : <h2 style={{ margin: 0 }}>{t.title}</h2>}
      <div className="small muted">Hedef: {t.targetName} ({t.targetId}) {t.ruleId && `· Kural ${t.ruleId}`}</div>
      {editable ? <textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} aria-label="Açıklama" /> : <p style={{ margin: 0, fontSize: 14 }}>{t.description}</p>}

      {!t.ai.applicable && <div className="notice warn small">AI bu tehdidi uygulanmaz buldu: {t.ai.notApplicableReason}</div>}
      {t.ai.attackScenario && <div className="small"><strong>Saldırı senaryosu (AI):</strong> {t.ai.attackScenario}</div>}

      <div className="stack soft-box" style={{ gap: 6 }}>
        <strong className="small">Üç ayrı değer</strong>
        <table className="tbl">
          <thead><tr><th /><th>Olasılık</th><th>Etki</th><th>Skor</th></tr></thead>
          <tbody>
            <tr><td className="small">AI önerisi</td><td>{t.ai.suggestedLikelihood ?? '–'}</td><td>{t.ai.suggestedImpact ?? '–'}</td><td className="muted small">hesaba girmez</td></tr>
            <tr><td className="small">Sistem (metodoloji)</td><td>{sys?.likelihood ?? '–'}</td><td>{sys?.impact ?? '–'}</td><td>{sys?.score ?? '–'} {sys && <span className={levelClass(sys.level)}>{LEVEL_LABEL[sys.level]}</span>}</td></tr>
            <tr><td className="small">Uzman</td><td>{t.expert.likelihood ?? '–'}</td><td>{t.expert.impact ?? '–'}</td><td>{t.expert.score ?? '–'}</td></tr>
          </tbody>
        </table>
        {t.ai.riskRationale && <span className="small muted">AI gerekçesi: {t.ai.riskRationale}</span>}
        {sys && (
          <details>
            <summary className="small">Risk hesabının izi ({sys.trace.length} ayarlama · başlangıç {sys.baselineLikelihood}×{sys.baselineImpact}, {sys.baselineSource === 'THREAT_RULE' ? 'kural varsayılanı' : 'STRIDE varsayılanı'})</summary>
            {sys.trace.map((s, idx) => (
              <div key={idx} className="small" style={{ marginTop: 4 }}>
                <span className="mono">{s.ruleId}</span> {s.field === 'likelihood' ? 'olasılık' : 'etki'} {s.before} → {s.after} ({s.op} {s.value}): {s.description}
              </div>
            ))}
            <div className="small muted" style={{ marginTop: 4 }}>{sys.methodologyVersion} · {sys.riskRulesVersion}</div>
          </details>
        )}
      </div>

      {editable && (
        <div className="stack" style={{ gap: 6 }}>
          <strong className="small">Uzman risk değerlendirmesi (isteğe bağlı)</strong>
          <div className="row">
            <label className="field">Olasılık<select value={l} onChange={(e) => setL(e.target.value ? Number(e.target.value) : '')}><option value="">Sistem</option>{[1, 2, 3, 4, 5].map((v) => <option key={v} value={v}>{v}</option>)}</select></label>
            <label className="field">Etki<select value={i} onChange={(e) => setI(e.target.value ? Number(e.target.value) : '')}><option value="">Sistem</option>{[1, 2, 3, 4, 5].map((v) => <option key={v} value={v}>{v}</option>)}</select></label>
          </div>
          {riskChanged && <textarea rows={2} placeholder="Değişiklik gerekçesi (zorunlu)" value={rationale} onChange={(e) => setRationale(e.target.value)} />}
          {(t.expert.likelihood != null || t.expert.impact != null) && <button className="btn small link" onClick={() => onUpdate({ resetRisk: true })}>Sistem değerine dön</button>}
        </div>
      )}

      <div className="stack" style={{ gap: 6 }}>
        <strong className="small">Önerilen kontroller</strong>
        {mitigations.length === 0 && <span className="small muted">Önlem önerisi yok.</span>}
        {mitigations.map((m, idx) => (
          <div key={idx} className="stack" style={{ gap: 4, borderTop: '1px solid var(--line-2)', paddingTop: 6 }}>
            {editable ? (
              <>
                <input value={m.title} onChange={(e) => setMitigations(mitigations.map((x, j) => (j === idx ? { ...x, title: e.target.value, origin: 'EXPERT' } : x)))} />
                <textarea rows={2} value={m.description} onChange={(e) => setMitigations(mitigations.map((x, j) => (j === idx ? { ...x, description: e.target.value, origin: 'EXPERT' } : x)))} />
              </>
            ) : (
              <span className="small"><strong>{m.title}</strong> – {m.description}</span>
            )}
            <div className="row small" style={{ gap: 4 }}>
              {m.sourceIds.map((s) => <span key={s} className="pill blue">{s}</span>)}
              <span className="muted">{m.origin === 'AI' ? 'AI önerisi' : m.origin === 'EXPERT' ? 'uzman' : m.origin}</span>
              {editable && <button className="btn small link" onClick={() => setMitigations(mitigations.filter((_, j) => j !== idx))}>Kaldır</button>}
            </div>
          </div>
        ))}
        {editable && <button className="btn small" onClick={() => setMitigations([...mitigations, { title: '', description: '', sourceIds: [], origin: 'EXPERT' }])}>+ Kontrol ekle</button>}
      </div>

      {t.sources.length > 0 && (
        <details>
          <summary className="small">Kaynaklar ({t.sources.length})</summary>
          {t.sources.map((s) => (
            <div key={s.id} className="small" style={{ marginTop: 4 }}>
              <span className="mono">{s.id}</span> · {SOURCE_LABEL[s.source] ?? s.source} · {s.title}{' '}
              {s.reference?.startsWith('http') && <a href={s.reference} target="_blank" rel="noreferrer">bağlantı</a>}
            </div>
          ))}
        </details>
      )}
      {t.issues.length > 0 && (
        <details>
          <summary className="small">Doğrulama notları ({t.issues.length})</summary>
          {t.issues.map((iss, idx) => <div key={idx} className="small" style={{ marginTop: 4 }}><span className="mono">{iss.code}</span> – {iss.message}</div>)}
        </details>
      )}

      {editable && (
        <>
          <textarea rows={2} placeholder="Analist notu" value={note} onChange={(e) => setNote(e.target.value)} />
          <div className="row">
            <button className="btn success" onClick={() => decide('ACCEPTED')}>{textChanged || riskChanged || mitChanged ? 'Düzenleyerek kabul et' : 'Kabul et'}</button>
            <button className="btn" onClick={() => decide('REJECTED')}>Reddet</button>
            <button className="btn" onClick={() => decide('FALSE_POSITIVE')}>Yanlış pozitif</button>
            {t.decision !== 'PENDING' && <button className="btn small link" onClick={() => decide('PENDING')}>Kararı geri al</button>}
          </div>
        </>
      )}
      {t.expert.decidedBy && <div className="small muted">Son karar: {t.expert.decidedBy} · {t.expert.decidedAt && new Date(t.expert.decidedAt).toLocaleString('tr-TR')}</div>}
    </aside>
  );
}

function NewThreatForm({ model, onSave, onCancel }: { model: SystemModel; onSave: (t: Parameters<typeof api.addThreat>[1]) => void; onCancel: () => void }) {
  const targets = [
    ...model.components.map((c) => ({ id: c.id, name: c.name })),
    ...model.dataFlows.map((f) => ({ id: f.id, name: `${model.components.find((c) => c.id === f.source)?.name} → ${model.components.find((c) => c.id === f.target)?.name}` })),
  ];
  const [stride, setStride] = useState<Stride>('SPOOFING');
  const [targetId, setTargetId] = useState(targets[0]?.id ?? '');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [mitigation, setMitigation] = useState('');
  return (
    <section className="card stack">
      <h2 style={{ margin: 0 }}>Manuel tehdit</h2>
      <div className="row">
        <label className="field">STRIDE<select value={stride} onChange={(e) => setStride(e.target.value as Stride)}>{Object.keys(STRIDE_LABEL).map((s) => <option key={s} value={s}>{STRIDE_LABEL[s]}</option>)}</select></label>
        <label className="field">Hedef<select value={targetId} onChange={(e) => setTargetId(e.target.value)}>{targets.map((t) => <option key={t.id} value={t.id}>{t.id} · {t.name}</option>)}</select></label>
      </div>
      <label className="field">Başlık<input value={title} onChange={(e) => setTitle(e.target.value)} /></label>
      <label className="field">Açıklama<textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
      <label className="field">Önlem<input value={mitigation} onChange={(e) => setMitigation(e.target.value)} /></label>
      <div className="row">
        <button className="btn primary" onClick={() => onSave({ stride, targetId, title, description, mitigations: mitigation ? [{ title: mitigation, description: '', sourceIds: [], origin: 'EXPERT' }] : [] })}>Ekle</button>
        <button className="btn" onClick={onCancel}>Vazgeç</button>
      </div>
      <span className="small muted">Risk skoru metodolojiye göre sistem tarafından hesaplanır; isterseniz eklendikten sonra uzman değeri girebilirsiniz.</span>
    </section>
  );
}
