import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { LlmTask } from '../api/types';
import { useMeta } from '../lib/useMeta';
import { availableModels, loadModel, loadedModelId, runTask, webGpuAvailable } from '../lib/webllm';

/**
 * Tarayıcı LLM modu: backend'in hazırladığı istemi yerel modelde (WebLLM) çalıştırır ve çıktıyı geri gönderir.
 * WebGPU yoksa istemi kopyalayıp başka bir araçta çalıştırarak çıktıyı elle yapıştırma seçeneği de vardır.
 */
export default function BrowserLlmPanel({ reviewId, onChanged }: { reviewId: string; onChanged: () => void }) {
  const meta = useMeta();
  const [task, setTask] = useState<LlmTask | null>(null);
  const [models, setModels] = useState<string[]>([]);
  const [model, setModel] = useState<string>('');
  const [progress, setProgress] = useState<{ text: string; ratio: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [auto, setAuto] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [manual, setManual] = useState('');
  const runningRef = useRef(false);
  const gpu = webGpuAvailable();

  useEffect(() => {
    availableModels().then((list) => {
      setModels(list);
      const preferred = meta?.llm.browserDefaultModel;
      setModel(list.includes(preferred ?? '') ? preferred! : list[0] ?? '');
    }).catch((e) => setError(e.message));
  }, [meta]);

  useEffect(() => {
    api.llmTasks(reviewId).then((t) => setTask(t[0] ?? null));
  }, [reviewId]);

  async function runOnce() {
    if (runningRef.current) return;
    runningRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await loadModel(model, (text, ratio) => setProgress({ text, ratio }));
      setProgress({ text: 'Model hazır', ratio: 1 });
      let current = (await api.llmTasks(reviewId))[0] ?? null;
      while (current) {
        setTask(current);
        setProgress({ text: `${current.stage} · ${current.callKey} çalıştırılıyor (deneme ${current.attempt})`, ratio: 1 });
        const { output, durationMs } = await runTask(current);
        await api.submitLlmResult(current.callId, output, model, durationMs);
        onChanged();
        if (!auto) break;
        await new Promise((r) => setTimeout(r, 800));
        current = (await api.llmTasks(reviewId))[0] ?? null;
      }
      setTask(current);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      runningRef.current = false;
      setBusy(false);
    }
  }

  async function submitManual() {
    if (!task || !manual.trim()) {
      setError('Önce modelin JSON çıktısını yapıştırın');
      return;
    }
    try {
      await api.submitLlmResult(task.callId, manual, 'manuel', 0);
      setManual('');
      onChanged();
      setTask((await api.llmTasks(reviewId))[0] ?? null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function switchToServer() {
    await api.setLlmMode(reviewId, 'SERVER');
    onChanged();
  }

  return (
    <div className="card stack" style={{ borderColor: 'var(--brand-line)', borderLeft: '3px solid var(--brand)' }}>
      <div className="row between">
        <h2 style={{ margin: 0 }}>Tarayıcı LLM görevi</h2>
        <span className="pill purple">WebLLM · WebGPU</span>
      </div>
      {task ? (
        <p className="muted small" style={{ margin: 0 }}>
          Bekleyen görev: <strong>{task.stage}</strong> · {task.callKey} · deneme {task.attempt} · istem {task.promptVersion}
        </p>
      ) : (
        <p className="muted small" style={{ margin: 0 }}>Bekleyen görev yok.</p>
      )}
      {!gpu && (
        <div className="notice warn">
          Bu tarayıcıda WebGPU yok. Güncel Chrome/Edge kullanın, aşağıdan çıktıyı elle yapıştırın ya da sunucu LLM'ine geçin.
        </div>
      )}
      <div className="row">
        <label className="field" style={{ minWidth: 280 }}>
          Model
          <select value={model} onChange={(e) => setModel(e.target.value)} disabled={busy}>
            {models.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </label>
        <label className="row small" style={{ marginTop: 18 }}>
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> Sıradaki görevleri otomatik çalıştır
        </label>
      </div>
      <div className="row">
        <button className="btn primary" disabled={!gpu || busy || !task || !model} onClick={runOnce}>
          {busy ? 'Çalışıyor…' : loadedModelId() === model ? 'Görevi çalıştır' : 'Modeli yükle ve çalıştır'}
        </button>
        <button className="btn" onClick={switchToServer} disabled={busy}>Sunucu LLM'ine geç</button>
      </div>
      {progress && (
        <div className="stack" style={{ gap: 4 }}>
          <div className="pbar" style={{ height: 6, background: 'var(--line)', borderRadius: 3 }}>
            <div style={{ width: `${Math.round(progress.ratio * 100)}%`, height: 6, background: 'var(--brand)', borderRadius: 3 }} />
          </div>
          <span className="small muted">{progress.text}</span>
        </div>
      )}
      {error && <div className="notice error">{error}</div>}
      {task && (
        <details>
          <summary className="small">İstemi gör / çıktıyı elle gir</summary>
          <div className="stack" style={{ marginTop: 8 }}>
            <pre className="code">{task.systemPrompt + '\n\n' + task.userPrompt}</pre>
            <textarea rows={6} placeholder="Modelin JSON çıktısını buraya yapıştırın" value={manual} onChange={(e) => setManual(e.target.value)} />
            <div>
              <button className="btn" onClick={submitManual}>Çıktıyı gönder</button>
            </div>
          </div>
        </details>
      )}
      <p className="small muted" style={{ margin: 0 }}>
        Çıktı backend'de JSON Schema ile doğrulanır; uymazsa hatalar istemeye eklenerek yeni deneme oluşturulur.
      </p>
    </div>
  );
}
