# TM Asistan – Threat Modeling Uygulaması (Faz 1)

Güvenlik ekibine gelen proje dokümanlarından tehdit modeli taslağı üreten, analist onaylı in-house uygulama.
"Threat Modelling Platform – Temel Mimari" dokümanındaki akışı izler; LLM karar verici değil, analiz ve öneri
motorudur. Tüm bileşenler banka içinde çalışır.

```
threat-modeling-app/
├── backend/      Java 21 + Spring Boot 3 (ayrı Maven projesi)
├── frontend/     React 18 + TypeScript + Vite (ayrı npm projesi)
├── contracts/    JSON Schema kontratları (sistem modeli, LLM structured output)
├── datasets/     Kurallar, bilgi tabanı (RAG), örnek projeler, değerlendirme seti
└── docs/         Kural motoru ve genişletme dokümantasyonu
```

## Akış

| # | Adım | Çalıştıran | Çıktı |
| --- | --- | --- | --- |
| 1 | Yükleme | Sistem | İnceleme kaydı, dosyalar `data/uploads` altında |
| 2 | Ayrıştırma | Sistem | Bölüm kimlikli metin (docx, md, txt, draw.io, PDF; görsel/taranmış PDF için tarayıcı OCR metni) |
| 3 | Model çıkarımı | LLM | `contracts/llm/model-extraction.output.schema.json` biçiminde JSON |
| 4 | Model doğrulama | Sistem | Model sürümü: kimlik/akış uçları, kanıt alıntısının dokümanda varlığı, güven sınırları |
| 5 | Model onayı | Analist | Düzeltilmiş ve onaylanmış model (her düzenleme yeni sürüm) |
| 6 | Aday tehditler | Kural motoru | STRIDE-per-element + bağlamsal adaylar (`datasets/rules/threat-rules.json`) |
| 7 | Bilgi getirme (RAG) | Sistem | Her aday için OWASP/STRIDE/MITRE/banka kontrolü/geçmiş inceleme kaynakları |
| 8 | Tehdit üretimi | LLM | `threat-generation.output.schema.json`: projeye uyarlanmış tehdit + kaynaklı önlemler |
| 9 | Tehdit doğrulama | Sistem | Hedef/aday/kaynak kontrolü, tekrar eleme, önlem–kategori uyumu, LLM'in atladığı adaylar |
| 10 | Risk değerlendirmesi | Risk motoru | Olasılık × etki, kural izi (`risk-rules.json`) |
| 11 | Uzman incelemesi | Analist | Kabul / red / yanlış pozitif, risk ve önlem düzenleme |
| 12 | Rapor | Sistem | Özet, STRIDE dağılımı, risk matrisi, aksiyon planı, denetim izi |

Arayüzdeki ilerleme çubuğu bu 12 adımı ve hangi adımda beklendiğini (analist / LLM) gösterir.
Her LLM çağrısından sonra deterministik bir doğrulama, her doğrulamadan sonra bir analist onayı vardır.

## Hızlı başlangıç

Gereksinimler: JDK 21, Maven 3.9+, Node 20+.

```bash
# 1) Backend (varsayılan: mock LLM, SQLite ./data/threat-modeling.db)
cd backend
mvn spring-boot:run

# 2) Frontend (ayrı terminal)
cd frontend
npm install
npm run dev          # http://localhost:5173  (/api → localhost:8080)
```

"Yeni inceleme" ekranındaki **Örnek projeler** ile akış tek tıkla denenebilir. Mock LLM ağ erişimi gerektirmez;
anahtar kelime tabanlıdır ve yalnızca deneme/test içindir.

Tek sunucu olarak çalıştırmak için: `npm run build` sonrası backend `../frontend/dist` klasörünü de sunar
(`tm.frontend-dist`).

## Kurum içi gen AI ortamına bağlama

`backend/src/main/resources/application.yml`:

```yaml
tm:
  llm:
    provider: openai-compatible
    base-url: http://genai.bank.local/v1   # OpenAI uyumlu /chat/completions
    api-key: ${TM_LLM_API_KEY:}
    model: <model-adi>
    structured-output-mode: JSON_SCHEMA     # Ortam desteklemiyorsa JSON_OBJECT veya NONE
```

- `JSON_SCHEMA`: `response_format.json_schema` (strict) ile şema zorlanır.
- `JSON_OBJECT` / `NONE`: şema istemin sonuna eklenir; çıktı yine backend'de doğrulanır.
- Çıktı şemaya uymazsa hatalar istemeye eklenerek bir kez daha denenir (`max-attempts`).
- Embedding uç noktası varsa `tm.embedding.*` doldurulur; RAG hibrit (BM25 + vektör) çalışır. Boşsa yalnız BM25.

Başka bir sağlayıcı için `core/llm/LlmClient` arayüzünün yeni bir uygulaması yazılır.

## Sistem parametreleri ekranı

**Model listesi ve adım bazında bağlantı:** OpenAI uyumlu uç nokta seçilince "Modelleri getir" düğmesi `GET {adres}/models`
ile tanımlı modelleri çeker ve listeden seçtirir (elle yazmak da mümkün). Model çıkarma, tehdit üretimi ve görsel/diyagram
analizi (ayrılmış; akışta henüz çağrılmıyor) adımlarının her birine ayrı model, istenirse ayrı bağlantı (adres + token)
verilebilir. Adımın kendi adresi varsa varsayılan token o adrese gönderilmez.

Yukarıdaki `application.yml` değerleri **ilk değerlerdir**. Arayüzdeki *Yönetim → Sistem parametreleri* ekranından
değiştirilen ayarlar veritabanında (`app_setting`, geçmişi `app_setting_history`) tutulur ve uygulama yeniden
başlatılmadan devreye girer:

- **Sunucu LLM:** mock / kurum içi OpenAI uyumlu uç nokta, adres, model, API anahtarı, structured output modu,
  zaman aşımı, deneme sayısı ve **adım bazında model** (model çıkarma ve tehdit üretimi için ayrı model).
- **Embedding (RAG):** aç/kapa, adres, model, anahtar. Kapalıysa arama yalnızca BM25.
- **Tarayıcı LLM:** aç/kapa, varsayılan model, `webllm-config.json` adresi.
- **Doküman ve OCR:** PDF metnini tarayıcıda çıkarma, OCR teknolojisi (tarayıcıda tesseract.js / kapalı;
  on-prem LandingAI Faz 2'de eklenecek), OCR dilleri ve kurum içi tesseract dosya adresleri.
- **Akış:** varsayılan LLM modu, temperature, metin sınırları, tehdit grubu boyutları, RAG sınırları.
- "Bağlantıyı test et" kaydetmeden, ekrandaki değerlerle küçük bir JSON isteği gönderir.

Güvenlik (`tm.security.*`, bilinçli olarak ekrandan değiştirilemez):

| Ayar | Açıklama |
| --- | --- |
| `settings-encryption-key` (`TM_SETTINGS_KEY`) | API anahtarları veritabanına AES-256-GCM ile şifreli yazılır. Tanımlı değilse ekranda uyarı çıkar. Parola değişirse kayıtlı anahtarlar yeniden girilmelidir. |
| `block-public-endpoints`, `internal-host-suffixes` | Varsayılan `false` (PoC): dış adresler (ör. api.openai.com) serbesttir ve arayüzde "Dış erişim" olarak işaretlenir. `true` yapılırsa yalnızca localhost, özel IP'ler ve listedeki iç alan adları girilebilir. |
| `settings-admins` | Ayarları değiştirebilecek kullanıcılar. Boşsa herkes (yalnızca geliştirme). |

Anahtarlar API yanıtlarında hiçbir zaman dönmez (yalnızca "kayıtlı ••••1234" ipucu); değişiklik geçmişine
anahtar değeri yazılmaz. Mevcut veritabanları yeni tabloları açılışta kendiliğinden alır (`db/schema-*-v2-settings.sql`).

## Tarayıcıda çalışan LLM ve OCR

**Tarayıcı LLM (WebLLM, WebGPU):** İnceleme oluştururken "Tarayıcı" seçilirse backend istemi ve JSON şemasını
hazırlayıp bekler. İnceleme ekranındaki panel modeli tarayıcıda çalıştırır (şema XGrammar ile zorlanır) ve
çıktıyı geri gönderir; doğrulama yine backend'dedir. Tarayıcı modunda tehditler küçük partilerle (varsayılan 4)
üretilir. WebGPU yoksa istem kopyalanıp çıktı elle yapıştırılabilir veya sunucu LLM'ine geçilebilir.

**Tarayıcı OCR:** PDF'ler pdf.js ile okunur. Metin katmanı yoksa sayfalar görüntüye çevrilip tesseract.js ile
OCR yapılır. Görseller de doğrudan OCR'lanır. Dosya tarayıcıdan çıkmaz; yalnızca çıkarılan metin gönderilir.

**Bulut erişimi olmayan ortam için:** Bu iki kütüphane varsayılan olarak model ve dil dosyalarını internetten
indirir. Kurum içinde:

- WebLLM: model klasörlerini ve wasm dosyalarını intranete koyun, `frontend/public/webllm-config.example.json`
  dosyasını `webllm-config.json` olarak kopyalayıp adresleri yazın.
- tesseract.js: worker, core ve `tur`/`eng` dil dosyalarını intranete koyun, `frontend/.env.example`
  dosyasındaki `VITE_TESSERACT_*` değişkenlerini doldurun.
- pdf.js worker'ı derlemeye dahildir, ek ayar gerekmez.

Görsel diyagramlar için sunucu tarafında OCR yoktur (kurum içi gen AI'da görsel model olmadığı için).
Önerilen yol draw.io kaynak dosyasıdır; on-prem LandingAI hazır olduğunda `core/parsing/DocumentParser`
arayüzüne yeni bir ayrıştırıcı olarak eklenebilir.

## Kontratlar (`contracts/`)

| Dosya | Kullanım |
| --- | --- |
| `system-model.schema.json` | Onaylanan sistem modeli (Open Threat Model'e yakın yapı) |
| `llm/model-extraction.output.schema.json` | Model çıkarımı structured output (strict uyumlu) |
| `llm/threat-generation.output.schema.json` | Tehdit üretimi structured output (strict uyumlu) |

Şemalar Maven derlemesinde classpath'e kopyalanır. Mock dahil tüm sağlayıcıların çıktısı aynı şemadan geçer.
İstemler sürümlüdür: `backend/src/main/resources/prompts/*.v1.md`. Her LLM çağrısı istem sürümü, model, ham
çıktı ve doğrulama hatalarıyla saklanır (İnceleme → İzlenebilirlik).

## Veri setleri (`datasets/`)

| Klasör | İçerik |
| --- | --- |
| `rules/` | Aday tehdit kuralları, risk metodolojisi, risk kuralları (bkz. `docs/kural-motoru.md`) |
| `knowledge-base/` | RAG kaynakları (JSONL): STRIDE, OWASP Top 10, OWASP API Top 10, MITRE ATT&CK seçkisi, **örnek** banka kontrol kataloğu (`KAT-*`), **örnek** geçmiş incelemeler |
| `sample-projects/` | 3 örnek proje (docx + draw.io, markdown, txt + görsel) |
| `evaluation/` | Örnekler için uzman referans tehdit listeleri (recall ölçümü) |

Banka kontrol kataloğu ve geçmiş incelemeler yer tutucudur; gerçek katalog aynı JSONL biçiminde eklenir ve
"Bilgi tabanı → Yeniden yükle" ile devreye alınır. Değerlendirme setindeki projeler RAG'e konmamalıdır.

## Backend mimarisi

```
com.bank.tm.core       Spring'den bağımsız iş mantığı (JDK + Jackson)
  model/               Kontrat sınıfları (SystemModel, ThreatCandidate, LLM çıktıları)
  domain/              İnceleme, adım, model sürümü, tehdit (AI / sistem / uzman ayrı), LLM çağrısı
  parsing/             docx (ek kütüphanesiz), draw.io, markdown, txt; istemci metni birleştirme
  llm/                 LlmGateway (kayıt, şema doğrulama, yeniden deneme, tarayıcı modu), istemciler, istemler
  rag/                 Bilgi tabanı, Türkçe tokenizer, BM25, hibrit arama
  rules/               Koşul dili, operatörler, fact'ler, aday tehdit motoru, kural deposu
  risk/                Risk metodolojisi ve motoru (JSON + Java kuralları)
  validation/          JSON Schema doğrulayıcı, model ve tehdit doğrulayıcıları
  pipeline/            Orkestratör ve 12 adım
  service/             ReviewService (kullanım senaryoları), rapor, metrikler, değerlendirme
  store/               ReviewStore portu: JdbcReviewStore (saf JDBC) ve bellek içi uygulama
com.bank.tm.app        Spring Boot: yapılandırma, REST uçları, PDFBox ayrıştırıcı
```

Spring katmanı ince tutuldu; çekirdek bağımsız test edilebilir ve aynı fabrika (`TmCore`) hem uygulamada hem
testlerde kullanılır.

## Oracle / PostgreSQL'e geçiş

Kalıcılık saf JDBC ve standart SQL'dir (UPSERT yok). Veritabanına özgü kısım yalnızca şema dosyasıdır:

1. `pom.xml`'e sürücü ekleyin (ör. `com.oracle.database.jdbc:ojdbc11`).
2. `spring.datasource.*` ayarlarını değiştirin, `tm.db.dialect: oracle` yapın.
3. `db/schema-oracle.sql` taslağını DBA ile gözden geçirin (JSON alanlar CLOB). Uygulama, `review` tablosu
   yoksa şemayı kendisi oluşturur; üretimde Flyway/Liquibase önerilir.
4. Faz 2'de pgvector kullanılacaksa `schema-postgresql.sql` başlangıç noktasıdır.

## REST API (özet)

| Uç | Açıklama |
| --- | --- |
| `GET/POST /api/reviews` | Liste / çok parçalı yükleme (`meta`, `files`, `clientTexts`) |
| `POST /api/reviews/from-sample/{name}` | Örnek projeden inceleme |
| `GET /api/reviews/{id}` | Ayrıntı + adımlar (ilerleme çubuğu) |
| `GET/PUT /api/reviews/{id}/model`, `POST …/model/approve`, `…/model/reject`, `…/back-to-model` | Model onayı |
| `GET …/candidates`, `GET …/retrieval` | Kural adayları ve RAG bağlamı |
| `GET/POST …/threats`, `PATCH …/threats/{tid}` | Tehditler ve uzman kararları |
| `POST …/expert-review/complete` / `reopen` | İncelemeyi tamamla / yeniden aç |
| `GET …/report`, `…/evaluation`, `…/llm-calls`, `…/audit`, `…/threat-events` | Rapor ve izlenebilirlik |
| `GET /api/llm-tasks`, `POST /api/llm-tasks/{callId}/result` | Tarayıcı LLM görevleri |
| `GET /api/knowledge/search`, `/sources`, `POST /reload` | LLM'siz bilgi tabanı araması |
| `GET /api/rules`, `POST /api/rules/reload`, `GET /api/metrics`, `GET /api/meta` | Kurallar, metrikler, sabitler |
| `GET/PUT /api/settings`, `POST /api/settings/test?target=llm\|embedding`, `POST /api/settings/models?task=`, `POST /api/settings/reset`, `GET /api/settings/history` | Sistem parametreleri |

Kullanıcı Faz 1'de `X-User` başlığından okunur (arayüzde sol alttaki alan). Kurumsal SSO'da
`app/web/CurrentUser` doğrulanmış kimliği dönecek şekilde değiştirilir.

## Testler

```bash
cd backend && mvn test
```

- `core` testleri: kural/risk motoru, ayrıştırıcılar, RAG, şema doğrulama, üç örnekle uçtan uca akış (sunucu ve
  tarayıcı modu, geçersiz çıktının düzeltme istemiyle tekrarı, modele dönüş).
- `JdbcStoreSqliteTest`: aynı akış gerçek SQLite dosyasıyla.
- `ApplicationSmokeTest`: Spring bağlamı ve temel REST uçları.

Örnek projelerde mock LLM ile beklenen tehditlerin tamamı yakalanır (recall 1.0). Bu, akışın ve kuralların
doğru bağlandığını gösterir; gerçek dokümanlarda kalite, gerçek LLM ve değerlendirme setiyle ölçülmelidir.

## Faz 1 sınırları

- Mock LLM yalnızca deneme içindir; gerçek kalite kurum içi model ile ölçülmelidir.
- Kontrol kataloğu ve geçmiş incelemeler örnektir.
- Proje sahibi portalı, Jira/GRC entegrasyonu ve dış standart eşleme tabloları Faz 2–3 kapsamındadır.
- Kimlik doğrulama başlık tabanlıdır; canlı kullanım öncesi SSO ve rol bazlı yetki eklenmelidir.
- Örnek risk eşikleri kurum metodolojisine göre güncellenmelidir.
