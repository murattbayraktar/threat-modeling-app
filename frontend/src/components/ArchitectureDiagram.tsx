import { useEffect, useMemo, useRef, useState } from 'react';
import type { Component, DataFlow, SystemModel } from '../api/types';
import { AUTH_LABEL, COMPONENT_TYPE_LABEL, DATA_CLASS_LABEL, ZONE_LABEL } from '../lib/labels';

/*
 * Mimari modelin görsel hali (DFD benzeri):
 *  - Sütunlar güven bölgeleridir (İnternet → Üçüncü taraf → DMZ → İç ağ → Bilinmiyor); kesikli çerçeve = güven sınırı.
 *  - Kutu biçimi öğe türünü gösterir: dış varlık (köşeli, gri), süreç (yuvarlak, beyaz), veri deposu (silindir).
 *  - Oklar veri akışlarıdır: kırmızı = güven sınırını geçiyor, gri = bölge içi, kesikli = şifresiz / bilinmiyor.
 * Harici kütüphane kullanmaz; yerleşim burada hesaplanır. Model değiştikçe (analist düzenlemesi) anında yeniden çizilir.
 */

const ZONE_ORDER = ['INTERNET', 'THIRD_PARTY', 'DMZ', 'INTERNAL', 'UNKNOWN'];
const ZONE_STYLE: Record<string, { fill: string; stroke: string; label: string }> = {
  INTERNET: { fill: '#fdf1f3', stroke: '#e3a3ae', label: '#9b0c23' },
  THIRD_PARTY: { fill: '#f6f6f7', stroke: '#b9bec5', label: '#3a4149' },
  DMZ: { fill: '#f8f6f4', stroke: '#c9c2bb', label: '#3a4149' },
  INTERNAL: { fill: '#f3f5f7', stroke: '#b9bec5', label: '#3a4149' },
  UNKNOWN: { fill: '#ffffff', stroke: '#d5d8dc', label: '#626a73' },
};
const EXTERNAL = new Set(['USER', 'EXTERNAL_SYSTEM', 'THIRD_PARTY_SYSTEM']);
const STORE = new Set(['DATABASE', 'FILE_STORAGE', 'MESSAGE_QUEUE']);
const kindOf = (c: Component) => (EXTERNAL.has(c.type) ? 'EXTERNAL' : STORE.has(c.type) ? 'STORE' : 'PROCESS');

const C = {
  ink: '#1d2125', muted: '#626a73', line: '#8a929b', red: '#c8102e', redDark: '#9b0c23', amber: '#b45309',
  dim: 0.18,
};

const NODE_W = 180;
const NODE_H = 60;
const ROW_GAP = 40;
const SUB_GAP = 96;
const ZONE_PAD = 22;
const ZONE_HEAD = 34;
const ZONE_GAP = 70;
const MAX_ROWS = 5;
const MARGIN = 16;
/** Bölge içi akışların sağa doğru çizdiği yaylar için ayrılan pay. */
const ARC_LANE = 86;
/** Dolaşan oklar için hat aralığı. */
const LANE = 9;

interface Box { c: Component; x: number; y: number; zone: string }
interface Zone { zone: string; x: number; w: number; cols: number }
interface Edge { f: DataFlow; d: string; lx: number; ly: number; crosses: boolean; weak: boolean; unknownAuth: boolean }

export interface DiagramBadges { [elementId: string]: number }

function truncate(s: string, max: number) {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

/** Bölge sırasını akışlara göre seçer: atlanan bölge sayısı (uzun oklar) en aza iner. İnternet başta, Bilinmiyor sonda kalır. */
function orderZones(present: string[], model: SystemModel, zoneOfId: (id: string) => string | undefined): string[] {
  const head = present.filter((z) => z === 'INTERNET');
  const tail = present.filter((z) => z === 'UNKNOWN');
  const mid = present.filter((z) => z !== 'INTERNET' && z !== 'UNKNOWN');
  const perms = (xs: string[]): string[][] => (xs.length <= 1 ? [xs] : xs.flatMap((x, i) => perms([...xs.slice(0, i), ...xs.slice(i + 1)]).map((r) => [x, ...r])));
  let best = present;
  let bestCost = Infinity;
  perms(mid).forEach((m) => {
    const order = [...head, ...m, ...tail];
    const pos: Record<string, number> = Object.fromEntries(order.map((z, i) => [z, i]));
    let cost = 0;
    model.dataFlows.forEach((f) => {
      const a = zoneOfId(f.source), b = zoneOfId(f.target);
      if (a && b && a !== b) cost += Math.abs(pos[a] - pos[b]) - 1; // yalnızca atlanan bölgeler maliyetlidir
    });
    // Eşitlikte varsayılan sırayı koru
    const tie = m.reduce((acc, z, i) => acc + Math.abs(ZONE_ORDER.indexOf(z) - (i + 1)) * 1e-3, 0);
    if (cost + tie < bestCost) {
      bestCost = cost + tie;
      best = order;
    }
  });
  return best;
}

function layout(model: SystemModel) {
  const zoneOf = (c: Component) => (ZONE_ORDER.includes(c.zone) ? c.zone : 'UNKNOWN');
  const byId: Record<string, Component> = Object.fromEntries(model.components.map((c) => [c.id, c]));
  const present = ZONE_ORDER.filter((z) => model.components.some((c) => zoneOf(c) === z));
  const zones = orderZones(present, model, (id) => (byId[id] ? zoneOf(byId[id]) : undefined));
  const kindRank = { EXTERNAL: 0, PROCESS: 1, STORE: 2 } as const;
  const groups: Record<string, Component[]> = {};
  zones.forEach((z) => {
    groups[z] = model.components.filter((c) => zoneOf(c) === z).sort((a, b) => kindRank[kindOf(a)] - kindRank[kindOf(b)]);
  });

  const neighbours: Record<string, string[]> = {};
  model.components.forEach((c) => (neighbours[c.id] = []));
  model.dataFlows.forEach((f) => {
    if (neighbours[f.source] && neighbours[f.target]) {
      neighbours[f.source].push(f.target);
      neighbours[f.target].push(f.source);
    }
  });

  // Kesişmeleri azaltmak için barycenter sıralaması: her bileşeni komşularının ortalama sırasına göre diz.
  const rank: Record<string, number> = {};
  const assign = () => zones.forEach((z) => groups[z].forEach((c, i) => (rank[c.id] = (i + 0.5) / groups[z].length)));
  assign();
  for (let iter = 0; iter < 6; iter++) {
    zones.forEach((z) => {
      const scored = groups[z].map((c, i) => {
        const ns = neighbours[c.id].filter((n) => rank[n] !== undefined && byId[n] && zoneOf(byId[n]) !== z);
        const bc = ns.length ? ns.reduce((acc, n) => acc + rank[n], 0) / ns.length : rank[c.id];
        return { c, key: bc + i * 1e-6 };
      });
      groups[z] = scored.sort((a, b) => a.key - b.key).map((x) => x.c);
    });
    assign();
  }

  // İki geçiş: önce kaç okun üstten/alttan dolaşacağı bulunur, sonra o kadar hat payı ayrılarak yeniden yerleşilir.
  const first = place(0, 0);
  return first.lanesTop || first.lanesBottom ? place(first.lanesTop, first.lanesBottom) : first;

  function place(padTopLanes: number, padBottomLanes: number) {
    const topPad = padTopLanes * LANE;
    const bottomPad = padBottomLanes * LANE;
    const rowsPer = (n: number) => Math.ceil(n / Math.ceil(n / MAX_ROWS));
    const maxRows = Math.max(1, ...zones.map((z) => rowsPer(groups[z].length)));
    const innerH = maxRows * NODE_H + (maxRows - 1) * ROW_GAP;
    const height = ZONE_HEAD + ZONE_PAD * 2 + topPad + bottomPad + innerH;
    const nodesTop = MARGIN + ZONE_HEAD + ZONE_PAD + topPad;

    const boxes: Record<string, Box> = {};
    const zoneRects: Zone[] = [];
    let x = MARGIN;
    zones.forEach((z) => {
      const list = groups[z];
      const cols = Math.ceil(list.length / MAX_ROWS);
      const rows = rowsPer(list.length);
      const ids = new Set(list.map((c) => c.id));
      const hasIntra = model.dataFlows.some((f) => ids.has(f.source) && ids.has(f.target));
      const w = cols * NODE_W + (cols - 1) * SUB_GAP + ZONE_PAD * 2 + (hasIntra ? ARC_LANE : 0);
      zoneRects.push({ zone: z, x, w, cols });
      list.forEach((c, i) => {
        const col = Math.floor(i / rows);
        const row = i % rows;
        const inCol = Math.min(rows, list.length - col * rows);
        const colH = inCol * NODE_H + (inCol - 1) * ROW_GAP;
        const top = nodesTop + (innerH - colH) / 2;
        boxes[c.id] = { c, zone: z, x: x + ZONE_PAD + col * (NODE_W + SUB_GAP), y: top + row * (NODE_H + ROW_GAP) };
      });
      x += w + ZONE_GAP;
    });
    const width = x - ZONE_GAP + MARGIN;
    const totalH = height + MARGIN * 2;
    const topLaneBase = MARGIN + ZONE_HEAD + 6;
    const bottomLaneBase = totalH - MARGIN - 8;

    // Aynı iki bileşen arasındaki birden fazla akışı birbirinden ayır
    const pairCount: Record<string, number> = {};
    const pairIndex: Record<string, number> = {};
    model.dataFlows.forEach((f) => {
      const k = [f.source, f.target].sort().join('|');
      pairIndex[f.id] = pairCount[k] ?? 0;
      pairCount[k] = (pairCount[k] ?? 0) + 1;
    });

    let usedTop = 0;
    let usedBottom = 0;
    const edges: Edge[] = [];
    model.dataFlows.forEach((f) => {
      const s = boxes[f.source];
      const t = boxes[f.target];
      if (!s || !t || s === t) return;
      const k = [f.source, f.target].sort().join('|');
      const n = pairCount[k];
      const off = (pairIndex[f.id] - (n - 1) / 2) * 14;
      const scx = s.x + NODE_W / 2;
      const tcx = t.x + NODE_W / 2;
      let d: string;
      let lx: number;
      let ly: number;
      if (Math.abs(scx - tcx) < 1) {
        // Aynı sütun: sağdan dışarı çıkıp geri dönen yay
        const sx = s.x + NODE_W, sy = s.y + NODE_H / 2 + off;
        const tx = t.x + NODE_W, ty = t.y + NODE_H / 2 + off;
        const hasRight = Object.values(boxes).some((b) => b.zone === s.zone && b.x > s.x);
        // Yayın en dış noktası ≈ 0.75 × bulge; etiketle birlikte ARC_LANE içinde kalacak şekilde sınırlanır
        const bulge = hasRight ? SUB_GAP * 0.95 + off * 0.3 : Math.min(80, 46 + Math.abs(sy - ty) * 0.08 + Math.abs(off));
        d = `M ${sx} ${sy} C ${sx + bulge} ${sy}, ${tx + bulge} ${ty}, ${tx + 2} ${ty}`;
        lx = sx + bulge * 0.75;
        ly = (sy + ty) / 2;
      } else {
        const dir = tcx > scx ? 1 : -1;
        const sx = dir > 0 ? s.x + NODE_W : s.x;
        const tx = dir > 0 ? t.x - 2 : t.x + NODE_W + 2;
        const sy = s.y + NODE_H / 2 + off;
        const ty = t.y + NODE_H / 2 + off;
        // Aradaki kutuların içinden geçecekse ok, kendine ayrılmış üst ya da alt hattan dolaşır
        const lo = Math.min(sx, tx), hi = Math.max(sx, tx);
        const blockers = Object.values(boxes).filter((b) => b !== s && b !== t && b.x + NODE_W > lo + 1 && b.x < hi - 1);
        const crossesBox = blockers.some((b) => {
          const yMin = Math.min(sy, ty) - 6, yMax = Math.max(sy, ty) + 6;
          return b.y < yMax && b.y + NODE_H > yMin;
        });
        if (crossesBox) {
          const mid = (sy + ty) / 2;
          const useTop = mid - nodesTop <= nodesTop + innerH - mid ? usedTop <= usedBottom + 2 : usedBottom > usedTop + 2;
          const lane = useTop ? topLaneBase + LANE * usedTop++ : bottomLaneBase - LANE * usedBottom++;
          const reach = Math.min(ZONE_GAP / 2 - 4, Math.abs(tx - sx) / 4);
          const g1 = sx + dir * (reach + (useTop ? usedTop : usedBottom) % 4 * 3);
          const g2 = tx - dir * (reach + (useTop ? usedTop : usedBottom) % 4 * 3);
          const r = 10;
          const v1 = Math.sign(lane - sy) || 1;
          const v2 = Math.sign(ty - lane) || 1;
          d = `M ${sx} ${sy} L ${g1 - dir * r} ${sy} Q ${g1} ${sy}, ${g1} ${sy + v1 * r}`
            + ` L ${g1} ${lane - v1 * r} Q ${g1} ${lane}, ${g1 + dir * r} ${lane}`
            + ` L ${g2 - dir * r} ${lane} Q ${g2} ${lane}, ${g2} ${lane + v2 * r}`
            + ` L ${g2} ${ty - v2 * r} Q ${g2} ${ty}, ${g2 + dir * r} ${ty} L ${tx} ${ty}`;
          lx = (g1 + g2) / 2 + (((usedTop + usedBottom) % 5) - 2) * 26;
          ly = lane;
        } else {
          const span = Math.max(40, Math.abs(tx - sx) * 0.45);
          const c1x = sx + dir * span, c2x = tx - dir * span;
          d = `M ${sx} ${sy} C ${c1x} ${sy}, ${c2x} ${ty}, ${tx} ${ty}`;
          lx = (sx + 3 * c1x + 3 * c2x + tx) / 8;
          ly = (sy + ty) / 2;
        }
      }
      const crosses = f.crossesTrustBoundary ?? s.zone !== t.zone;
      edges.push({ f, d, lx, ly, crosses: !!crosses, weak: f.encrypted !== true, unknownAuth: f.authentication === 'UNKNOWN' });
    });

    return { boxes, zoneRects, edges, width: Math.max(width, 320), height: totalH, lanesTop: usedTop, lanesBottom: usedBottom };
  }
}

/** Mimari modeli güven bölgeleri, bileşenler ve veri akışlarıyla çizer. Tıklanan öğenin ayrıntısı altta gösterilir. */
export default function ArchitectureDiagram({ model, badges, badgeLabel = 'tehdit', selected: selectedProp, onSelect, flagged, hideDetail }: {
  model: SystemModel;
  badges?: DiagramBadges;
  badgeLabel?: string;
  /** Kontrollü kullanım: seçimi dışarıdaki bir panel yönetir. */
  selected?: string | null;
  onSelect?: (id: string | null) => void;
  /** Dikkat gerektiren öğeler (küçük turuncu işaret). */
  flagged?: Set<string>;
  hideDetail?: boolean;
}) {
  const { boxes, zoneRects, edges, width, height } = useMemo(() => layout(model), [model]);
  const [selectedLocal, setSelectedLocal] = useState<string | null>(null);
  const selected = onSelect ? selectedProp ?? null : selectedLocal;
  const setSelected = (id: string | null) => (onSelect ? onSelect(id) : setSelectedLocal(id));
  const [hover, setHover] = useState<string | null>(null);
  const [zoom, setZoom] = useState<'fit' | number>('fit');
  const fit = zoom === 'fit';
  const svgRef = useRef<SVGSVGElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const [wrapW, setWrapW] = useState(0);
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setWrapW(el.clientWidth));
    ro.observe(el);
    setWrapW(el.clientWidth);
    return () => ro.disconnect();
  }, []);
  const currentScale = () => (wrapW ? Math.min(1, (wrapW - 2) / width) : 1);

  const focus = hover ?? selected;
  const focusIsNode = focus ? !!boxes[focus] : false;
  const related = useMemo(() => {
    const set = new Set<string>();
    if (!focus) return set;
    set.add(focus);
    model.dataFlows.forEach((f) => {
      if (focusIsNode && (f.source === focus || f.target === focus)) {
        set.add(f.id);
        set.add(f.source);
        set.add(f.target);
      }
      if (!focusIsNode && f.id === focus) {
        set.add(f.source);
        set.add(f.target);
      }
    });
    return set;
  }, [focus, focusIsNode, model.dataFlows]);
  const op = (id: string) => (focus && !related.has(id) ? C.dim : 1);

  const names = Object.fromEntries(model.components.map((c) => [c.id, c.name]));
  const selComp = selected ? model.components.find((c) => c.id === selected) : undefined;
  const selFlow = selected ? model.dataFlows.find((f) => f.id === selected) : undefined;

  function exportSvg() {
    if (!svgRef.current) return;
    const clone = svgRef.current.cloneNode(true) as SVGSVGElement;
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    clone.setAttribute('width', String(width));
    clone.setAttribute('height', String(height));
    clone.querySelectorAll('[opacity]').forEach((el) => el.setAttribute('opacity', '1'));
    const blob = new Blob([new XMLSerializer().serializeToString(clone)], { type: 'image/svg+xml' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${(model.system.name || 'mimari').replace(/[^\p{L}\p{N}]+/gu, '-')}-diyagram.svg`;
    a.click();
  }

  if (model.components.length === 0) {
    return <div className="notice info">Modelde bileşen yok; diyagram için en az bir bileşen gerekli.</div>;
  }

  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="row between">
        <div className="dlegend">
          <span><svg width="26" height="10" aria-hidden="true"><line x1="0" y1="5" x2="26" y2="5" stroke={C.red} strokeWidth="2" /></svg>Güven sınırını geçen akış</span>
          <span><svg width="26" height="10" aria-hidden="true"><line x1="0" y1="5" x2="26" y2="5" stroke={C.line} strokeWidth="2" /></svg>Bölge içi akış</span>
          <span><svg width="26" height="10" aria-hidden="true"><line x1="0" y1="5" x2="26" y2="5" stroke={C.line} strokeWidth="2" strokeDasharray="5 4" /></svg>Şifresiz / bilinmiyor</span>
          <span><svg width="14" height="14" aria-hidden="true"><rect x="1" y="1" width="12" height="12" rx="6" fill="#fff7e6" stroke={C.amber} /></svg>Kimlik doğrulama bilinmiyor</span>
        </div>
        <div className="row no-print" style={{ gap: 6 }}>
          <button className={'btn small' + (fit ? ' on' : '')} onClick={() => setZoom('fit')}>Sığdır</button>
          <span className="zoom-group" role="group" aria-label="Yakınlaştırma">
            <button className="btn small" aria-label="Uzaklaştır" onClick={() => setZoom((z) => Math.max(0.4, Math.round(((z === 'fit' ? currentScale() : z) - 0.1) * 10) / 10))}>−</button>
            <span className="zoom-v">{Math.round((fit ? currentScale() : (zoom as number)) * 100)}%</span>
            <button className="btn small" aria-label="Yakınlaştır" onClick={() => setZoom((z) => Math.min(2, Math.round(((z === 'fit' ? currentScale() : z) + 0.1) * 10) / 10))}>+</button>
          </span>
          <button className={'btn small' + (zoom === 1 ? ' on' : '')} onClick={() => setZoom(1)}>%100</button>
          <button className="btn small" onClick={exportSvg}>SVG indir</button>
        </div>
      </div>

      <div className="diagram-wrap" ref={wrapRef}>
        <svg
          ref={svgRef}
          viewBox={`0 0 ${width} ${height}`}
          width={fit ? '100%' : Math.round(width * (zoom as number))}
          height={fit ? undefined : Math.round(height * (zoom as number))}
          style={fit ? { maxWidth: width, margin: '0 auto' } : undefined}
          role="img"
          aria-label={`${model.system.name} mimari diyagramı: ${model.components.length} bileşen, ${model.dataFlows.length} veri akışı`}
          fontFamily='Inter, "Segoe UI", system-ui, sans-serif'
          onClick={() => setSelected(null)}
        >
          <defs>
            {[['gray', C.line], ['red', C.red], ['dark', C.ink]].map(([k, col]) => (
              <marker key={k} id={`arr-${k}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M1 1L9 5L1 9z" fill={col} />
              </marker>
            ))}
          </defs>
          <rect x="0" y="0" width={width} height={height} fill="#ffffff" />

          {zoneRects.map((z) => {
            const st = ZONE_STYLE[z.zone];
            return (
              <g key={z.zone}>
                <rect x={z.x} y={MARGIN} width={z.w} height={height - MARGIN * 2} rx="10" fill={st.fill} stroke={st.stroke} strokeWidth="1.2" strokeDasharray="7 5" />
                <text x={z.x + 14} y={MARGIN + 22} fontSize="12" fontWeight="700" fill={st.label} letterSpacing=".04em">
                  {(ZONE_LABEL[z.zone] ?? z.zone).toLocaleUpperCase('tr-TR')}
                </text>
                <text x={z.x + z.w - 14} y={MARGIN + 22} fontSize="11" fill={C.muted} textAnchor="end">
                  güven sınırı
                </text>
              </g>
            );
          })}

          {edges.map((e) => {
            const col = e.crosses ? C.red : C.line;
            const active = selected === e.f.id || hover === e.f.id;
            return (
              <g
                key={e.f.id}
                className="edge"
                opacity={op(e.f.id)}
                onClick={(ev) => { ev.stopPropagation(); setSelected(e.f.id); }}
                onMouseEnter={() => setHover(e.f.id)}
                onMouseLeave={() => setHover(null)}
              >
                <title>{`${e.f.id}: ${names[e.f.source]} → ${names[e.f.target]}${e.f.description ? ' – ' + e.f.description : ''}`}</title>
                <path d={e.d} fill="none" stroke="transparent" strokeWidth="12" />
                <path d={e.d} fill="none" stroke={active ? C.ink : col} strokeWidth={active ? 2.4 : e.crosses ? 1.8 : 1.4}
                  strokeDasharray={e.weak ? '6 4' : undefined} markerEnd={`url(#arr-${active ? 'dark' : e.crosses ? 'red' : 'gray'})`} />
                <g transform={`translate(${e.lx}, ${e.ly})`}>
                  <rect x="-15" y="-9" width="30" height="18" rx="9" fill={e.unknownAuth ? '#fff7e6' : '#ffffff'} stroke={e.unknownAuth ? C.amber : active ? C.ink : col} strokeWidth="1" />
                  <text x="0" y="4" fontSize="10.5" fontWeight="700" textAnchor="middle" fill={e.unknownAuth ? C.amber : e.crosses ? C.redDark : C.muted}>{e.f.id}</text>
                  {badges?.[e.f.id] ? <Badge x={15} y={-9} n={badges[e.f.id]} /> : null}
                </g>
              </g>
            );
          })}

          {Object.values(boxes).map(({ c, x, y }) => {
            const k = kindOf(c);
            const low = c.confidence === 'LOW';
            const active = selected === c.id || hover === c.id;
            const stroke = active ? C.ink : low ? C.amber : k === 'EXTERNAL' ? '#8a929b' : '#5b636c';
            const sw = active ? 2 : 1.2;
            const dash = low ? '5 3' : undefined;
            const fill = k === 'EXTERNAL' ? '#eef0f2' : '#ffffff';
            const textY = k === 'STORE' ? y + 30 : y + 26;
            return (
              <g
                key={c.id}
                className="node"
                opacity={op(c.id)}
                onClick={(ev) => { ev.stopPropagation(); setSelected(c.id); }}
                onMouseEnter={() => setHover(c.id)}
                onMouseLeave={() => setHover(null)}
              >
                <title>{`${c.id} · ${c.name}${c.evidence ? `\nKanıt (${c.evidence.documentId}#${c.evidence.section}): ${c.evidence.quote}` : '\nKanıt yok'}`}</title>
                {k === 'STORE' ? (
                  <>
                    <path d={`M ${x} ${y + 8} a ${NODE_W / 2} 8 0 0 1 ${NODE_W} 0 v ${NODE_H - 16} a ${NODE_W / 2} 8 0 0 1 ${-NODE_W} 0 z`} fill={fill} stroke={stroke} strokeWidth={sw} strokeDasharray={dash} />
                    <path d={`M ${x} ${y + 8} a ${NODE_W / 2} 8 0 0 0 ${NODE_W} 0`} fill="none" stroke={stroke} strokeWidth={sw} strokeDasharray={dash} />
                  </>
                ) : (
                  <rect x={x} y={y} width={NODE_W} height={NODE_H} rx={k === 'EXTERNAL' ? 2 : 12} fill={fill} stroke={stroke} strokeWidth={sw} strokeDasharray={dash} />
                )}
                {c.internetFacing && k !== 'EXTERNAL' && (
                  <rect x={x + (k === 'STORE' ? 0 : 0.6)} y={y + (k === 'STORE' ? 12 : 10)} width="3.5" height={NODE_H - (k === 'STORE' ? 24 : 20)} rx="1.5" fill={C.red} />
                )}
                <text x={x + 14} y={textY} fontSize="13" fontWeight="600" fill={C.ink}>{truncate(c.name, 22)}</text>
                <text x={x + 14} y={textY + 17} fontSize="11" fill={C.muted}>
                  {c.id} · {truncate(COMPONENT_TYPE_LABEL[c.type] ?? c.type, 15)}
                  {c.internetFacing ? <tspan fill={C.red} fontWeight="600"> · internet</tspan> : null}
                </text>
                {badges?.[c.id] ? <Badge x={x + NODE_W} y={y} n={badges[c.id]} /> : null}
                {flagged?.has(c.id) && !badges?.[c.id] ? (
                  <g transform={`translate(${x + NODE_W - 4}, ${y + 4})`}><title>Dikkat gerektiriyor</title><circle r="8" fill="#fff7e6" stroke={C.amber} /><text y="3.6" fontSize="10.5" fontWeight="700" fill={C.amber} textAnchor="middle">!</text></g>
                ) : null}
              </g>
            );
          })}
        </svg>
      </div>

      {!hideDetail && selComp && (
        <div className="ddetail">
          <div className="full row between"><strong>{selComp.id} · {selComp.name}</strong>{badges?.[selComp.id] ? <span className="pill red">{badges[selComp.id]} {badgeLabel}</span> : null}</div>
          <Field k="Tür" v={COMPONENT_TYPE_LABEL[selComp.type]} />
          <Field k="Bölge" v={ZONE_LABEL[selComp.zone]} />
          <Field k="İnternete açık" v={selComp.internetFacing === null ? 'Bilinmiyor' : selComp.internetFacing ? 'Evet' : 'Hayır'} />
          <Field k="Kimlik doğrulama" v={AUTH_LABEL[selComp.authentication]} />
          <Field k="Teknoloji" v={selComp.technology ?? '–'} />
          <Field k="Veri sınıfları" v={selComp.dataClasses.map((d) => DATA_CLASS_LABEL[d]).join(', ') || 'Yok'} />
          <Field k="Bağlı akışlar" v={model.dataFlows.filter((f) => f.source === selComp.id || f.target === selComp.id).map((f) => f.id).join(', ') || 'Yok'} />
          <Field k="Güven" v={selComp.confidence} />
          {selComp.evidence && <Field full k={`Kanıt · ${selComp.evidence.documentId}#${selComp.evidence.section}`} v={`“${selComp.evidence.quote}”`} />}
        </div>
      )}
      {!hideDetail && selFlow && (
        <div className="ddetail">
          <div className="full row between"><strong>{selFlow.id} · {names[selFlow.source]} → {names[selFlow.target]}</strong>{badges?.[selFlow.id] ? <span className="pill red">{badges[selFlow.id]} {badgeLabel}</span> : null}</div>
          {selFlow.description && <Field full k="Açıklama" v={selFlow.description} />}
          <Field k="Protokol" v={selFlow.protocol ?? '–'} />
          <Field k="Kimlik doğrulama" v={AUTH_LABEL[selFlow.authentication]} />
          <Field k="Şifreli" v={selFlow.encrypted === null ? 'Bilinmiyor' : selFlow.encrypted ? 'Evet' : 'Hayır'} />
          <Field k="Güven sınırı" v={(selFlow.crossesTrustBoundary ?? boxes[selFlow.source]?.zone !== boxes[selFlow.target]?.zone) ? 'Geçiyor' : 'Bölge içi'} />
          <Field k="Veri sınıfları" v={selFlow.dataClasses.map((d) => DATA_CLASS_LABEL[d]).join(', ') || 'Yok'} />
          {selFlow.evidence && <Field full k={`Kanıt · ${selFlow.evidence.documentId}#${selFlow.evidence.section}`} v={`“${selFlow.evidence.quote}”`} />}
        </div>
      )}
      {!hideDetail && !selComp && !selFlow && <span className="small muted no-print">Bir bileşene ya da akışa tıklayarak ayrıntısını görün; üzerine gelince bağlantıları vurgulanır.</span>}
    </div>
  );
}

function Badge({ x, y, n }: { x: number; y: number; n: number }) {
  return (
    <g transform={`translate(${x}, ${y})`}>
      <circle r="10" fill="#c8102e" stroke="#fff" strokeWidth="2" />
      <text y="3.8" fontSize="10.5" fontWeight="700" fill="#fff" textAnchor="middle">{n > 99 ? '99+' : n}</text>
    </g>
  );
}

function Field({ k, v, full }: { k: string; v: string; full?: boolean }) {
  return (
    <div className={full ? 'full' : undefined}>
      <div className="k">{k}</div>
      <div>{v}</div>
    </div>
  );
}
