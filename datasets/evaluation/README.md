# Değerlendirme seti

Her dosya, bir örnek proje için uzman (analist) tarafından beklenen tehditleri listeler.
`target` alanı bileşen adı veya `Kaynak → Hedef` biçiminde veri akışıdır.
Backend, örnekten oluşturulan incelemeler için `GET /api/reviews/{id}/evaluation` ile
üretilen tehditlerin bu listeyi ne kadar kapsadığını (recall) ve fazladan üretilenleri raporlar.

Gerçek kullanımda bu dosyalar, güvenlik ekibinin geçmiş review'larından hazırlanır ve
RAG indeksine **konulmaz** (aksi halde ölçüm şişer).
