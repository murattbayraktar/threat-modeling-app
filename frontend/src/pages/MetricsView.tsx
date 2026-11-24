import { useEffect, useState } from 'react';
import { api } from '../api/client';

const LABELS: Record<string, string> = {
  reviews: 'İnceleme', completedReviews: 'Tamamlanan inceleme', threats: 'Tehdit', decided: 'Karar verilen',
  acceptanceRate: 'Kabul oranı', rejectionRate: 'Red oranı', falsePositiveRate: 'Yanlış pozitif oranı',
  duplicateRate: 'Tekrar (duplicate) oranı', aiAdditionalThreats: 'AI ek önerisi', aiAdditionalAcceptanceRate: 'AI ek önerisi kabul oranı',
  mitigationAcceptedUnchangedRate: 'Önlemlerin değiştirilmeden kabul oranı', candidatesFilledFromRules: 'LLM\'in atladığı (şablonla tamamlanan) aday',
  expertRiskOverrides: 'Uzmanın değiştirdiği risk', avgMissingStrideCategories: 'Ortalama eksik STRIDE kategorisi',
};

export default function MetricsView() {
  const [m, setM] = useState<Record<string, unknown> | null>(null);
  useEffect(() => {
    api.metrics().then(setM);
  }, []);
  if (!m) return <div className="muted">Yükleniyor…</div>;
  const fmt = (k: string, v: unknown) => v == null ? '–' : k.endsWith('Rate') ? `%${Math.round(Number(v) * 100)}` : String(v);
  return (
    <>
      <div className="stack" style={{ gap: 4 }}>
        <h1>İzleme metrikleri</h1>
        <span className="muted">Model kalitesini izlemek için. Tek başına güvenlik etkinliğinin kanıtı değildir.</span>
      </div>
      <div className="grid">
        {Object.entries(m).map(([k, v]) => (
          <div key={k} className="card stat"><span className="muted small">{LABELS[k] ?? k}</span><span className="v">{fmt(k, v)}</span></div>
        ))}
      </div>
    </>
  );
}
