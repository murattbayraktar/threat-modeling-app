import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { DocumentView } from '../api/types';

interface Parsed {
  documentKey: string;
  fileName: string;
  sections: { id: string; heading: string; text: string }[];
  diagram?: { nodes: { id: string; label: string }[]; edges: { source: string; target: string; label: string }[] } | null;
  warnings: string[];
}

export default function DocumentsPanel({ reviewId, documents, version }: { reviewId: string; documents: DocumentView[]; version: number }) {
  const [parsed, setParsed] = useState<Parsed[]>([]);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    api.documents(reviewId).then((d) => setParsed(d as Parsed[]));
  }, [reviewId, version]);

  return (
    <div className="stack">
      <div className="card flush">
        <div className="scroll-x">
          <table className="tbl">
            <thead><tr><th>Kimlik</th><th>Dosya</th><th>Çıkarım</th><th>Bölüm</th><th>Karakter</th><th>Uyarılar</th><th /></tr></thead>
            <tbody>
              {documents.map((d) => (
                <tr key={d.id}>
                  <td className="mono">{d.documentKey}</td>
                  <td style={{ fontWeight: 600 }}>{d.fileName}</td>
                  <td><span className="pill gray">{d.extractionMethod ?? 'bekliyor'}</span></td>
                  <td>{d.sections}</td>
                  <td>{d.chars}</td>
                  <td>{d.warnings.map((w, i) => <div key={i} className="small" style={{ color: 'var(--orange)' }}>{w}</div>)}</td>
                  <td><button className="btn small" onClick={() => setOpen(open === d.documentKey ? null : d.documentKey)}>{open === d.documentKey ? 'Gizle' : 'İçerik'}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {parsed.filter((p) => p.documentKey === open).map((p) => (
        <div key={p.documentKey} className="card stack">
          <h2>{p.documentKey} · {p.fileName}</h2>
          <span className="muted small">LLM'e bu bölümler "[{p.documentKey}#bölüm]" kimlikleriyle gönderilir; model öğelerinin kanıtları bu kimliklere işaret eder.</span>
          {p.sections.map((s) => (
            <div key={s.id} className="stack" style={{ gap: 4 }}>
              <strong className="small">[{p.documentKey}#{s.id}] {s.heading}</strong>
              <pre className="code light">{s.text}</pre>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
