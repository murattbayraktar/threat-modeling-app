import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { AuditEvent, LlmCallView, ThreatEvent } from '../api/types';
import { fmtDate } from '../lib/labels';

/** LLM çağrıları (istem, ham çıktı, doğrulama hataları), denetim izi ve uzman karar geçmişi. */
export default function TracePanel({ reviewId, version }: { reviewId: string; version: number }) {
  const [calls, setCalls] = useState<LlmCallView[]>([]);
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [events, setEvents] = useState<ThreatEvent[]>([]);

  useEffect(() => {
    api.llmCalls(reviewId).then(setCalls);
    api.audit(reviewId).then(setAudit);
    api.threatEvents(reviewId).then(setEvents);
  }, [reviewId, version]);

  const statusClass = (s: string) => s === 'COMPLETED' ? 'pill green' : s === 'PENDING' || s === 'SUBMITTED' ? 'pill purple' : s === 'SUPERSEDED' ? 'pill gray' : 'pill red';

  return (
    <div className="stack">
      <section className="card stack">
        <h2>LLM çağrıları ({calls.length})</h2>
        {calls.map((c) => (
          <details key={c.id} style={{ borderTop: '1px solid var(--line-2)', paddingTop: 8 }}>
            <summary className="row small" style={{ display: 'flex' }}>
              <span className={statusClass(c.status)}>{c.status}</span>
              <strong>{c.stage}</strong><span className="mono">{c.callKey}</span><span>deneme {c.attempt}</span>
              <span className="muted">{c.executor}{c.model ? ` · ${c.model}` : ''} · {c.promptVersion}{c.durationMs != null ? ` · ${c.durationMs} ms` : ''} · {fmtDate(c.createdAt)}</span>
            </summary>
            <div className="stack" style={{ marginTop: 8 }}>
              {c.errors.length > 0 && <div className="notice error small">{c.errors.slice(0, 8).map((e, i) => <div key={i}>{e}</div>)}</div>}
              <strong className="small">Sistem istemi</strong>
              <pre className="code">{c.systemPrompt}</pre>
              <strong className="small">Kullanıcı istemi</strong>
              <pre className="code">{c.userPrompt}</pre>
              <strong className="small">Ham çıktı</strong>
              <pre className="code">{prettify(c.rawOutput)}</pre>
            </div>
          </details>
        ))}
      </section>
      <section className="card stack">
        <h2>Uzman karar geçmişi ({events.length})</h2>
        {events.map((e) => (
          <div key={e.id} className="row small" style={{ borderTop: '1px solid var(--line-2)', paddingTop: 6 }}>
            <span className="muted" style={{ width: 130 }}>{fmtDate(e.createdAt)}</span><span className="mono">{e.action}</span><span>{e.actor}</span>
            {e.note && <span className="muted">· {e.note}</span>}
          </div>
        ))}
      </section>
      <section className="card stack">
        <h2>Denetim izi</h2>
        {audit.map((e) => (
          <div key={e.id} className="row small" style={{ borderTop: '1px solid var(--line-2)', paddingTop: 6 }}>
            <span className="muted" style={{ width: 130 }}>{fmtDate(e.createdAt)}</span><span className="mono">{e.type}</span><span>{e.message}</span><span className="muted">· {e.actor}</span>
          </div>
        ))}
      </section>
    </div>
  );
}

function prettify(s?: string) {
  if (!s) return '–';
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
