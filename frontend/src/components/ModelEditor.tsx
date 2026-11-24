import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import type { Component, DataFlow, ModelView, SystemModel, ValidationIssue } from '../api/types';
import { AUTH_LABEL, COMPONENT_TYPE_LABEL, DATA_CLASS_LABEL, ZONE_LABEL, fmtDate } from '../lib/labels';
import { useMeta } from '../lib/useMeta';
import ArchitectureDiagram from './ArchitectureDiagram';

/*
 * Mimari model doğrulama ekranı (diyagram merkezli):
 *  - Üstte diyagram: bileşenler, güven bölgeleri, veri akışları.
 *  - Altta solda kısa öğe listesi (Bileşenler / Veri akışları / Varsayımlar ve sürümler / JSON sekmeleri).
 *  - Sağda denetçi paneli: öğe seçilince düzenleme formu; seçim yoksa dikkat gerektiren noktaların listesi.
 * Diyagram, liste ve panel aynı seçimi paylaşır; birinde seçilen öğe ötekilerde de vurgulanır.
 */

type ListTab = 'components' | 'flows' | 'notes' | 'json';

interface Attention {
  key: string;
  elementId: string | null;
  kind: 'ERROR' | 'WARNING' | 'QUESTION' | 'LOW';
  text: string;
}

function triState(v: boolean | null): string {
  return v === null || v === undefined ? '' : v ? 'true' : 'false';
}
function fromTri(s: string): boolean | null {
  return s === '' ? null : s === 'true';
}
const yesNo = (v: boolean | null) => (v === null || v === undefined ? 'Bilinmiyor' : v ? 'Evet' : 'Hayır');

export default function ModelEditor({ reviewId, version, onChanged }: { reviewId: string; version: number; onChanged: () => void }) {
  const meta = useMeta();
  const [view, setView] = useState<ModelView | null>(null);
  const [model, setModel] = useState<SystemModel | null>(null);
  const [dirty, setDirty] = useState(false);
  const [json, setJson] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [tab, setTab] = useState<ListTab>('components');
  const inspectorRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api.model(reviewId).then((v) => {
      setView(v);
      if (!dirty) setModel(v.current ? structuredClone(v.current.model) : null);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reviewId, version]);

  const attention = useMemo<Attention[]>(() => {
    if (!model || !view?.current) return [];
    const out: Attention[] = [];
    view.current.issues.forEach((i, idx) =>
      out.push({ key: 'i' + idx, elementId: i.elementId, kind: i.severity === 'ERROR' ? 'ERROR' : i.severity === 'WARNING' ? 'WARNING' : 'LOW', text: `${i.code}: ${i.message}` }));
    model.missingInformation.forEach((m, idx) => out.push({ key: 'm' + idx, elementId: m.relatedElementId, kind: 'QUESTION', text: m.question }));
    model.components.filter((c) => c.confidence === 'LOW').forEach((c) => out.push({ key: 'lc' + c.id, elementId: c.id, kind: 'LOW', text: `${c.name}: düşük güvenle çıkarıldı, kanıtı kontrol edin.` }));
    model.dataFlows.filter((f) => f.authentication === 'UNKNOWN').forEach((f) => out.push({ key: 'ua' + f.id, elementId: f.id, kind: 'WARNING', text: `${f.id}: kimlik doğrulama yöntemi bilinmiyor.` }));
    return out;
  }, [model, view]);

  const flagged = useMemo(() => new Set(attention.map((a) => a.elementId).filter((x): x is string => !!x)), [attention]);

  if (!view) return <div className="muted">Yükleniyor…</div>;
  if (!view.current || !model) {
    return <div className="card muted">Model henüz çıkarılmadı. Akış ilerledikçe bu ekran dolacak.</div>;
  }
  const editable = view.editable;
  const current = view.current;
  const names: Record<string, string> = Object.fromEntries(model.components.map((c) => [c.id, c.name]));
  const zoneOf: Record<string, string> = Object.fromEntries(model.components.map((c) => [c.id, c.zone]));
  const crosses = (f: DataFlow) => f.crossesTrustBoundary ?? zoneOf[f.source] !== zoneOf[f.target];

  function select(id: string | null, scroll = false) {
    setSelected(id);
    if (id && scroll) setTimeout(() => inspectorRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 30);
    if (id && model!.components.some((c) => c.id === id)) setTab('components');
    else if (id && model!.dataFlows.some((f) => f.id === id)) setTab('flows');
  }
  function patch(m: SystemModel) {
    setModel(m);
    setDirty(true);
  }
  function setComp(id: string, p: Partial<Component>) {
    patch({ ...model!, components: model!.components.map((c) => (c.id === id ? { ...c, ...p } : c)) });
  }
  function setFlow(id: string, p: Partial<DataFlow>) {
    patch({ ...model!, dataFlows: model!.dataFlows.map((f) => (f.id === id ? { ...f, ...p } : f)) });
  }
  function nextId(prefix: string, ids: string[]) {
    let n = ids.length + 1;
    while (ids.includes(prefix + n)) n++;
    return prefix + n;
  }
  function addComponent() {
    const c: Component = {
      id: nextId('C', model!.components.map((x) => x.id)), name: 'Yeni bileşen', type: 'SERVICE', technology: null, zone: 'INTERNAL',
      internetFacing: null, authentication: 'UNKNOWN', dataClasses: [], description: '', evidence: null, confidence: 'HIGH',
    };
    patch({ ...model!, components: [...model!.components, c] });
    select(c.id, true);
  }
  function addFlow(from?: string) {
    const ids = model!.components.map((c) => c.id);
    if (ids.length < 2) return;
    const source = from ?? ids[0];
    const f: DataFlow = {
      id: nextId('F', model!.dataFlows.map((x) => x.id)), source, target: ids.find((i) => i !== source)!, description: '', protocol: null,
      authentication: 'UNKNOWN', encrypted: null, dataClasses: [], evidence: null, confidence: 'HIGH',
    };
    patch({ ...model!, dataFlows: [...model!.dataFlows, f] });
    select(f.id, true);
  }
  function remove(id: string) {
    if (model!.components.some((c) => c.id === id)) {
      const linked = model!.dataFlows.filter((f) => f.source === id || f.target === id).length;
      if (linked && !confirm(`Bu bileşene bağlı ${linked} akış da silinecek. Devam edilsin mi?`)) return;
      patch({ ...model!, components: model!.components.filter((c) => c.id !== id), dataFlows: model!.dataFlows.filter((f) => f.source !== id && f.target !== id) });
    } else {
      patch({ ...model!, dataFlows: model!.dataFlows.filter((f) => f.id !== id) });
    }
    setSelected(null);
  }

  async function act(fn: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setDirty(false);
      setJson(null);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const save = () => act(async () => {
    const m = json !== null ? (JSON.parse(json) as SystemModel) : model!;
    await api.saveModel(reviewId, m);
  });
  const approve = () => act(async () => {
    if (dirty || json !== null) await api.saveModel(reviewId, json !== null ? JSON.parse(json) : model!);
    await api.approveModel(reviewId);
  });
  const reject = () => act(() => api.rejectModel(reviewId, note));
  const back = () => {
    if (!confirm('Modele dönülürse mevcut tehditler ve uzman kararları silinir. Devam edilsin mi?')) return;
    act(() => api.backToModel(reviewId));
  };

  const selComp = selected ? model.components.find((c) => c.id === selected) : undefined;
  const selFlow = selected ? model.dataFlows.find((f) => f.id === selected) : undefined;
  const issuesFor = (id: string) => current.issues.filter((i) => i.elementId === id);
  const counts = {
    errors: current.issues.filter((i) => i.severity === 'ERROR').length,
    warnings: attention.filter((a) => a.kind === 'WARNING').length,
    questions: model.missingInformation.length,
    low: model.components.filter((c) => c.confidence === 'LOW').length,
    crossing: model.dataFlows.filter(crosses).length,
  };

  return (
    <div className="stack">
      {/* Başlık ve özet */}
      <div className="card stack" style={{ gap: 14 }}>
        <div className="row between">
          <div className="stack" style={{ gap: 4 }}>
            <h2 style={{ margin: 0 }}>
              Mimari model v{current.version}{' '}
              {current.approvedAt ? <span className="pill green">Onaylandı</span> : <span className="pill orange">Onay bekliyor</span>}
              {dirty && <span className="pill gray" style={{ marginLeft: 6 }}>Kaydedilmemiş değişiklik</span>}
            </h2>
            <span className="muted small">
              {current.source === 'AI' ? 'LLM çıkarımı + doğrulama' : 'Analist düzenlemesi'} · {current.createdBy} · {fmtDate(current.createdAt)}
              {current.approvedBy ? ` · Onaylayan: ${current.approvedBy}` : ''}
            </span>
          </div>
          <div className="row">
            {editable && (
              <>
                <button className="btn" disabled={busy || (!dirty && json === null)} onClick={save}>Kaydet (yeni sürüm)</button>
                <button className="btn primary" disabled={busy} onClick={approve}>Modeli onayla ve tehditleri üret</button>
              </>
            )}
            {!editable && current.approvedAt && <button className="btn" disabled={busy} onClick={back}>Modele dön ve düzenle</button>}
          </div>
        </div>
        <div className="kpis">
          <Kpi label="Bileşen" value={model.components.length} />
          <Kpi label="Veri akışı" value={model.dataFlows.length} />
          <Kpi label="Sınırı geçen akış" value={counts.crossing} tone={counts.crossing ? 'brand' : undefined} />
          <Kpi label="Eksik bilgi" value={counts.questions} tone={counts.questions ? 'warn' : undefined} onClick={() => setSelected(null)} />
          <Kpi label="Uyarı / hata" value={counts.warnings + counts.errors} tone={counts.errors ? 'error' : counts.warnings ? 'warn' : undefined} onClick={() => setSelected(null)} />
          <Kpi label="Düşük güven" value={counts.low} tone={counts.low ? 'warn' : undefined} onClick={() => setSelected(null)} />
        </div>
      </div>
      {error && <div className="notice error">{error}</div>}

      {/* Diyagram */}
      <section className="card stack">
        <div className="row between">
          <div className="stack" style={{ gap: 2 }}>
            <h2 style={{ margin: 0 }}>Mimari diyagram</h2>
            <span className="small muted">Bir öğeye tıklayın; ayrıntısı ve düzenleme formu aşağıdaki panelde açılır. Turuncu “!” dikkat gerektiren öğeleri gösterir.</span>
          </div>
          {editable && (
            <div className="row" style={{ gap: 6 }}>
              <button className="btn small" onClick={addComponent}>+ Bileşen</button>
              <button className="btn small" onClick={() => addFlow(selComp?.id)}>+ Akış</button>
            </div>
          )}
        </div>
        <ArchitectureDiagram model={model} selected={selected} onSelect={(id) => select(id, true)} flagged={flagged} hideDetail />
      </section>

      <div className="split">
        {/* Öğe listesi */}
        <section className="grow card flush">
          <div className="tabs" role="tablist" style={{ padding: '0 12px' }}>
            {([
              ['components', `Bileşenler (${model.components.length})`],
              ['flows', `Veri akışları (${model.dataFlows.length})`],
              ['notes', `Varsayımlar ve sürümler`],
              ...(editable ? [['json', 'JSON']] : []),
            ] as [ListTab, string][]).map(([id, label]) => (
              <button key={id} role="tab" aria-selected={tab === id} className={'tab' + (tab === id ? ' active' : '')} onClick={() => setTab(id)}>{label}</button>
            ))}
          </div>

          {tab === 'components' && (
            <div className="list-scroll">
              <table className="tbl compact">
                <thead><tr><th>ID</th><th>Bileşen</th><th>Bölge</th><th>Kimlik doğrulama</th><th>Veri</th><th>Akış</th><th /></tr></thead>
                <tbody>
                  {model.components.map((c) => {
                    const n = model.dataFlows.filter((f) => f.source === c.id || f.target === c.id).length;
                    return (
                      <tr key={c.id} className={'clickable' + (selected === c.id ? ' selected' : '')} onClick={() => select(c.id, true)}>
                        <td className="mono">{c.id}</td>
                        <td><strong>{c.name}</strong><div className="small muted">{COMPONENT_TYPE_LABEL[c.type]}{c.technology ? ` · ${c.technology}` : ''}</div></td>
                        <td><ZoneTag zone={c.zone} internet={c.internetFacing} /></td>
                        <td className={c.authentication === 'UNKNOWN' ? 'warn-text' : undefined}>{AUTH_LABEL[c.authentication]}</td>
                        <td><DataTags value={c.dataClasses} /></td>
                        <td className="muted">{n}</td>
                        <td>{flagged.has(c.id) && <span className="flag" title="Dikkat gerektiriyor">!</span>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          {tab === 'flows' && (
            <div className="list-scroll">
              <table className="tbl compact">
                <thead><tr><th>ID</th><th>Kaynak → hedef</th><th>Protokol</th><th>Kimlik doğrulama</th><th>Şifreli</th><th>Veri</th><th /></tr></thead>
                <tbody>
                  {model.dataFlows.map((f) => (
                    <tr key={f.id} className={'clickable' + (selected === f.id ? ' selected' : '')} onClick={() => select(f.id, true)}>
                      <td className="mono">{f.id}</td>
                      <td>
                        <strong>{names[f.source] ?? f.source}</strong> <span className={crosses(f) ? 'arrow-cross' : 'muted'}>→</span> <strong>{names[f.target] ?? f.target}</strong>
                        {crosses(f) && <div className="small" style={{ color: 'var(--brand-dark)' }}>Güven sınırını geçiyor</div>}
                      </td>
                      <td>{f.protocol ?? <span className="muted">–</span>}</td>
                      <td className={f.authentication === 'UNKNOWN' ? 'warn-text' : undefined}>{AUTH_LABEL[f.authentication]}</td>
                      <td className={f.encrypted === false ? 'warn-text' : f.encrypted === null ? 'muted' : undefined}>{yesNo(f.encrypted)}</td>
                      <td><DataTags value={f.dataClasses} /></td>
                      <td>{flagged.has(f.id) && <span className="flag" title="Dikkat gerektiriyor">!</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {tab === 'notes' && (
            <div className="stack" style={{ padding: 18 }}>
              <div className="stack" style={{ gap: 6 }}>
                <h3 style={{ margin: 0 }}>Varsayımlar ({model.assumptions.length})</h3>
                {model.assumptions.length === 0 && <span className="small muted">Yok.</span>}
                {model.assumptions.map((a, i) => <div key={i} className="small">• {a}</div>)}
              </div>
              <div className="stack" style={{ gap: 6 }}>
                <h3 style={{ margin: 0 }}>Sürümler</h3>
                {view.versions.map((v) => (
                  <div key={v.version} className="small">v{v.version} · {v.source === 'AI' ? 'LLM' : 'Analist'} · {v.createdBy} · {fmtDate(v.createdAt)} {v.approvedAt && <span className="pill green">onaylı</span>}</div>
                ))}
              </div>
              {editable && (
                <div className="stack soft-box" style={{ gap: 8 }}>
                  <h3 style={{ margin: 0 }}>Yeniden çıkarım</h3>
                  <span className="small muted">Model kötü çıktıysa LLM'den notunuzu dikkate alarak tekrar çıkarmasını isteyin.</span>
                  <textarea rows={3} placeholder="Ör. Dosya aktarım sunucusu bu projenin parçası değil" value={note} onChange={(e) => setNote(e.target.value)} />
                  <div><button className="btn" disabled={busy} onClick={reject}>Yeniden çıkar</button></div>
                </div>
              )}
            </div>
          )}

          {tab === 'json' && editable && (
            <div className="stack" style={{ padding: 18 }}>
              <span className="small muted">contracts/system-model.schema.json yapısında. Kaydedildiğinde backend tekrar doğrular.</span>
              <textarea rows={18} className="mono" value={json ?? JSON.stringify(model, null, 2)} onChange={(e) => setJson(e.target.value)} />
            </div>
          )}
        </section>

        {/* Denetçi paneli */}
        <aside className="side-panel inspector" ref={inspectorRef}>
          {selComp && (
            <ComponentInspector c={selComp} model={model} editable={editable} issues={issuesFor(selComp.id)} meta={meta?.componentTypes}
              onChange={(p) => setComp(selComp.id, p)} onSelect={(id) => select(id)} onAddFlow={() => addFlow(selComp.id)}
              onDelete={() => remove(selComp.id)} onClose={() => setSelected(null)} />
          )}
          {selFlow && (
            <FlowInspector f={selFlow} model={model} editable={editable} issues={issuesFor(selFlow.id)} crosses={crosses(selFlow)}
              onChange={(p) => setFlow(selFlow.id, p)} onSelect={(id) => select(id)} onDelete={() => remove(selFlow.id)} onClose={() => setSelected(null)} />
          )}
          {!selComp && !selFlow && (
            <section className="card stack">
              <div className="row between">
                <h2 style={{ margin: 0 }}>Gözden geçirme listesi</h2>
                <span className="pill gray">{attention.length}</span>
              </div>
              <span className="small muted">Onaylamadan önce bakılması gereken noktalar. Satıra tıklayınca ilgili öğe seçilir.</span>
              {attention.length === 0 && <div className="notice ok">Dikkat gerektiren nokta yok.</div>}
              <div className="attention-list">
                {attention.map((a) => (
                  <button key={a.key} className={'att ' + a.kind} disabled={!a.elementId} onClick={() => a.elementId && select(a.elementId)}>
                    <span className="att-kind">{a.kind === 'ERROR' ? 'Hata' : a.kind === 'WARNING' ? 'Uyarı' : a.kind === 'QUESTION' ? 'Soru' : 'Kontrol'}</span>
                    <span className="att-text">{a.elementId && <span className="mono">{a.elementId} · </span>}{a.text}</span>
                  </button>
                ))}
              </div>
              {model.missingInformation.length > 0 && (
                <button className="btn small" onClick={() => navigator.clipboard.writeText(model.missingInformation.map((m) => '- ' + m.question).join('\n'))}>
                  Eksik bilgi sorularını kopyala (proje sahibine iletmek için)
                </button>
              )}
            </section>
          )}
        </aside>
      </div>
    </div>
  );
}

/* ----------------------------------------------------------------- yardımcı bileşenler */

function Kpi({ label, value, tone, onClick }: { label: string; value: number; tone?: 'brand' | 'warn' | 'error'; onClick?: () => void }) {
  return (
    <div className={'kpi' + (tone ? ' ' + tone : '') + (onClick ? ' clickable' : '')} onClick={onClick}>
      <span className="kpi-v">{value}</span>
      <span className="kpi-l">{label}</span>
    </div>
  );
}

function ZoneTag({ zone, internet }: { zone: string; internet: boolean | null }) {
  return (
    <span className="row" style={{ gap: 4 }}>
      <span className={'zone-tag ' + zone}>{ZONE_LABEL[zone] ?? zone}</span>
      {internet && zone !== 'INTERNET' && <span className="zone-tag net">internete açık</span>}
    </span>
  );
}

function DataTags({ value }: { value: string[] }) {
  if (!value.length) return <span className="muted small">–</span>;
  return (
    <span className="tags">
      {value.slice(0, 3).map((d) => <span key={d} className={'dtag ' + d}>{DATA_CLASS_LABEL[d]}</span>)}
      {value.length > 3 && <span className="dtag">+{value.length - 3}</span>}
    </span>
  );
}

function DataClassChips({ value, editable, onChange }: { value: string[]; editable: boolean; onChange: (v: string[]) => void }) {
  return (
    <div className="tags">
      {Object.keys(DATA_CLASS_LABEL).filter((d) => editable || value.includes(d)).map((d) => {
        const on = value.includes(d);
        return editable ? (
          <button key={d} type="button" className={'chip-toggle' + (on ? ' on' : '')} aria-pressed={on}
            onClick={() => onChange(on ? value.filter((x) => x !== d) : [...value, d])}>{DATA_CLASS_LABEL[d]}</button>
        ) : <span key={d} className={'dtag ' + d}>{DATA_CLASS_LABEL[d]}</span>;
      })}
      {!editable && value.length === 0 && <span className="muted small">Yok</span>}
    </div>
  );
}

function InspectorHead({ id, title, sub, onClose }: { id: string; title: string; sub: string; onClose: () => void }) {
  return (
    <div className="insp-head">
      <div className="stack" style={{ gap: 2, minWidth: 0 }}>
        <span className="mono muted">{id}</span>
        <strong className="insp-title">{title}</strong>
        <span className="small muted">{sub}</span>
      </div>
      <button className="btn small ghost" onClick={onClose} aria-label="Paneli kapat">✕</button>
    </div>
  );
}

function EvidenceBox({ e }: { e: { documentId: string; section: string; quote: string } | null }) {
  if (!e) return <div className="notice warn small">Dokümanda kanıt bulunamadı. Öğenin gerçekten projede olup olmadığını kontrol edin.</div>;
  return (
    <div className="evidence">
      <span className="k">Kanıt · {e.documentId}#{e.section}</span>
      <q>{e.quote}</q>
    </div>
  );
}

function IssueList({ issues }: { issues: ValidationIssue[] }) {
  if (!issues.length) return null;
  return (
    <div className="stack" style={{ gap: 4 }}>
      {issues.map((i, idx) => (
        <div key={idx} className={'notice small ' + (i.severity === 'ERROR' ? 'error' : 'warn')}><span className="mono">{i.code}</span> – {i.message}</div>
      ))}
    </div>
  );
}

function Fld({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="field">{label}{children}</label>;
}

function ComponentInspector({ c, model, editable, issues, meta, onChange, onSelect, onAddFlow, onDelete, onClose }: {
  c: Component; model: SystemModel; editable: boolean; issues: ValidationIssue[]; meta?: string[];
  onChange: (p: Partial<Component>) => void; onSelect: (id: string) => void; onAddFlow: () => void; onDelete: () => void; onClose: () => void;
}) {
  const names: Record<string, string> = Object.fromEntries(model.components.map((x) => [x.id, x.name]));
  const flows = model.dataFlows.filter((f) => f.source === c.id || f.target === c.id);
  return (
    <section className="card stack insp">
      <InspectorHead id={c.id} title={c.name} sub={`${COMPONENT_TYPE_LABEL[c.type]} · ${ZONE_LABEL[c.zone]}`} onClose={onClose} />
      <IssueList issues={issues} />
      {editable ? (
        <div className="form-grid">
          <div className="full"><Fld label="Ad"><input value={c.name} onChange={(e) => onChange({ name: e.target.value })} /></Fld></div>
          <Fld label="Tür">
            <select value={c.type} onChange={(e) => onChange({ type: e.target.value })}>
              {(meta ?? Object.keys(COMPONENT_TYPE_LABEL)).map((t) => <option key={t} value={t}>{COMPONENT_TYPE_LABEL[t]}</option>)}
            </select>
          </Fld>
          <Fld label="Güven bölgesi">
            <select value={c.zone} onChange={(e) => onChange({ zone: e.target.value })}>
              {Object.keys(ZONE_LABEL).map((z) => <option key={z} value={z}>{ZONE_LABEL[z]}</option>)}
            </select>
          </Fld>
          <Fld label="İnternete açık">
            <select value={triState(c.internetFacing)} onChange={(e) => onChange({ internetFacing: fromTri(e.target.value) })}>
              <option value="">Bilinmiyor</option><option value="true">Evet</option><option value="false">Hayır</option>
            </select>
          </Fld>
          <Fld label="Kimlik doğrulama">
            <select value={c.authentication} onChange={(e) => onChange({ authentication: e.target.value })}>
              {Object.keys(AUTH_LABEL).map((a) => <option key={a} value={a}>{AUTH_LABEL[a]}</option>)}
            </select>
          </Fld>
          <div className="full"><Fld label="Teknoloji"><input value={c.technology ?? ''} placeholder="Ör. Spring Boot, Oracle" onChange={(e) => onChange({ technology: e.target.value || null })} /></Fld></div>
          <div className="full"><span className="field-label">İşlenen veri</span><DataClassChips value={c.dataClasses} editable onChange={(v) => onChange({ dataClasses: v })} /></div>
          <div className="full"><Fld label="Açıklama"><textarea rows={2} value={c.description} onChange={(e) => onChange({ description: e.target.value })} /></Fld></div>
        </div>
      ) : (
        <dl className="kv-grid">
          <dt>İnternete açık</dt><dd>{yesNo(c.internetFacing)}</dd>
          <dt>Kimlik doğrulama</dt><dd>{AUTH_LABEL[c.authentication]}</dd>
          <dt>Teknoloji</dt><dd>{c.technology ?? '–'}</dd>
          <dt>İşlenen veri</dt><dd><DataClassChips value={c.dataClasses} editable={false} onChange={() => undefined} /></dd>
          {c.description && <><dt>Açıklama</dt><dd>{c.description}</dd></>}
        </dl>
      )}
      <EvidenceBox e={c.evidence} />
      <div className="stack" style={{ gap: 6 }}>
        <div className="row between"><span className="field-label">Bağlı akışlar ({flows.length})</span>{editable && <button className="btn small link" onClick={onAddFlow}>+ Akış ekle</button>}</div>
        {flows.length === 0 && <span className="small muted">Bu bileşene bağlı akış yok.</span>}
        {flows.map((f) => (
          <button key={f.id} className="link-row" onClick={() => onSelect(f.id)}>
            <span className="mono">{f.id}</span>
            <span>{f.source === c.id ? `→ ${names[f.target]}` : `← ${names[f.source]}`}</span>
            <span className="muted small">{f.protocol ?? ''}</span>
          </button>
        ))}
      </div>
      {editable && <div className="row between insp-foot"><span className="small muted">Değişiklikler “Kaydet” ile yeni sürüm olur.</span><button className="btn small danger" onClick={onDelete}>Bileşeni sil</button></div>}
    </section>
  );
}

function FlowInspector({ f, model, editable, issues, crosses, onChange, onSelect, onDelete, onClose }: {
  f: DataFlow; model: SystemModel; editable: boolean; issues: ValidationIssue[]; crosses: boolean;
  onChange: (p: Partial<DataFlow>) => void; onSelect: (id: string) => void; onDelete: () => void; onClose: () => void;
}) {
  const names: Record<string, string> = Object.fromEntries(model.components.map((x) => [x.id, x.name]));
  return (
    <section className="card stack insp">
      <InspectorHead id={f.id} title={`${names[f.source] ?? f.source} → ${names[f.target] ?? f.target}`} sub={crosses ? 'Güven sınırını geçen akış' : 'Bölge içi akış'} onClose={onClose} />
      <IssueList issues={issues} />
      <div className="stack" style={{ gap: 6 }}>
        <button className="link-row" onClick={() => onSelect(f.source)}><span className="mono">{f.source}</span><span>Kaynak: {names[f.source]}</span><span className="muted small">aç</span></button>
        <button className="link-row" onClick={() => onSelect(f.target)}><span className="mono">{f.target}</span><span>Hedef: {names[f.target]}</span><span className="muted small">aç</span></button>
      </div>
      {editable ? (
        <div className="form-grid">
          <Fld label="Kaynak">
            <select value={f.source} onChange={(e) => onChange({ source: e.target.value })}>{model.components.map((c) => <option key={c.id} value={c.id}>{c.id} · {c.name}</option>)}</select>
          </Fld>
          <Fld label="Hedef">
            <select value={f.target} onChange={(e) => onChange({ target: e.target.value })}>{model.components.map((c) => <option key={c.id} value={c.id}>{c.id} · {c.name}</option>)}</select>
          </Fld>
          <Fld label="Protokol"><input value={f.protocol ?? ''} placeholder="Ör. HTTPS, SFTP" onChange={(e) => onChange({ protocol: e.target.value || null })} /></Fld>
          <Fld label="Kimlik doğrulama">
            <select value={f.authentication} onChange={(e) => onChange({ authentication: e.target.value })}>
              {Object.keys(AUTH_LABEL).map((a) => <option key={a} value={a}>{AUTH_LABEL[a]}</option>)}
            </select>
          </Fld>
          <Fld label="Şifreli">
            <select value={triState(f.encrypted)} onChange={(e) => onChange({ encrypted: fromTri(e.target.value) })}>
              <option value="">Bilinmiyor</option><option value="true">Evet</option><option value="false">Hayır</option>
            </select>
          </Fld>
          <div className="full"><span className="field-label">Taşınan veri</span><DataClassChips value={f.dataClasses} editable onChange={(v) => onChange({ dataClasses: v })} /></div>
          <div className="full"><Fld label="Açıklama"><textarea rows={2} value={f.description} onChange={(e) => onChange({ description: e.target.value })} /></Fld></div>
        </div>
      ) : (
        <dl className="kv-grid">
          {f.description && <><dt>Açıklama</dt><dd>{f.description}</dd></>}
          <dt>Protokol</dt><dd>{f.protocol ?? '–'}</dd>
          <dt>Kimlik doğrulama</dt><dd>{AUTH_LABEL[f.authentication]}</dd>
          <dt>Şifreli</dt><dd>{yesNo(f.encrypted)}</dd>
          <dt>Taşınan veri</dt><dd><DataClassChips value={f.dataClasses} editable={false} onChange={() => undefined} /></dd>
        </dl>
      )}
      <EvidenceBox e={f.evidence} />
      {editable && <div className="row between insp-foot"><span className="small muted">Değişiklikler “Kaydet” ile yeni sürüm olur.</span><button className="btn small danger" onClick={onDelete}>Akışı sil</button></div>}
    </section>
  );
}
