import type { StepView } from '../api/types';
import { STEP_STATUS_LABEL } from '../lib/labels';

const ACTOR_LABEL: Record<string, string> = { SYSTEM: 'Sistem', LLM: 'LLM', ANALYST: 'Analist' };

/** Yükleme sonrası akışın hangi adımda olduğunu gösteren ilerleme çubuğu. */
export default function PipelineProgress({ steps }: { steps: StepView[] }) {
  const done = steps.filter((s) => s.status === 'DONE').length;
  const current = steps.find((s) => s.status !== 'DONE');
  const pct = Math.round((done / Math.max(steps.length, 1)) * 100);
  return (
    <div className="card progress-card stack" aria-label="Akış durumu">
      <div className="row between">
        <h2 style={{ margin: 0 }}>Akış durumu</h2>
        <span className="muted small">
          {done}/{steps.length} adım · %{pct}
        </span>
      </div>
      <div className="progress" role="list">
        {steps.map((s) => (
          <div
            key={s.stage}
            role="listitem"
            className={`pstep ${s.status}${current?.stage === s.stage ? ' current' : ''}`}
            title={`${s.label}: ${STEP_STATUS_LABEL[s.status]}${s.message ? ' – ' + s.message : ''}`}
          >
            <div className="pbar" />
            <span className="pactor">{ACTOR_LABEL[s.actor]}</span>
            <span className="plabel">{s.label}</span>
          </div>
        ))}
      </div>
      <div className="plegend" aria-hidden="true">
        <span><i style={{ background: 'var(--graphite)' }} />Tamamlandı</span>
        <span><i style={{ background: 'var(--brand)' }} />Çalışıyor / analist bekliyor</span>
        <span><i style={{ background: 'repeating-linear-gradient(135deg, #8a929b 0 4px, #b9bec5 4px 8px)' }} />LLM bekleniyor</span>
        <span><i style={{ background: '#e3e5e8' }} />Sırada</span>
      </div>
      {current ? (
        <div className={`notice ${current.status === 'FAILED' ? 'error' : current.status === 'WAITING_USER' ? 'warn' : 'info'}`}>
          <strong>{current.label}</strong> · {STEP_STATUS_LABEL[current.status]}
          {current.message ? ` – ${current.message}` : ''}
        </div>
      ) : (
        <div className="notice ok">Tüm adımlar tamamlandı.</div>
      )}
    </div>
  );
}
