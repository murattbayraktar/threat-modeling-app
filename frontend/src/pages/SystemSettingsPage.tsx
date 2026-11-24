import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import type { ModelListResponse, SettingChange, SettingsResponse, SettingsTestResult, SystemSettings } from '../api/types';
import { fmtDate } from '../lib/labels';

/*
 * Sistem parametreleri: sunucu LLM'i (adres, model, token, adım bazında model), embedding (RAG), tarayıcı LLM'i,
 * doküman metin çıkarımı / OCR ve akış ayarları. Kaydedilen değerler uygulama yeniden başlatılmadan devreye girer.
 * API anahtarları ekrana geri gelmez: boş bırakılırsa mevcut anahtar korunur, "Anahtarı sil" ile silinir.
 */

const CLEAR = '__CLEAR__';
const TASKS: { id: string; label: string; hint: string }[] = [
  { id: 'MODEL_EXTRACTION', label: 'Model çıkarma', hint: 'Dokümandan mimari modeli (JSON) çıkarır. Uzun bağlamlı model önerilir.' },
  { id: 'THREAT_GENERATION', label: 'Tehdit üretimi', hint: 'Adaylardan tehdit metni ve öneri üretir. Muhakemesi güçlü model önerilir.' },
  { id: 'DOCUMENT_VISION', label: 'Görsel / diyagram analizi', hint: 'Doküman görselleri ve diyagramlar için görsel destekli (vision) model. Görsel analiz adımı eklendiğinde kullanılır; şimdilik akışta çağrılmaz.' },
];

/** Sunucudaki EndpointPolicy.isInternal ile aynı mantık: dış adresler "dış erişim" olarak işaretlenir. */
function isExternalUrl(url: string, suffixes: string[]): boolean {
  let host: string;
  try { host = new URL(url.trim()).hostname.toLowerCase(); } catch { return false; }
  if (!host) return false;
  if (host.startsWith('[') && host.endsWith(']')) host = host.slice(1, -1);
  if (host === 'localhost' || host.endsWith('.localhost')) return false;
  const m = host.match(/^(\d+)\.(\d+)\.\d+\.\d+$/);
  if (m) {
    const a = +m[1], b = +m[2];
    return !(a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254));
  }
  if (host.includes(':')) return !(host === '::1' || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80'));
  if (!host.includes('.')) return false;
  return !suffixes.some((x) => { const sx = x.startsWith('.') ? x : '.' + x; return host.endsWith(sx) || host === sx.slice(1); });
}
const SECTIONS = [
  ['llm', 'Sunucu LLM'],
  ['embedding', 'Embedding (RAG)'],
  ['browser', 'Tarayıcı LLM'],
  ['ocr', 'Doküman ve OCR'],
  ['pipeline', 'Akış ayarları'],
  ['history', 'Değişiklik geçmişi'],
] as const;

type Secrets = { llm: string; embedding: string; tasks: Record<string, string> };
type Catalog = ModelListResponse | 'loading';

export default function SystemSettingsPage() {
  const [data, setData] = useState<SettingsResponse | null>(null);
  const [draft, setDraft] = useState<SystemSettings | null>(null);
  const [secrets, setSecrets] = useState<Secrets>({ llm: '', embedding: '', tasks: {} });
  const [history, setHistory] = useState<SettingChange[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [catalogs, setCatalogs] = useState<Record<string, Catalog>>({});
  const [tests, setTests] = useState<Record<string, SettingsTestResult[] | 'running'>>({});

  function load(r: SettingsResponse) {
    setData(r);
    setDraft(structuredClone(r.settings));
    setSecrets({ llm: '', embedding: '', tasks: {} });
    setCatalogs({});
  }

  useEffect(() => {
    api.settings().then(load).catch((e) => setError(e.message));
    api.settingsHistory().then(setHistory).catch(() => setHistory([]));
  }, []);

  const payload = useMemo<SystemSettings | null>(() => {
    if (!draft) return null;
    const conns = Object.fromEntries(Object.entries(draft.llm.taskConnections ?? {}).map(([t, c]) => [t, { baseUrl: c.baseUrl, apiKey: secrets.tasks[t] ?? '' }]));
    return {
      ...draft,
      llm: { ...draft.llm, apiKey: secrets.llm, taskConnections: conns },
      embedding: { ...draft.embedding, apiKey: secrets.embedding },
    };
  }, [draft, secrets]);

  const dirty = !!data && !!draft && (JSON.stringify(draft) !== JSON.stringify(data.settings) || secrets.llm !== '' || secrets.embedding !== '' || Object.values(secrets.tasks).some((v) => v !== ''));

  if (error && !data) return <div className="notice error">{error}</div>;
  if (!data || !draft || !payload) return <div className="muted">Yükleniyor…</div>;
  const ro = !data.canEdit;
  const d = draft;

  function set<K extends keyof SystemSettings>(k: K, patch: Partial<SystemSettings[K]>) {
    setDraft((prev) => (prev ? { ...prev, [k]: { ...prev[k], ...patch } } : prev));
    setSaved(null);
  }

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const r = await api.saveSettings(payload!);
      load(r);
      setSaved('Kaydedildi; yeni değerler hemen devreye girdi.');
      api.settingsHistory().then(setHistory);
      window.dispatchEvent(new Event('tm-meta-refresh'));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function reset() {
    if (!confirm('Ekrandan kaydedilen tüm değerler silinip application.yml değerlerine dönülecek. Devam edilsin mi?')) return;
    setBusy(true);
    try {
      load(await api.resetSettings());
      setSaved('application.yml değerlerine dönüldü.');
      api.settingsHistory().then(setHistory);
      window.dispatchEvent(new Event('tm-meta-refresh'));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function test(target: 'llm' | 'embedding') {
    setTests((t) => ({ ...t, [target]: 'running' }));
    try {
      const r = await api.testSettings(target, payload!);
      setTests((t) => ({ ...t, [target]: r }));
    } catch (e) {
      setTests((t) => ({ ...t, [target]: [{ target, model: '', ok: false, message: (e as Error).message, durationMs: 0 }] }));
    }
  }

  const openAi = d.llm.provider === 'openai-compatible';
  const suffixes = data.policy.internalHostSuffixes;

  async function fetchModels(scope: string) {
    setCatalogs((c) => ({ ...c, [scope]: 'loading' }));
    try {
      const r = await api.listModels(scope === 'default' ? null : scope, payload!);
      setCatalogs((c) => ({ ...c, [scope]: r }));
    } catch (e) {
      setCatalogs((c) => ({ ...c, [scope]: { ok: false, models: [], message: (e as Error).message } }));
    }
  }

  function setConn(task: string, patch: Partial<{ baseUrl: string }> | null) {
    setDraft((prev) => {
      if (!prev) return prev;
      const conns = { ...(prev.llm.taskConnections ?? {}) };
      if (patch === null) delete conns[task];
      else conns[task] = { ...(conns[task] ?? { baseUrl: '', apiKey: '' }), ...patch };
      return { ...prev, llm: { ...prev.llm, taskConnections: conns } };
    });
    if (patch === null) setSecrets((s) => { const t = { ...s.tasks }; delete t[task]; return { ...s, tasks: t }; });
    setSaved(null);
  }

  return (
    <>
      <div className="row between">
        <div className="stack" style={{ gap: 4 }}>
          <h1>Sistem parametreleri</h1>
          <span className="muted">LLM ve embedding bağlantıları, adım bazında model, OCR ve akış ayarları. Kaydedilen değerler yeniden başlatmadan devreye girer.</span>
        </div>
        <div className="row">
          <span className="pill gray">Kaynak: {data.source}</span>
          <span className="pill gray">Aktif LLM: {data.active.llm}</span>
          <span className="pill gray">Arama: {data.active.retrieval}</span>
        </div>
      </div>

      {ro && <div className="notice warn">Salt okunur görünüm: sistem parametrelerini değiştirme yetkiniz yok. Yetki, sunucudaki tm.security.settings-admins listesiyle verilir.</div>}
      {!data.policy.encryptionEnabled && (
        <div className="notice warn"><strong>Şifreleme parolası tanımlı değil.</strong> API anahtarları veritabanına şifrelenmeden yazılır. Canlı ortamda sunucuya <span className="mono">TM_SETTINGS_KEY</span> ortam değişkeni (kasadan) verilmelidir.</div>
      )}
      {!data.adminsConfigured && !ro && (
        <div className="notice info">Yönetici listesi tanımlı değil; şu an herkes bu ayarları değiştirebilir. Canlıya çıkmadan önce <span className="mono">tm.security.settings-admins</span> doldurulmalı.</div>
      )}

      <div className="settings-layout">
        <nav className="settings-nav" aria-label="Bölümler">
          {SECTIONS.map(([id, label]) => <a key={id} href={`#s-${id}`}>{label}</a>)}
        </nav>

        <div className="stack settings-body">
          {/* ------------------------------------------------------------ Sunucu LLM */}
          <section className="card stack" id="s-llm">
            <SectionHead title="Sunucu LLM" desc="Model çıkarma ve tehdit üretimi adımlarında sunucunun çağırdığı LLM. OpenAI uyumlu herhangi bir uç nokta: kurum içi gen AI ortamı ya da dış servisler (OpenAI vb.)." />
            <div className="choice-row">
              <Choice on={d.llm.provider === 'mock'} disabled={ro} title="Mock (demo)" desc="Ağa çıkmaz; anahtar kelimeye dayalı deneme sağlayıcısı." onClick={() => set('llm', { provider: 'mock' })} />
              <Choice on={openAi} disabled={ro} title="OpenAI uyumlu uç nokta" desc="Kurum içi gen AI ya da dış servis; /v1/chat/completions, structured output desteklenir." onClick={() => set('llm', { provider: 'openai-compatible' })} />
            </div>
            {openAi && (
              <>
                <div className="form-grid two">
                  <Field label={<>Uç nokta adresi (base URL) <ExternalPill url={d.llm.baseUrl} suffixes={suffixes} /></>} hint={policyHint(data)}>
                    <input disabled={ro} value={d.llm.baseUrl} placeholder="https://genai.bank.local/v1 veya https://api.openai.com/v1" onChange={(e) => set('llm', { baseUrl: e.target.value })} />
                  </Field>
                  <Field label="Varsayılan model" hint="Adım bazında model seçilmezse bu model kullanılır.">
                    <ModelPicker disabled={ro} value={d.llm.model} placeholder="ör. kurum-ici-llm-70b" catalog={catalogs.default}
                      canFetch={!!d.llm.baseUrl.trim()} onChange={(v) => set('llm', { model: v })} onFetch={() => fetchModels('default')} listId="models-default" />
                  </Field>
                  <SecretField label="API anahtarı / token" status={data.secrets['llm.apiKey']} value={secrets.llm} disabled={ro}
                    onChange={(v) => setSecrets((s) => ({ ...s, llm: v }))} />
                  <Field label="Structured output" hint="JSON Schema: sağlayıcı şemayı zorlar (önerilen). JSON modu / Yok: şema isteme eklenir, backend yine doğrular.">
                    <select disabled={ro} value={d.llm.structuredOutputMode} onChange={(e) => set('llm', { structuredOutputMode: e.target.value })}>
                      <option value="JSON_SCHEMA">JSON Schema (strict)</option>
                      <option value="JSON_OBJECT">JSON modu</option>
                      <option value="NONE">Yok (şema istemde)</option>
                    </select>
                  </Field>
                  <Field label="Zaman aşımı (saniye)">
                    <input disabled={ro} type="number" min={5} max={900} value={d.llm.timeoutSeconds} onChange={(e) => set('llm', { timeoutSeconds: +e.target.value })} />
                  </Field>
                  <Field label="Deneme sayısı" hint="Şemaya uymayan çıktıda hatalar eklenerek yeniden sorulur.">
                    <input disabled={ro} type="number" min={1} max={5} value={d.llm.maxAttempts} onChange={(e) => set('llm', { maxAttempts: +e.target.value })} />
                  </Field>
                </div>
                {isExternalUrl(d.llm.baseUrl, suffixes) && (
                  <div className="notice small warn">Bu adres kurum dışı. Bu bağlantıyla çağrılan adımlara gönderilen doküman ve model içeriği kurum dışına çıkar; gerçek doküman yüklemeden önce bunu göz önünde bulundurun.</div>
                )}
                <div className="stack" style={{ gap: 8 }}>
                  <span className="field-label">Adım bazında model ve bağlantı</span>
                  <span className="small muted">Her adım için farklı bir model seçebilir, istersen o adım için tamamen ayrı bir bağlantı (adres + token) tanımlayabilirsiniz. Örneğin görsel analiz için dış bir model, diğer adımlar için ucuz/kurum içi bir model.</span>
                  <div className="task-models">
                    {TASKS.map((t) => {
                      const conn = d.llm.taskConnections?.[t.id];
                      const scope = t.id;
                      return (
                        <div key={t.id} className="task-card">
                          <div className="row between">
                            <div><strong>{t.label}</strong><div className="small muted">{t.hint}</div></div>
                            {conn && <ExternalPill url={conn.baseUrl || d.llm.baseUrl} suffixes={suffixes} />}
                          </div>
                          <ModelPicker disabled={ro} value={d.llm.taskModels[t.id] ?? ''} placeholder={`Varsayılan: ${d.llm.model || '–'}`}
                            catalog={catalogs[scope]} canFetch={!!(conn?.baseUrl || d.llm.baseUrl).trim()} listId={`models-${t.id}`}
                            onChange={(v) => set('llm', { taskModels: { ...d.llm.taskModels, [t.id]: v } })} onFetch={() => fetchModels(scope)} />
                          <Toggle disabled={ro} on={!!conn} label="Bu adım için farklı bir bağlantı kullan" onChange={(v) => setConn(t.id, v ? {} : null)} />
                          {conn && (
                            <div className="form-grid two">
                              <Field label="Uç nokta adresi" hint="Boş bırakılırsa varsayılan adres kullanılır. Kendi adresi olan adım varsayılan token'ı kullanmaz; yalnızca buradaki token'ı gönderir.">
                                <input disabled={ro} value={conn.baseUrl} placeholder={d.llm.baseUrl || 'https://api.openai.com/v1'} onChange={(e) => setConn(t.id, { baseUrl: e.target.value })} />
                              </Field>
                              <SecretField label="API anahtarı / token" status={data.secrets[`llm.task.${t.id}.apiKey`]} value={secrets.tasks[t.id] ?? ''} disabled={ro}
                                onChange={(v) => setSecrets((x) => ({ ...x, tasks: { ...x.tasks, [t.id]: v } }))} />
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              </>
            )}
            {!ro && <TestBar onTest={() => test('llm')} state={tests.llm} label="Bağlantıyı test et" note="Kaydetmeden, ekrandaki değerlerle küçük bir JSON isteği gönderir; adım bazındaki modeller de denenir." />}
          </section>

          {/* ------------------------------------------------------------ Embedding */}
          <section className="card stack" id="s-embedding">
            <SectionHead title="Embedding (RAG)" desc="Açıksa bilgi tabanı araması BM25 + vektör benzerliği (hibrit) ile yapılır. Kapalıysa yalnızca BM25 kullanılır; akış etkilenmez." />
            <Toggle disabled={ro} on={d.embedding.enabled} label="Vektör aramasını kullan" onChange={(v) => set('embedding', { enabled: v })} />
            {d.embedding.enabled && (
              <div className="form-grid two">
                <Field label={<>Uç nokta adresi (base URL) <ExternalPill url={d.embedding.baseUrl} suffixes={suffixes} /></>} hint={policyHint(data)}>
                  <input disabled={ro} value={d.embedding.baseUrl} placeholder="https://genai.bank.local/v1" onChange={(e) => set('embedding', { baseUrl: e.target.value })} />
                </Field>
                <Field label="Embedding modeli">
                  <input disabled={ro} value={d.embedding.model} placeholder="ör. bge-m3" onChange={(e) => set('embedding', { model: e.target.value })} />
                </Field>
                <SecretField label="API anahtarı / token" status={data.secrets['embedding.apiKey']} value={secrets.embedding} disabled={ro}
                  onChange={(v) => setSecrets((s) => ({ ...s, embedding: v }))} />
              </div>
            )}
            {!ro && d.embedding.enabled && <TestBar onTest={() => test('embedding')} state={tests.embedding} label="Bağlantıyı test et" note="Örnek bir metnin vektörünü ister." />}
          </section>

          {/* ------------------------------------------------------------ Tarayıcı LLM */}
          <section className="card stack" id="s-browser">
            <SectionHead title="Tarayıcı LLM" desc="Model analistin tarayıcısında (WebLLM + WebGPU) çalışır; istem ve doğrulama yine sunucuda yapılır. Model dosyaları kurum içinden sunulmalıdır." />
            <Toggle disabled={ro} on={d.browserLlm.enabled} label="Tarayıcı LLM modunu kullanılabilir yap" onChange={(v) => set('browserLlm', { enabled: v })} />
            {d.browserLlm.enabled && (
              <div className="form-grid two">
                <Field label="Varsayılan model">
                  <input disabled={ro} value={d.browserLlm.defaultModel} onChange={(e) => set('browserLlm', { defaultModel: e.target.value })} />
                </Field>
                <Field label="Model listesi (webllm-config.json) adresi" hint="Kurum içinde barındırılan model dosyalarının listesi. Varsayılan: /webllm-config.json">
                  <input disabled={ro} value={d.browserLlm.configUrl} onChange={(e) => set('browserLlm', { configUrl: e.target.value })} />
                </Field>
              </div>
            )}
          </section>

          {/* ------------------------------------------------------------ OCR */}
          <section className="card stack" id="s-ocr">
            <SectionHead title="Doküman ve OCR" desc="Word, draw.io ve Markdown her zaman sunucuda okunur. PDF ve görsellerde metnin nerede ve nasıl çıkarılacağını buradan seçin." />
            <Toggle disabled={ro} on={d.extraction.browserPdfText} label="PDF metnini tarayıcıda çıkar (pdf.js)" onChange={(v) => set('extraction', { browserPdfText: v })} />
            <span className="field-label">OCR teknolojisi (görseller ve taranmış PDF'ler)</span>
            <div className="choice-row three">
              <Choice on={d.extraction.ocrMode === 'BROWSER'} disabled={ro} title="Tarayıcıda OCR" desc="tesseract.js; dosya tarayıcıdan çıkmaz, yalnızca metin gönderilir." onClick={() => set('extraction', { ocrMode: 'BROWSER' })} />
              <Choice on={d.extraction.ocrMode === 'DISABLED'} disabled={ro} title="OCR kullanma" desc="Görsellerden metin çıkarılmaz; bileşenler model ekranında elle eklenir." onClick={() => set('extraction', { ocrMode: 'DISABLED' })} />
              <Choice on={false} disabled title="Sunucuda OCR · LandingAI (on-prem)" desc="Faz 2: on-prem kurulum hazır olduğunda eklenecek." badge="Yakında" onClick={() => undefined} />
            </div>
            {d.extraction.ocrMode === 'BROWSER' && (
              <>
                <div className="form-grid two">
                  <Field label="OCR dilleri" hint="tesseract dil kodları, + ile birleştirilir (ör. tur+eng).">
                    <input disabled={ro} value={d.extraction.ocrLanguages} onChange={(e) => set('extraction', { ocrLanguages: e.target.value })} />
                  </Field>
                </div>
                <details className="soft-box">
                  <summary><strong>Kurum içi OCR dosya adresleri</strong> <span className="small muted">(boşsa internetten indirilmeye çalışılır)</span></summary>
                  <div className="form-grid two" style={{ marginTop: 12 }}>
                    <Field label="Worker (worker.min.js)"><input disabled={ro} value={d.extraction.tesseractWorkerPath} placeholder="/ocr/worker.min.js" onChange={(e) => set('extraction', { tesseractWorkerPath: e.target.value })} /></Field>
                    <Field label="Çekirdek (tesseract-core)"><input disabled={ro} value={d.extraction.tesseractCorePath} placeholder="/ocr/core" onChange={(e) => set('extraction', { tesseractCorePath: e.target.value })} /></Field>
                    <Field label="Dil dosyaları (traineddata)"><input disabled={ro} value={d.extraction.tesseractLangPath} placeholder="/ocr/lang" onChange={(e) => set('extraction', { tesseractLangPath: e.target.value })} /></Field>
                  </div>
                </details>
              </>
            )}
          </section>

          {/* ------------------------------------------------------------ Akış */}
          <section className="card stack" id="s-pipeline">
            <SectionHead title="Akış ayarları" desc="Yeni başlayan adımlarda geçerli olur; çalışmakta olan bir adım eski değerlerle tamamlanır." />
            <div className="form-grid three">
              <Field label="Varsayılan LLM modu">
                <select disabled={ro} value={d.pipeline.defaultLlmMode} onChange={(e) => set('pipeline', { defaultLlmMode: e.target.value as SystemSettings['pipeline']['defaultLlmMode'] })}>
                  <option value="SERVER">Sunucu</option>
                  <option value="BROWSER" disabled={!d.browserLlm.enabled}>Tarayıcı</option>
                </select>
              </Field>
              <Field label="Temperature" hint="0–1,5. Düşük değer daha tutarlı çıktı verir.">
                <input disabled={ro} type="number" step={0.05} min={0} max={1.5} value={d.pipeline.temperature} onChange={(e) => set('pipeline', { temperature: +e.target.value })} />
              </Field>
              <div />
              <Field label="Model çıkarma metin sınırı · sunucu" hint="LLM'e gönderilen en fazla karakter.">
                <input disabled={ro} type="number" value={d.pipeline.modelExtractionMaxCharsServer} onChange={(e) => set('pipeline', { modelExtractionMaxCharsServer: +e.target.value })} />
              </Field>
              <Field label="Model çıkarma metin sınırı · tarayıcı">
                <input disabled={ro} type="number" value={d.pipeline.modelExtractionMaxCharsBrowser} onChange={(e) => set('pipeline', { modelExtractionMaxCharsBrowser: +e.target.value })} />
              </Field>
              <div />
              <Field label="Tehdit grubu · sunucu" hint="Tek LLM çağrısında işlenen aday sayısı.">
                <input disabled={ro} type="number" min={1} max={50} value={d.pipeline.threatBatchSizeServer} onChange={(e) => set('pipeline', { threatBatchSizeServer: +e.target.value })} />
              </Field>
              <Field label="Tehdit grubu · tarayıcı">
                <input disabled={ro} type="number" min={1} max={20} value={d.pipeline.threatBatchSizeBrowser} onChange={(e) => set('pipeline', { threatBatchSizeBrowser: +e.target.value })} />
              </Field>
              <div />
              <Field label="RAG: aday başına kaynak">
                <input disabled={ro} type="number" min={1} max={10} value={d.pipeline.retrievalPerCandidate} onChange={(e) => set('pipeline', { retrievalPerCandidate: +e.target.value })} />
              </Field>
              <Field label="RAG: kaynak türü başına üst sınır">
                <input disabled={ro} type="number" min={1} max={10} value={d.pipeline.retrievalMaxPerSource} onChange={(e) => set('pipeline', { retrievalMaxPerSource: +e.target.value })} />
              </Field>
            </div>
          </section>

          {/* ------------------------------------------------------------ Geçmiş */}
          <section className="card stack" id="s-history">
            <SectionHead title="Değişiklik geçmişi" desc="Kim, ne zaman, neyi değiştirdi. Anahtar değerleri geçmişe yazılmaz." />
            {history.length === 0 && <span className="small muted">Henüz ekrandan değişiklik yapılmadı; application.yml değerleri kullanılıyor.</span>}
            {history.map((h, i) => (
              <div key={i} className="hist-row">
                <span className="muted small">{fmtDate(h.changedAt)}</span>
                <strong className="small">{h.changedBy}</strong>
                <span className="small">{h.summary}</span>
              </div>
            ))}
          </section>
        </div>
      </div>

      {!ro && (
        <div className={'save-bar' + (dirty ? ' dirty' : '')}>
          <div className="small">
            {error ? <span style={{ color: 'var(--red)' }}>{error}</span>
              : saved && !dirty ? <span style={{ color: 'var(--green)' }}>{saved}</span>
                : dirty ? 'Kaydedilmemiş değişiklikler var.' : 'Değişiklik yok.'}
          </div>
          <div className="row">
            <button className="btn ghost" disabled={busy} onClick={reset}>Varsayılanlara dön</button>
            <button className="btn" disabled={busy || !dirty} onClick={() => load(data)}>Vazgeç</button>
            <button className="btn primary" disabled={busy || !dirty} onClick={save}>Kaydet ve uygula</button>
          </div>
        </div>
      )}
    </>
  );
}

function policyHint(d: SettingsResponse) {
  if (!d.policy.blockPublicEndpoints) return 'Kurum içi ya da dış adres girilebilir; dış adresler "Dış erişim" olarak işaretlenir.';
  const s = d.policy.internalHostSuffixes;
  return `Yalnızca kurum içi adres: localhost, özel IP'ler${s.length ? ' veya ' + s.join(', ') : ''}. Dış adresler yapılandırmada kapalı.`;
}

function ExternalPill({ url, suffixes }: { url: string; suffixes: string[] }) {
  if (!url.trim() || !isExternalUrl(url, suffixes)) return null;
  return <span className="pill orange" style={{ marginLeft: 6 }}>Dış erişim</span>;
}

/** Model adı girişi: elle yazılabilir; "Modelleri getir" ile uç noktadaki tanımlı modeller listeye gelir. */
function ModelPicker({ value, placeholder, disabled, catalog, canFetch, listId, onChange, onFetch }: {
  value: string; placeholder: string; disabled?: boolean; catalog?: Catalog; canFetch: boolean; listId: string;
  onChange: (v: string) => void; onFetch: () => void;
}) {
  const loading = catalog === 'loading';
  const res = catalog && catalog !== 'loading' ? catalog : null;
  return (
    <div className="stack" style={{ gap: 4 }}>
      <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
        <input disabled={disabled} style={{ flex: 1 }} list={listId} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
        {!disabled && <button type="button" className="btn small" disabled={!canFetch || loading} onClick={onFetch}>{loading ? 'Getiriliyor…' : 'Modelleri getir'}</button>}
      </div>
      {res?.ok && (
        <>
          <datalist id={listId}>{res.models.map((m) => <option key={m} value={m} />)}</datalist>
          <select disabled={disabled} value={res.models.includes(value) ? value : ''} onChange={(e) => e.target.value && onChange(e.target.value)} aria-label="Tanımlı modellerden seç">
            <option value="">{res.models.length} model bulundu – listeden seç…</option>
            {res.models.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </>
      )}
      {res && !res.ok && <span className="field-hint" style={{ color: 'var(--red)' }}>{res.message}</span>}
    </div>
  );
}

function SectionHead({ title, desc }: { title: string; desc: string }) {
  return (
    <div className="stack" style={{ gap: 2 }}>
      <h2 style={{ margin: 0 }}>{title}</h2>
      <span className="small muted">{desc}</span>
    </div>
  );
}

function Field({ label, hint, children }: { label: React.ReactNode; hint?: string; children: React.ReactNode }) {
  return (
    <label className="field">
      {label}
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

function Toggle({ on, label, disabled, onChange }: { on: boolean; label: string; disabled?: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className={'switch' + (disabled ? ' disabled' : '')}>
      <input type="checkbox" role="switch" checked={on} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="track" aria-hidden="true"><span className="thumb" /></span>
      <span>{label}</span>
    </label>
  );
}

function Choice({ on, title, desc, badge, disabled, onClick }: { on: boolean; title: string; desc: string; badge?: string; disabled?: boolean; onClick: () => void }) {
  return (
    <button type="button" className={'choice' + (on ? ' on' : '')} disabled={disabled} aria-pressed={on} onClick={onClick}>
      <span className="choice-dot" aria-hidden="true" />
      <span className="stack" style={{ gap: 2 }}>
        <strong>{title} {badge && <span className="pill gray" style={{ marginLeft: 4 }}>{badge}</span>}</strong>
        <span className="small muted">{desc}</span>
      </span>
    </button>
  );
}

function SecretField({ label, status, value, disabled, onChange }: {
  label: string; status?: { set: boolean; hint: string }; value: string; disabled?: boolean; onChange: (v: string) => void;
}) {
  const clearing = value === CLEAR;
  return (
    <div className="field">
      <span className="row between" style={{ gap: 6 }}>
        <span>{label}</span>
        {clearing ? <span className="pill red">Silinecek</span>
          : value ? <span className="pill orange">Yeni anahtar girildi</span>
            : status?.set ? <span className="pill green">Kayıtlı {status.hint}</span> : <span className="pill gray">Kayıtlı değil</span>}
      </span>
      <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
        <input type="password" autoComplete="new-password" disabled={disabled || clearing} style={{ flex: 1 }}
          value={clearing ? '' : value} placeholder={status?.set ? 'Değiştirmek için yeni anahtarı yazın' : 'Anahtarı yazın'}
          onChange={(e) => onChange(e.target.value)} />
        {status?.set && !disabled && (
          <button type="button" className="btn small" onClick={() => onChange(clearing ? '' : CLEAR)}>{clearing ? 'Geri al' : 'Anahtarı sil'}</button>
        )}
      </div>
      <span className="field-hint">Anahtar ekrana geri gelmez; veritabanında şifreli saklanır. Boş bırakılırsa mevcut anahtar korunur.</span>
    </div>
  );
}

function TestBar({ onTest, state, label, note }: { onTest: () => void; state?: SettingsTestResult[] | 'running'; label: string; note: string }) {
  return (
    <div className="test-bar">
      <div className="row between">
        <span className="small muted">{note}</span>
        <button className="btn small" disabled={state === 'running'} onClick={onTest}>{state === 'running' ? 'Deneniyor…' : label}</button>
      </div>
      {Array.isArray(state) && state.map((r, i) => (
        <div key={i} className={'notice small ' + (r.ok ? 'ok' : 'error')}>
          <strong>{r.ok ? 'Başarılı' : 'Başarısız'}</strong>{r.scope ? ` · ${TASKS.find((x) => x.id === r.scope)?.label ?? r.scope}` : ''}{r.model ? ` · ${r.model}` : ''}{r.durationMs ? ` · ${r.durationMs} ms` : ''} – {r.message}
        </div>
      ))}
    </div>
  );
}
