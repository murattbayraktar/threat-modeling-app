import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { LlmMode, Sample } from '../api/types';
import { extractInBrowser, extractionConfigFromMeta, needsBrowserExtraction } from '../lib/browserExtract';
import { PROJECT_TYPE_LABEL } from '../lib/labels';
import { useMeta } from '../lib/useMeta';

interface FileEntry {
  file: File;
  status: 'waiting' | 'extracting' | 'done' | 'skipped' | 'error';
  message?: string;
  text?: string;
  method?: string;
}

export default function NewReview() {
  const meta = useMeta();
  const nav = useNavigate();
  const [title, setTitle] = useState('');
  const [businessUnit, setBusinessUnit] = useState('');
  const [owner, setOwner] = useState('');
  const [projectType, setProjectType] = useState('NEW_APPLICATION');
  const [dataClassification, setDataClassification] = useState('GIZLI');
  const [answers, setAnswers] = useState({ internetFacing: true, personalData: true, thirdParty: false, authMethod: 'UNKNOWN' });
  const [llmMode, setLlmMode] = useState<LlmMode>('SERVER');
  const [browserExtract, setBrowserExtract] = useState(true);
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [over, setOver] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [preview, setPreview] = useState<FileEntry | null>(null);

  useEffect(() => {
    api.samples().then(setSamples).catch(() => setSamples([]));
  }, []);
  useEffect(() => {
    if (meta) setLlmMode(meta.llm.defaultMode);
  }, [meta]);
  const xcfg = extractionConfigFromMeta(meta?.extraction);
  const extractionAvailable = xcfg.pdfText || xcfg.ocr;
  const useBrowserExtract = browserExtract && extractionAvailable;
  const extractLabel = [xcfg.pdfText && 'PDF metni (pdf.js)', xcfg.ocr && `görsel ve taranmış sayfalarda OCR (tesseract.js · ${xcfg.languages})`]
    .filter(Boolean).join(' + ');

  function addFiles(list: FileList | null) {
    if (!list) return;
    const added = Array.from(list).map((file) => ({ file, status: 'waiting' as const }));
    setFiles((prev) => [...prev, ...added]);
  }

  function update(i: number, patch: Partial<FileEntry>) {
    setFiles((prev) => prev.map((f, idx) => (idx === i ? { ...f, ...patch } : f)));
  }

  async function runExtraction(list: FileEntry[]): Promise<Record<string, { text: string; method: string }>> {
    const out: Record<string, { text: string; method: string }> = {};
    for (let i = 0; i < list.length; i++) {
      const f = list[i];
      if (f.status === 'done' && f.text) {
        out[f.file.name] = { text: f.text, method: f.method! };
        continue;
      }
      if (!useBrowserExtract || !needsBrowserExtraction(f.file, xcfg)) {
        update(i, { status: 'skipped', message: 'Sunucuda ayrıştırılacak' });
        continue;
      }
      update(i, { status: 'extracting', message: 'Başlıyor…' });
      try {
        const r = await extractInBrowser(f.file, (m, ratio) =>
          update(i, { message: `${m}${ratio !== undefined ? ` %${Math.round(ratio * 100)}` : ''}` }), xcfg);
        if (r && r.text.trim()) {
          out[f.file.name] = { text: r.text, method: r.method };
          update(i, { status: 'done', text: r.text, method: r.method, message: `${r.text.length} karakter (${r.method})` });
        } else {
          update(i, { status: 'done', message: 'Metin bulunamadı' });
        }
      } catch (e) {
        update(i, { status: 'error', message: (e as Error).message });
      }
    }
    return out;
  }

  async function submit() {
    setError(null);
    if (!title.trim()) return setError('Proje adı zorunlu');
    if (files.length === 0) return setError('En az bir doküman ekleyin');
    setBusy(true);
    try {
      const clientTexts = await runExtraction(files);
      const form = new FormData();
      form.append('meta', JSON.stringify({ title, businessUnit, owner, projectType, dataClassification, llmMode, answers }));
      files.forEach((f) => form.append('files', f.file, f.file.name));
      form.append('clientTexts', JSON.stringify(clientTexts));
      const review = await api.createReview(form);
      nav(`/reviews/${review.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function startSample(s: Sample) {
    setBusy(true);
    setError(null);
    try {
      const clientTexts: Record<string, string> = {};
      if (useBrowserExtract && xcfg.ocr) {
        for (const f of s.files.filter((x) => /\.(png|jpe?g)$/i.test(x.fileName))) {
          const blob = await api.sampleFile(s.name, f.fileName);
          const r = await extractInBrowser(new File([blob], f.fileName, { type: f.contentType }), () => {}, xcfg);
          if (r?.text) clientTexts[f.fileName] = r.text;
        }
      }
      const review = await api.createFromSample(s.name, llmMode, clientTexts);
      nav(`/reviews/${review.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="stack" style={{ gap: 4 }}>
        <h1>Yeni inceleme</h1>
        <span className="muted">Proje bilgilerini girin ve dokümanları yükleyin. Ayrıştırma bitince mimari model taslağı hazırlanır.</span>
      </div>
      {error && <div className="notice error">{error}</div>}
      <div className="split">
        <div className="grow stack">
          <section className="card stack">
            <h2>Proje bilgileri</h2>
            <label className="field">Proje adı<input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Ör. Tedarikçi fatura entegrasyonu" /></label>
            <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
              <label className="field">Talep eden birim<input value={businessUnit} onChange={(e) => setBusinessUnit(e.target.value)} /></label>
              <label className="field">Proje sahibi<input value={owner} onChange={(e) => setOwner(e.target.value)} /></label>
              <label className="field">Proje türü
                <select value={projectType} onChange={(e) => setProjectType(e.target.value)}>
                  {Object.entries(PROJECT_TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </label>
              <label className="field">Veri sınıfı
                <select value={dataClassification} onChange={(e) => setDataClassification(e.target.value)}>
                  <option value="GIZLI">Gizli</option><option value="KISISEL">Kişisel veri</option>
                  <option value="KURUM_ICI">Kurum içi</option><option value="ACIK">Açık</option>
                </select>
              </label>
            </div>
          </section>
          <section className="card stack">
            <h2>Ön sorular</h2>
            <span className="muted small">Cevaplar model çıkarımında bağlam olarak kullanılır ve kurallarda review.answers.* olarak okunabilir.</span>
            {([
              ['internetFacing', 'İnternete açık bir bileşen var mı?'],
              ['personalData', 'Müşteri veya kişisel veri işleniyor mu?'],
              ['thirdParty', 'Üçüncü taraf sistemle bağlantı var mı?'],
            ] as const).map(([k, q]) => (
              <div key={k} className="row between" style={{ borderBottom: '1px solid var(--line-2)', paddingBottom: 8 }}>
                <span>{q}</span>
                <span className="row">
                  <label><input type="radio" checked={answers[k] === true} onChange={() => setAnswers({ ...answers, [k]: true })} /> Evet</label>
                  <label><input type="radio" checked={answers[k] === false} onChange={() => setAnswers({ ...answers, [k]: false })} /> Hayır</label>
                </span>
              </div>
            ))}
            <label className="field">Kimlik doğrulama yöntemi
              <select value={answers.authMethod} onChange={(e) => setAnswers({ ...answers, authMethod: e.target.value })}>
                {['UNKNOWN', 'OAUTH2', 'JWT', 'MTLS', 'SSO', 'API_KEY'].map((a) => <option key={a} value={a}>{a === 'UNKNOWN' ? 'Bilinmiyor' : a}</option>)}
              </select>
            </label>
          </section>
          <section className="card stack">
            <h2>LLM ve metin çıkarımı</h2>
            <div className="row">
              <label className="row"><input type="radio" checked={llmMode === 'SERVER'} onChange={() => setLlmMode('SERVER')} />
                Sunucu ({meta?.llm.serverProvider ?? '…'} · {meta?.llm.serverModel ?? ''})</label>
              <label className="row"><input type="radio" checked={llmMode === 'BROWSER'} disabled={meta?.llm.browserEnabled === false} onChange={() => setLlmMode('BROWSER')} />
                Tarayıcı (WebLLM, WebGPU gerekir)</label>
            </div>
            {extractionAvailable ? (
              <label className="row"><input type="checkbox" checked={browserExtract} onChange={(e) => setBrowserExtract(e.target.checked)} />
                Metni tarayıcıda çıkar: {extractLabel}</label>
            ) : (
              <span className="small muted">Tarayıcıda metin çıkarımı ve OCR sistem parametrelerinde kapalı; dokümanlar yalnızca sunucuda ayrıştırılır.</span>
            )}
          </section>
        </div>
        <div className="side-panel stack">
          <section className="card stack">
            <div className="row between"><h2 style={{ margin: 0 }}>Dokümanlar</h2><span className="muted small">{files.length} dosya</span></div>
            <div
              className={'drop' + (over ? ' over' : '')}
              onDragOver={(e) => { e.preventDefault(); setOver(true); }}
              onDragLeave={() => setOver(false)}
              onDrop={(e) => { e.preventDefault(); setOver(false); addFiles(e.dataTransfer.files); }}
            >
              <div style={{ fontWeight: 600 }}>Dosyaları buraya sürükleyin</div>
              <div className="muted small" style={{ margin: '6px 0 10px' }}>Word (.docx), PDF, Markdown, metin, draw.io, görsel. Diyagramlar için düzenlenebilir kaynak (draw.io) önerilir.</div>
              <label className="btn">Dosya seç<input type="file" multiple hidden onChange={(e) => addFiles(e.target.files)} /></label>
            </div>
            {files.map((f, i) => (
              <div key={i} className="row between" style={{ border: '1px solid var(--line)', borderRadius: 10, padding: 10 }}>
                <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                  <strong style={{ fontSize: 14, overflowWrap: 'anywhere' }}>{f.file.name}</strong>
                  <span className="small muted">{(f.file.size / 1024).toFixed(0)} KB {f.message ? `· ${f.message}` : ''}</span>
                </div>
                <div className="row" style={{ gap: 6 }}>
                  {f.text && <button className="btn small" onClick={() => setPreview(f)}>Metin</button>}
                  <button className="btn small danger" onClick={() => setFiles(files.filter((_, idx) => idx !== i))} aria-label="Kaldır">Kaldır</button>
                </div>
              </div>
            ))}
            <div className="notice info small">Dokümanlar banka içinde işlenir. Doküman içeriği LLM'e yalnızca bilgi kaynağı olarak, talimatlardan ayrılmış biçimde verilir.</div>
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <button className="btn primary" disabled={busy} onClick={submit}>{busy ? 'Hazırlanıyor…' : 'İncelemeyi başlat'}</button>
            </div>
          </section>
          {samples.length > 0 && (
            <section className="card stack">
              <h2 style={{ margin: 0 }}>Örnek projeler</h2>
              <span className="muted small">datasets/sample-projects altındaki örneklerle akışı deneyin. Seçili LLM modu kullanılır.</span>
              {samples.map((s) => (
                <div key={s.name} className="row between" style={{ borderTop: '1px solid var(--line-2)', paddingTop: 8 }}>
                  <div className="stack" style={{ gap: 2 }}>
                    <strong style={{ fontSize: 14 }}>{s.intake.title ?? s.name}</strong>
                    <span className="small muted">{s.files.map((f) => f.fileName).join(', ')}</span>
                  </div>
                  <button className="btn small" disabled={busy} onClick={() => startSample(s)}>Başlat</button>
                </div>
              ))}
            </section>
          )}
        </div>
      </div>
      {preview && (
        <div className="card stack">
          <div className="row between"><h2 style={{ margin: 0 }}>{preview.file.name} – tarayıcıda çıkarılan metin</h2><button className="btn small" onClick={() => setPreview(null)}>Kapat</button></div>
          <pre className="code">{preview.text}</pre>
        </div>
      )}
    </>
  );
}
