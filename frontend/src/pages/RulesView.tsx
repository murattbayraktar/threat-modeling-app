import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { LEVEL_LABEL, STRIDE_LABEL, levelClass } from '../lib/labels';

/** Kural dosyalarının salt okunur görünümü. Kurallar datasets/rules altındaki JSON dosyalarında düzenlenir. */
export default function RulesView() {
  const [rules, setRules] = useState<Record<string, any> | null>(null);
  const [msg, setMsg] = useState<{ type: string; text: string } | null>(null);

  useEffect(() => {
    api.rules().then(setRules);
  }, []);

  async function reload() {
    try {
      setRules(await api.reloadRules());
      setMsg({ type: 'ok', text: 'Kurallar yeniden yüklendi.' });
    } catch (e) {
      setMsg({ type: 'error', text: `Yüklenemedi (önceki kurallar geçerli): ${(e as Error).message}` });
    }
  }

  if (!rules) return <div className="muted">Yükleniyor…</div>;
  const m = rules.methodology;
  return (
    <>
      <div className="row between">
        <div className="stack" style={{ gap: 4 }}>
          <h1>Kurallar ve risk metodolojisi</h1>
          <span className="muted">Kurallar datasets/rules altındaki JSON dosyalarındadır. Değiştirdikten sonra yeniden yükleyin; yeni incelemeler güncel kuralları kullanır.</span>
        </div>
        <button className="btn primary" onClick={reload}>Kuralları yeniden yükle</button>
      </div>
      {msg && <div className={`notice ${msg.type}`}>{msg.text}</div>}
      <section className="card stack">
        <h2>Risk metodolojisi · {m.version}</h2>
        <span className="small muted">{m.description}</span>
        <div className="row">{m.levels.map((l: any) => <span key={l.id} className={levelClass(l.id)}>{LEVEL_LABEL[l.id]}: {l.min}–{l.max}</span>)}</div>
        <span className="small">Skor = olasılık × etki (1–5). Başlangıç: aday kuralın varsayılanı, yoksa STRIDE varsayılanı; ardından risk kuralları sırayla uygulanır.</span>
      </section>
      <section className="card flush">
        <div className="card-head"><h2 style={{ margin: 0 }}>Risk kuralları · {rules.riskRules.version}</h2></div>
        <div className="scroll-x">
          <table className="tbl">
            <thead><tr><th>ID</th><th>Sıra</th><th>Açıklama</th><th>Koşul</th><th>Etki</th></tr></thead>
            <tbody>
              {rules.riskRules.rules.map((r: any) => (
                <tr key={r.id}><td className="mono">{r.id}</td><td>{r.order}</td><td>{r.description}<div className="small muted">{r.rationale}</div></td>
                  <td><pre className="mono" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{JSON.stringify(r.when, null, 1)}</pre></td>
                  <td className="mono">{JSON.stringify(r.then)}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="card flush">
        <div className="card-head"><h2 style={{ margin: 0 }}>Aday tehdit kuralları · {rules.threatRules.version} ({rules.loaded.threatRules} etkin)</h2></div>
        <div className="scroll-x">
          <table className="tbl">
            <thead><tr><th>ID</th><th>Uygulandığı</th><th>STRIDE</th><th>Başlık</th><th>Koşul</th><th>O / E</th></tr></thead>
            <tbody>
              {rules.threatRules.rules.map((r: any) => (
                <tr key={r.id}><td className="mono">{r.id}</td><td>{r.appliesTo === 'COMPONENT' ? 'Bileşen' : 'Veri akışı'}</td><td>{STRIDE_LABEL[r.stride]}</td>
                  <td>{r.title}<div className="small muted">{r.titleTemplate}</div></td>
                  <td><pre className="mono" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{JSON.stringify(r.when, null, 1)}</pre></td>
                  <td>{r.defaultLikelihood} / {r.defaultImpact}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="card"><span className="small">Kullanılabilir operatörler: {rules.operators.join(', ')}</span></section>
    </>
  );
}
