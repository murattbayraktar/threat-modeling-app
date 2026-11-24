// Tarayıcı LLM'i (WebLLM + WebGPU). Backend istemi ve JSON şemasını hazırlar; burada yalnızca model çalıştırılır.
// Model dosyaları varsayılan olarak WebLLM'in kendi adreslerinden indirilir. Bulut erişimi yoksa
// public/webllm-config.json (örneği: webllm-config.example.json) ile kurum içi adresler tanımlanmalıdır.

import type { LlmTask } from '../api/types';

type Engine = {
  chat: { completions: { create: (req: unknown) => Promise<{ choices: { message: { content: string | null } }[] }> } };
  unload: () => Promise<void>;
};

let engine: Engine | null = null;
let configUrl = '/webllm-config.json';

/** Sistem parametrelerindeki model listesi adresi (/api/meta → llm.browserConfigUrl). */
export function setWebLlmConfigUrl(url?: string) {
  if (url) configUrl = url;
}
let loadedModel: string | null = null;

export function webGpuAvailable(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.gpu;
}

async function customAppConfig(): Promise<unknown | undefined> {
  try {
    const res = await fetch(configUrl, { cache: 'no-store' });
    if (!res.ok) return undefined;
    const json = await res.json();
    return json?.model_list ? json : undefined;
  } catch {
    return undefined;
  }
}

export async function availableModels(): Promise<string[]> {
  const custom = (await customAppConfig()) as { model_list: { model_id: string }[] } | undefined;
  if (custom) return custom.model_list.map((m) => m.model_id);
  const webllm = await import('@mlc-ai/web-llm');
  return webllm.prebuiltAppConfig.model_list
    .map((m) => m.model_id)
    .filter((id) => /Instruct/.test(id) && /q4f16_1/.test(id) && !/Coder|Math/.test(id))
    .filter((id) => /Qwen2\.5-(1\.5B|3B|7B)|Llama-3\.[12]-(3B|8B)|Phi-3\.5|gemma-2-(2b|9b)/i.test(id));
}

export async function loadModel(modelId: string, onProgress: (text: string, ratio: number) => void): Promise<void> {
  if (engine && loadedModel === modelId) return;
  if (engine) await engine.unload();
  const webllm = await import('@mlc-ai/web-llm');
  const appConfig = await customAppConfig();
  engine = (await webllm.CreateMLCEngine(modelId, {
    appConfig: appConfig as never,
    initProgressCallback: (r: { text: string; progress: number }) => onProgress(r.text, r.progress),
  })) as unknown as Engine;
  loadedModel = modelId;
}

export function loadedModelId() {
  return loadedModel;
}

/** Görevi yerel modelde çalıştırır; JSON şeması WebLLM'in kısıtlı üretim (XGrammar) özelliğiyle zorlanır. */
export async function runTask(task: LlmTask): Promise<{ output: string; durationMs: number }> {
  if (!engine) throw new Error('Model yüklenmedi');
  const start = performance.now();
  const reply = await engine.chat.completions.create({
    messages: [
      { role: 'system', content: task.systemPrompt },
      { role: 'user', content: task.userPrompt },
    ],
    temperature: task.temperature,
    response_format: { type: 'json_object', schema: JSON.stringify(stripMeta(task.schema)) },
  });
  return { output: reply.choices[0]?.message?.content ?? '', durationMs: Math.round(performance.now() - start) };
}

function stripMeta(schema: unknown): unknown {
  if (!schema || typeof schema !== 'object') return schema;
  const { $schema: _s, $id: _i, title: _t, ...rest } = schema as Record<string, unknown>;
  void _s; void _i; void _t;
  return rest;
}
