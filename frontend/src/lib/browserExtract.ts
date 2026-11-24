// Tarayıcıda metin çıkarımı: PDF için pdf.js, görseller ve taranmış PDF'ler için tesseract.js (OCR).
// Dosya içeriği tarayıcıdan dışarı çıkmaz; yalnızca çıkarılan metin backend'e gönderilir.

export type ExtractionMethod = 'browser-pdfjs' | 'browser-ocr' | 'browser-pdfjs+ocr';

export interface ExtractionResult {
  text: string;
  method: ExtractionMethod;
  pages?: number;
}

type Progress = (message: string, ratio?: number) => void;

const MIN_PDF_TEXT = 80;
const IMAGE = /\.(png|jpe?g|gif|bmp|webp|tiff?)$/;

/** Sistem parametreleri ekranından yönetilir (/api/meta → extraction). Ortam değişkenleri yalnızca yedek değerdir. */
export interface ExtractionConfig {
  pdfText: boolean;
  ocr: boolean;
  languages: string;
  workerPath?: string;
  corePath?: string;
  langPath?: string;
}

export const DEFAULT_EXTRACTION: ExtractionConfig = {
  pdfText: true,
  ocr: true,
  languages: import.meta.env.VITE_OCR_LANGS || 'tur+eng',
  workerPath: import.meta.env.VITE_TESSERACT_WORKER_PATH,
  corePath: import.meta.env.VITE_TESSERACT_CORE_PATH,
  langPath: import.meta.env.VITE_TESSERACT_LANG_PATH,
};

export function extractionConfigFromMeta(x?: {
  browserPdfText: boolean; ocrMode: string; ocrLanguages: string; tesseractWorkerPath: string; tesseractCorePath: string; tesseractLangPath: string;
}): ExtractionConfig {
  if (!x) return DEFAULT_EXTRACTION;
  return {
    pdfText: x.browserPdfText,
    ocr: x.ocrMode === 'BROWSER',
    languages: x.ocrLanguages || DEFAULT_EXTRACTION.languages,
    workerPath: x.tesseractWorkerPath || DEFAULT_EXTRACTION.workerPath,
    corePath: x.tesseractCorePath || DEFAULT_EXTRACTION.corePath,
    langPath: x.tesseractLangPath || DEFAULT_EXTRACTION.langPath,
  };
}

export function needsBrowserExtraction(file: File, cfg: ExtractionConfig = DEFAULT_EXTRACTION): boolean {
  const n = file.name.toLowerCase();
  return (IMAGE.test(n) && cfg.ocr) || (n.endsWith('.pdf') && (cfg.pdfText || cfg.ocr));
}

export async function extractInBrowser(file: File, onProgress: Progress, cfg: ExtractionConfig = DEFAULT_EXTRACTION): Promise<ExtractionResult | null> {
  const name = file.name.toLowerCase();
  if (name.endsWith('.pdf')) return extractPdf(file, onProgress, cfg);
  if (IMAGE.test(name) && cfg.ocr) {
    const text = await ocrImages([file], onProgress, cfg);
    return { text, method: 'browser-ocr' };
  }
  return null;
}

async function loadPdfJs() {
  const pdfjs = await import('pdfjs-dist');
  const worker = await import('pdfjs-dist/build/pdf.worker.min.mjs?url');
  pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
  return pdfjs;
}

async function extractPdf(file: File, onProgress: Progress, cfg: ExtractionConfig): Promise<ExtractionResult | null> {
  const pdfjs = await loadPdfJs();
  const doc = await pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) }).promise;
  const parts: string[] = [];
  for (let p = 1; p <= doc.numPages; p++) {
    onProgress(`PDF metni okunuyor (${p}/${doc.numPages})`, p / doc.numPages);
    const page = await doc.getPage(p);
    const content = await page.getTextContent();
    const lines: string[] = [];
    let lastY: number | null = null;
    let line = '';
    for (const item of content.items as { str?: string; transform?: number[] }[]) {
      if (item.str === undefined) continue;
      const y = item.transform ? item.transform[5] : null;
      if (lastY !== null && y !== null && Math.abs(y - lastY) > 2) {
        lines.push(line.trim());
        line = '';
      }
      line += item.str + ' ';
      lastY = y;
    }
    if (line.trim()) lines.push(line.trim());
    parts.push(lines.filter(Boolean).join('\n'));
  }
  const text = parts.join('\n\n');
  if (text.replace(/\s/g, '').length >= MIN_PDF_TEXT || !cfg.ocr) {
    return cfg.pdfText || text.trim() ? { text, method: 'browser-pdfjs', pages: doc.numPages } : null;
  }
  // Metin katmanı yok: sayfaları görüntüye çevirip OCR uygula
  const canvases: Blob[] = [];
  for (let p = 1; p <= doc.numPages; p++) {
    onProgress(`Taranmış sayfa görüntüye çevriliyor (${p}/${doc.numPages})`, p / doc.numPages);
    const page = await doc.getPage(p);
    const viewport = page.getViewport({ scale: 2 });
    const canvas = document.createElement('canvas');
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise;
    canvases.push(await new Promise<Blob>((resolve) => canvas.toBlob((b) => resolve(b!), 'image/png')));
  }
  const ocr = await ocrImages(canvases, onProgress, cfg);
  return { text: ocr, method: 'browser-pdfjs+ocr', pages: doc.numPages };
}

async function ocrImages(images: Blob[], onProgress: Progress, cfg: ExtractionConfig): Promise<string> {
  const { createWorker } = await import('tesseract.js');
  const options: Record<string, unknown> = {
    logger: (m: { status: string; progress: number }) => onProgress(`OCR: ${m.status}`, m.progress),
  };
  if (cfg.workerPath) options.workerPath = cfg.workerPath;
  if (cfg.corePath) options.corePath = cfg.corePath;
  if (cfg.langPath) options.langPath = cfg.langPath;
  const worker = await createWorker(cfg.languages, 1, options);
  try {
    const out: string[] = [];
    for (let i = 0; i < images.length; i++) {
      onProgress(`OCR sayfa ${i + 1}/${images.length}`, i / images.length);
      const { data } = await worker.recognize(images[i]);
      out.push(data.text);
    }
    return out.join('\n\n');
  } finally {
    await worker.terminate();
  }
}
