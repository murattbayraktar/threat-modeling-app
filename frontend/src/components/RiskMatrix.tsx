const COLORS = (score: number) =>
  score >= 17 ? ['#fee2e2', '#991b1b'] : score >= 10 ? ['#ffedd5', '#9a3412'] : score >= 5 ? ['#fef9c3', '#854d0e'] : ['#f3f4f6', '#626a73'];

/** 5x5 olasılık × etki matrisi. matrix[olasılık-1][etki-1] = tehdit sayısı. */
export default function RiskMatrix({ matrix }: { matrix: number[][] }) {
  const rows = [5, 4, 3, 2, 1];
  return (
    <div className="matrix" role="table" aria-label="Risk matrisi">
      {rows.map((l) => (
        <div key={l} style={{ display: 'contents' }} role="row">
          <div className="ax" role="rowheader">Olasılık {l}</div>
          {[1, 2, 3, 4, 5].map((i) => {
            const [bg, fg] = COLORS(l * i);
            const v = matrix[l - 1]?.[i - 1] ?? 0;
            return (
              <div key={i} className="cell" role="cell" style={{ background: bg, color: v ? fg : '#b9bec5' }} title={`Olasılık ${l} × Etki ${i} = ${l * i}`}>
                {v}
              </div>
            );
          })}
        </div>
      ))}
      <div />
      {[1, 2, 3, 4, 5].map((i) => (
        <div key={i} className="ax">Etki {i}</div>
      ))}
    </div>
  );
}
