"""Örnek proje dosyalarını (docx, png) yeniden üretir.

Kullanım:  pip install python-docx pillow && python generate_samples.py
Metin içerikleri bu dosyada tutulur; .md/.txt/.drawio örnekleri elle yazılmıştır.
"""
from pathlib import Path

from docx import Document
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent

FATURA_COMPONENTS = [
    ("Tedarikçi Portalı", "Tedarikçilerin internet üzerinden fatura ve ek belge (PDF) yüklediği web uygulaması. Tedarikçi kullanıcıları e-posta ve parola ile giriş yapar."),
    ("Tedarikçi ERP", "Büyük tedarikçilerin toplu fatura gönderdiği üçüncü taraf sistem."),
    ("API Gateway", "DMZ'de çalışan ve dış istekleri karşılayan gateway. Portal istekleri için OAuth 2.0 token doğrulaması yapar."),
    ("Fatura Entegrasyon Servisi", "İç ağda çalışan Spring Boot servisi. Faturaları doğrular, kaydeder ve onay sürecini başlatır."),
    ("Fatura Veritabanı", "İç ağdaki Oracle veritabanı. Fatura tutarı, tedarikçi IBAN ve vergi numarası bilgileri tutulur."),
    ("Ek Belge Deposu", "Yüklenen PDF eklerinin saklandığı iç ağdaki dosya deposu."),
    ("Mesaj Kuyruğu", "Onay olaylarının iletildiği iç ağdaki kuyruk."),
    ("Onay İş Akışı", "Satın alma ekiplerinin faturaları onayladığı iç uygulama. Kullanıcılar kurumsal SSO ile giriş yapar."),
    ("Muhasebe Sistemi", "Çekirdek bankacılık muhasebe modülü. Onaylanan faturalar için ödeme kaydı oluşturur."),
]

FATURA_FLOWS = [
    "Tedarikçi Portalı → API Gateway: Fatura ve ek belgeler HTTPS ile gönderilir, OAuth 2.0 token kullanılır.",
    "Tedarikçi ERP → API Gateway: Toplu fatura XML dosyaları HTTPS üzerinden mTLS ile iletilir.",
    "API Gateway → Fatura Entegrasyon Servisi: Gateway gelen istekleri REST ile entegrasyon servisine iletir.",
    "Fatura Entegrasyon Servisi → Fatura Veritabanı: Fatura kayıtları ve IBAN bilgisi JDBC ile yazılır, servis hesabı kullanılır.",
    "Fatura Entegrasyon Servisi → Ek Belge Deposu: PDF ekleri saklanır.",
    "Fatura Entegrasyon Servisi → Mesaj Kuyruğu: Onay olayı yayınlanır.",
    "Mesaj Kuyruğu → Onay İş Akışı: Onay bekleyen faturalar iletilir.",
    "Onay İş Akışı → Muhasebe Sistemi: Onaylanan fatura için ödeme talimatı SOAP ile gönderilir, servis hesabı kullanılır.",
]


def build_fatura_docx() -> None:
    doc = Document()
    doc.add_heading("Tedarikçi Fatura Entegrasyonu – Mimari Tasarım Dokümanı", level=0)

    doc.add_heading("1. Amaç ve kapsam", level=1)
    doc.add_paragraph(
        "Bu proje ile tedarikçilerin e-fatura ve ek belgelerini bankaya elektronik olarak iletmesi, "
        "satın alma ekiplerinin faturaları onaylaması ve onaylanan faturaların muhasebeye aktarılması hedeflenmektedir."
    )

    doc.add_heading("2. Bileşenler", level=1)
    for name, desc in FATURA_COMPONENTS:
        doc.add_paragraph(f"{name}: {desc}", style="List Bullet")

    doc.add_heading("3. Veri akışları", level=1)
    for line in FATURA_FLOWS:
        doc.add_paragraph(line, style="List Bullet")

    doc.add_heading("4. Teknoloji ve sorumluluklar", level=1)
    table = doc.add_table(rows=1, cols=3)
    table.rows[0].cells[0].text = "Bileşen"
    table.rows[0].cells[1].text = "Teknoloji"
    table.rows[0].cells[2].text = "Sorumlu ekip"
    for row in [
        ("Fatura Entegrasyon Servisi", "Java 21, Spring Boot", "Satın Alma IT"),
        ("Fatura Veritabanı", "Oracle 19c", "Veritabanı ekibi"),
        ("API Gateway", "Kurumsal API Gateway", "API platform ekibi"),
    ]:
        cells = table.add_row().cells
        for i, value in enumerate(row):
            cells[i].text = value

    doc.add_heading("5. Güvenlik notları", level=1)
    doc.add_paragraph("Portal kullanıcıları için şifre politikası kurumsal standartlara uygundur.")
    doc.add_paragraph("Ek belgelerin saklama süresi henüz netleşmemiştir.")

    out = ROOT / "tedarikci-fatura-entegrasyonu" / "Mimari_Tasarim.docx"
    doc.save(out)
    print("yazıldı:", out)


def build_randevu_png() -> None:
    lines = [
        "SISTEM SEMASI - Mobil Sube Randevu",
        "",
        "Bilesenler:",
        "- Mobil Uygulama: Musteri mobil uygulamasi (internet)",
        "- Randevu API: REST API, JWT ile kimlik dogrulama",
        "- Randevu Veritabani: musteri adi, telefon, sube bilgisi",
        "- SMS Servisi: ucuncu taraf SMS saglayici",
        "",
        "Akislar:",
        "- Mobil Uygulama -> Randevu API: HTTPS, JWT",
        "- Randevu API -> SMS Servisi: HTTPS, API anahtari",
    ]
    img = Image.new("RGB", (900, 40 * len(lines) + 60), "white")
    draw = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 26)
    except OSError:
        font = ImageFont.load_default()
    for i, line in enumerate(lines):
        draw.text((30, 30 + i * 40), line, fill="black", font=font)
    out = ROOT / "mobil-sube-randevu" / "sistem_semasi.png"
    img.save(out)
    print("yazıldı:", out)


if __name__ == "__main__":
    build_fatura_docx()
    build_randevu_png()
