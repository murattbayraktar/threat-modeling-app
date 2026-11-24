import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Evaluation, Report, Review, SystemModel } from '../api/types';
import ArchitectureDiagram, { type DiagramBadges } from './ArchitectureDiagram';
import RiskMatrix from './RiskMatrix';
import { DECISION_LABEL, LEVEL_LABEL, STRIDE_LABEL, fmtDate, levelClass } from '../lib/labels';

/** Rapor verisi backend'de üretilir; burada gösterilir ve tarayıcıdan PDF'e yazdırılır. */
export default function ReportView({ review, version, onChanged }: { review: Review; version: number; onChanged: () => void }) {
  const [report, setReport] = useState<Report | null>(null);
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [model, setModel] = useState<SystemModel | null>(null);
  const [badges, setBadges] = useState<DiagramBadges>({});

  useEffect(() => {
    api.report(review.id).then(setReport).catch((e) => setError(e.message));
    api.model(review.id).then((v) => setModel(v.current?.model ?? null)).catch(() => setModel(null));
    api.threats(review.id).then((ts) => {
      const b: DiagramBadges = {};
      ts.filter((t) => t.decision !== 'REJECTED' && t.decision !== 'FALSE_POSITIVE').forEach((t) => (b[t.targetId] = (b[t.targetId] ?? 0) + 1));
      setBadges(b);
    }).catch(() => setBadges({}));
    if (review.sampleName) api.evaluation(review.id).then(setEvaluation).catch(() => setEvaluation(null));
  }, [review.id, review.sampleName, version]);

  if (error) return <div className="notice error">{error}</div>;
  if (!report) return <div className="muted">Yükleniyor…</div>;
  const maxStride = Math.max(1, ...Object.values(report.strideDistribution));
  const completed = review.status === 'COMPLETED';

  function downloadJson() {
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${review.code}-rapor.json`;
    a.click();
  }

  return (
    <div className="stack">
      <div className="row no-print">
        <button className="btn primary" onClick={() => window.print()}>Yazdır / PDF</button>
        <button className="btn" onClick={downloadJson}>JSON indir</button>
        {completed && <button className="btn" onClick={() => api.reopenReview(review.id).then(onChanged)}>Uzman incelemesini yeniden aç</button>}
        {!completed && <span className="notice warn small">Ön rapor: uzman incelemesi henüz tamamlanmadı.</span>}
      </div>

      <section className="card stack">
        <div className="row between">
          <div className="stack" style={{ gap: 4 }}>
            <h2 style={{ margin: 0 }}>Threat model raporu · {review.code}</h2>
            <span className="muted small">{review.title} · {review.businessUnit} · Oluşturma: {fmtDate(report.generatedAt)}</span>
          </div>
          <span className={completed ? 'pill green' : 'pill orange'}>{completed ? 'Tamamlandı' : 'Taslak'}</span>
        </div>
        <div className="grid">
          <div className="stat"><span className="muted small">Bileşen / akış</span><span className="v">{report.modelSummary.components ?? 0} / {report.modelSummary.dataFlows ?? 0}</span></div>
          {(['ACCEPTED', 'REJECTED', 'FALSE_POSITIVE', 'PENDING'] as const).map((d) => (
            <div key={d} className="stat"><span className="muted small">{DECISION_LABEL[d]}</span><span className="v">{report.decisions[d] ?? 0}</span></div>
          ))}
        </div>
        <div className="notice info small">{report.disclaimer}</div>
      </section>

      {model && (
        <section className="card stack">
          <div className="stack" style={{ gap: 2 }}>
            <h2 style={{ margin: 0 }}>Mimari diyagram ve tehdit yoğunluğu</h2>
            <span className="small muted">Kırmızı rozetler, bileşen veya akış başına reddedilmemiş tehdit sayısını gösterir.</span>
          </div>
          <ArchitectureDiagram model={model} badges={badges} />
        </section>
      )}

      <div className="split">
        <section className="grow card stack">
          <h2>STRIDE dağılımı</h2>
          <div className="bars">
            {Object.entries(report.strideDistribution).map(([k, v]) => (
              <div key={k} className="bar-row">
                <span>{STRIDE_LABEL[k]}</span>
                <div className="bar-track"><div className="bar" style={{ width: `${(v / maxStride) * 100}%`, minWidth: v ? 6 : 0 }} /></div>
                <span>{v}</span>
              </div>
            ))}
          </div>
          {report.missingStrideCategories.length > 0 && (
            <div className="small muted">Tehdit bulunmayan kategoriler: {report.missingStrideCategories.map((s) => STRIDE_LABEL[s]).join(', ')}. Bunun gerçekten risk olmadığından emin olun.</div>
          )}
        </section>
        <section className="side-panel card stack">
          <h2>Risk matrisi</h2>
          <RiskMatrix matrix={report.riskMatrix} />
          <div className="row" style={{ gap: 4 }}>{Object.entries(report.levelDistribution).map(([l, v]) => <span key={l} className={levelClass(l)}>{v} {LEVEL_LABEL[l]?.toLowerCase()}</span>)}</div>
        </section>
      </div>

      <section className="card flush">
        <div className="card-head"><h2 style={{ margin: 0 }}>Kritik ve yüksek riskli tehditler ({report.criticalThreats.length})</h2></div>
        <div className="scroll-x">
          <table className="tbl">
            <thead><tr><th>ID</th><th>Tehdit</th><th>STRIDE</th><th>Hedef</th><th>O×E</th><th>Risk</th><th>Karar</th></tr></thead>
            <tbody>
              {report.criticalThreats.map((t) => (
                <tr key={t.code}>
                  <td className="muted">{t.code}</td><td>{t.title}</td><td>{STRIDE_LABEL[t.stride]}</td><td>{t.target}</td>
                  <td>{t.likelihood}×{t.impact}={t.score}{t.expertOverride && <span className="small muted"> (uzman)</span>}</td>
                  <td><span className={levelClass(t.level)}>{LEVEL_LABEL[t.level ?? '']}</span></td>
                  <td>{DECISION_LABEL[t.decision]}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card flush">
        <div className="card-head"><h2 style={{ margin: 0 }}>Aksiyon planı</h2><span className="muted small">Kabul edilen tehditlerin kontrolleri</span></div>
        <div className="scroll-x">
          <table className="tbl">
            <thead><tr><th>Tehdit</th><th>Risk</th><th>Kontrol</th><th>Kaynak</th><th>Öneren</th></tr></thead>
            <tbody>
              {report.actionPlan.length === 0 && <tr><td colSpan={5} className="muted">Kabul edilmiş tehdit yok.</td></tr>}
              {report.actionPlan.map((a, idx) => (
                <tr key={idx}>
                  <td><span className="muted">{a.threatCode}</span> {a.threatTitle}</td>
                  <td><span className={levelClass(a.level)}>{LEVEL_LABEL[a.level]}</span></td>
                  <td><strong>{a.mitigationTitle}</strong><div className="small muted">{a.mitigationDescription}</div></td>
                  <td>{a.sourceIds.join(', ') || '–'}</td>
                  <td className="small">{a.origin === 'AI' ? 'AI' : 'Uzman'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <div className="split">
        <section className="grow card stack">
          <h2>Açık sorular</h2>
          {report.openQuestions.length === 0 && <span className="muted small">Yok.</span>}
          {report.openQuestions.map((q, i) => <div key={i} className="small">• {q.question}</div>)}
        </section>
        <section className="side-panel card stack">
          <h2>Sürümler ve izlenebilirlik</h2>
          <div className="small">Model: v{report.versions.modelVersion} (onaylayan {report.versions.modelApprovedBy ?? '–'})</div>
          <div className="small">Tehdit kuralları: {report.versions.threatRules}</div>
          <div className="small">Risk kuralları: {report.versions.riskRules} · metodoloji {report.versions.methodology}</div>
          <div className="small">İstemler: {report.versions.prompts.join(', ') || '–'}</div>
          <div className="small">LLM: {report.versions.llmExecutors.join(', ') || '–'}</div>
        </section>
      </div>

      {evaluation && (
        <section className="card stack no-print">
          <h2>Değerlendirme (örnek proje referansı)</h2>
          <div className="row">
            <span className="pill blue">Recall: %{Math.round(evaluation.recall * 100)}</span>
            <span className="muted small">{evaluation.matched}/{evaluation.expected} beklenen tehdit yakalandı · {evaluation.unmatchedGenerated} ek tehdit</span>
          </div>
          <table className="tbl">
            <thead><tr><th>Beklenen</th><th>STRIDE</th><th>Hedef</th><th>Not</th><th>Eşleşen</th></tr></thead>
            <tbody>
              {evaluation.details.map((d) => (
                <tr key={d.id}><td>{d.id}</td><td>{STRIDE_LABEL[d.stride]}</td><td>{d.target}</td><td className="small">{d.note}</td>
                  <td>{d.matchedThreatCode ? <span className="pill green">{d.matchedThreatCode}</span> : <span className="pill red">kaçırıldı</span>}</td></tr>
              ))}
            </tbody>
          </table>
          <span className="small muted">Bu ölçüm model kalitesini izlemek içindir; tek başına güvenlik etkinliğinin kanıtı değildir.</span>
        </section>
      )}

      <section className="card stack">
        <h2>Denetim izi</h2>
        {report.audit.map((e) => (
          <div key={e.id} className="row small" style={{ borderTop: '1px solid var(--line-2)', paddingTop: 6 }}>
            <span className="muted" style={{ width: 130 }}>{fmtDate(e.createdAt)}</span><span className="mono">{e.type}</span><span>{e.message}</span><span className="muted">· {e.actor}</span>
          </div>
        ))}
      </section>
    </div>
  );
}
