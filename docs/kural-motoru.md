# Kural motoru ve risk motoru

Faz 1'de Drools kullanılmaz. Kurallar `datasets/rules/` altındaki JSON dosyalarında tutulur, backend açılışta
yükler ve **Kurallar ve risk** ekranındaki "Kuralları yeniden yükle" (veya `POST /api/rules/reload`) ile yeniden
başlatmadan güncellenir. Hatalı bir dosya yüklenmez; önceki geçerli kurallar kullanılmaya devam eder.

| Dosya | İçerik |
| --- | --- |
| `threat-rules.json` | STRIDE-per-element ve bağlamsal **aday tehdit** kuralları |
| `risk-methodology.json` | Ölçek (1–5), formül (olasılık × etki), seviye eşikleri, STRIDE varsayılanları |
| `risk-rules.json` | Olasılık/etkiyi ayarlayan **risk kuralları** |

## Koşul dili

```json
{ "all": [ ... ] }      // hepsi doğru
{ "any": [ ... ] }      // en az biri doğru
{ "not": { ... } }      // değil
{ "fact": "element.zone", "op": "eq", "value": "INTERNET" }
```

Operatörler: `eq`, `ne`, `in`, `notIn`, `contains`, `containsAny`, `containsAll`, `gt`, `gte`, `lt`, `lte`,
`matches` (regex), `isNull`, `notNull`, `isTrue`, `isFalse`, `isEmpty`, `notEmpty`.
Bilinmeyen operatör kural yüklenirken reddedilir.

## Kullanılabilir fact'ler

Kurallar yalnızca onaylı sistem modelinden türetilen bu anahtarları görür (`core/rules/Facts.java`).
Yeni bir bilgiye kural yazmak için önce oraya eklenmelidir.

| Anahtar | Bileşen kuralı | Akış kuralı | Açıklama |
| --- | --- | --- | --- |
| `element.id`, `element.name` | ✓ | ✓ | Akışta ad "Kaynak → Hedef" |
| `element.kind` | EXTERNAL_ENTITY / PROCESS / DATA_STORE | DATA_FLOW | STRIDE-per-element türü |
| `element.isFlow` | false | true | |
| `element.type`, `element.zone`, `element.internetFacing`, `element.technology` | ✓ | – | |
| `element.authentication` | ✓ | ✓ | NONE, JWT, OAUTH2, MTLS, SSO, API_KEY, BASIC, SERVICE_ACCOUNT, UNKNOWN |
| `element.dataClasses` | ✓ | ✓ | Öğenin kendi veri sınıfları |
| `element.relatedDataClasses` | bileşen + bağlı akışlar | akış + kaynak + hedef | Hassas veri kontrolleri için önerilen alan |
| `element.protocol`, `element.encrypted`, `element.crossesTrustBoundary` | – | ✓ | `null` = bilinmiyor |
| `source.*`, `target.*` | – | ✓ | Akışın uç bileşenleri (`type`, `kind`, `zone`, `internetFacing`, …) |
| `system.dataClasses`, `system.componentCount` | ✓ | ✓ | Tüm model |
| `review.projectType`, `review.dataClassification`, `review.answers.*` | ✓ | ✓ | İnceleme formu |
| `threat.stride`, `threat.ruleId`, `threat.aiAdditional` | yalnızca risk kuralları | | |

## Aday tehdit kuralı

```json
{
  "id": "TR-F-T-02", "enabled": true, "appliesTo": "DATA_FLOW", "stride": "TAMPERING",
  "title": "SQL enjeksiyonu",
  "titleTemplate": "{source.name} üzerinden {target.name} veritabanına SQL enjeksiyonu",
  "descriptionTemplate": "…",
  "defaultLikelihood": 3, "defaultImpact": 5,
  "ragQuery": "SQL enjeksiyonu parametreli sorgu ORM girdi doğrulama",
  "tags": ["owasp:A03", "cwe:89"],
  "when": { "all": [
    { "fact": "target.type", "op": "eq", "value": "DATABASE" },
    { "fact": "source.kind", "op": "eq", "value": "PROCESS" } ] }
}
```

`ragQuery` bilgi tabanı aramasında kullanılır; `defaultLikelihood/Impact` risk hesabının başlangıç değeridir.

## Risk hesabı

1. Başlangıç: adayı üreten kuralın varsayılanları; manuel/AI ek önerilerde `strideDefaults`.
2. Risk kuralları `order` sırasıyla uygulanır. Operasyonlar: `set`, `min` (en az), `max` (en fazla), `add`.
   Sonuç her adımda ölçek sınırlarına kırpılır.
3. Skor = olasılık × etki; seviye `levels` eşiklerinden okunur.
4. Her ayarlama izlenebilir şekilde kaydedilir (Tehdit detayı → "Risk hesabının izi").

LLM'in önerdiği olasılık/etki bu hesaba **girmez**; ayrı saklanır. Uzman değeri girerse gerekçe zorunludur ve
sistem değeri silinmez. Böylece **AI tahmini**, **sistem hesabı** ve **uzman kararı** ayrı alanlarda tutulur.

## Java ile genişletme

Karmaşık mantık gerektiren risk kuralı veya yeni operatör için Spring bean tanımlamak yeterlidir;
`CoreConfig` bunları otomatik olarak çekirdeğe ekler:

```java
@Component
class KritikSistemKurali implements RiskRule {
    public String id() { return "JAVA-01"; }
    public int order() { return 500; }
    public String description() { return "Kritik sistem listesindeki hedeflerde etki 5"; }
    public Optional<RiskEffect> evaluate(Map<String, Object> facts) {
        return KRITIK.contains(facts.get("element.name"))
                ? Optional.of(new RiskEffect(null, new RiskEffect.Adjustment("set", 5), "Kritik sistem"))
                : Optional.empty();
    }
}

@Component
class StartsWith implements Operator {
    public String name() { return "startsWith"; }
    public boolean test(Object actual, Object expected) {
        return actual != null && actual.toString().startsWith(String.valueOf(expected));
    }
}
```

## Drools'a geçiş

Kural sayısı ve değişkenliği arttığında `RiskRule` arayüzünün Drools ile çalışan bir uygulaması yazılabilir
(fact haritası Drools'a fact olarak verilir). Arayüz ve iz kaydı aynı kalır.
