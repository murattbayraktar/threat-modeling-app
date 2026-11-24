# Kurumsal Talimat API'si – Tasarım Dokümanı

## 1. Amaç

Kurumsal müşterilerin kendi ERP sistemlerinden bankaya toplu ödeme talimatı (EFT/havale) gönderebilmesi için bir API sunulacaktır. Talimatlar kontrol edildikten sonra çekirdek bankacılık sistemine iletilir.

## 2. Bileşenler

- Kurumsal Müşteri Sistemi: Kurumsal müşterinin talimat gönderen ERP uygulaması (üçüncü taraf).
- Kurumsal Talimat API: İnternete açık REST API. İstemciler OAuth 2.0 client credentials ile token alır, istekler JWT ile doğrulanır.
- Kimlik Sağlayıcı: Kurumsal istemcilere token üreten kimlik sağlayıcı (DMZ).
- Talimat Servisi: İç ağda çalışan servis; talimatları limit ve hesap kontrollerinden geçirir.
- Talimat Veritabanı: İç ağdaki PostgreSQL veritabanı; talimat tutarı, alıcı IBAN, müşteri vergi numarası tutulur.
- Bildirim Servisi: İşlem sonucunu müşteriye e-posta ile bildiren iç servis.
- Çekirdek Bankacılık: Ödeme talimatlarını işleyen çekirdek bankacılık sistemi.

## 3. Veri akışları

- Kurumsal Müşteri Sistemi → Kurumsal Talimat API: Toplu ödeme talimatı HTTPS ile JWT kullanılarak gönderilir.
- Kurumsal Müşteri Sistemi → Kimlik Sağlayıcı: OAuth 2.0 client credentials ile HTTPS üzerinden token alınır.
- Kurumsal Talimat API → Talimat Servisi: Talimatlar REST ile iletilir, mTLS kullanılır.
- Talimat Servisi → Talimat Veritabanı: Talimat kayıtları JDBC ile yazılır.
- Talimat Servisi → Bildirim Servisi: Sonuç bilgisi HTTP ile iletilir.
- Talimat Servisi → Çekirdek Bankacılık: Onaylanan talimat ödeme mesajı olarak servis hesabı ile gönderilir.

## 4. Notlar

API yalnızca sözleşmeli kurumsal müşterilere açılacaktır. Günlük talimat limiti müşteri bazında tanımlanır.
