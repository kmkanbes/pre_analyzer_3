# borsaverialma — Analiz Çıktısı Rehberi (LLM için)

Bu dizindeki dosyalar `borsaverialma` projesinin statik analiz sonucudur.
Tüm JSON alan adları Türkçedir. Çıktı, giriş noktaları (uygulamada esas çalışan
akışlar) etrafında düzenlenmiştir ve token tasarrufu için katmanlıdır:

- **proje.json** — üst künye (framework, build, modüller, katmanlar, config). Önce bunu oku.
- **siniflar.json** — tüm sınıfların ÖZET katalogu (imza düzeyi, AKIŞ YOK). "Projede ne var,
  hangi metodlar var, hangileri giriş noktası" sorusunun cevabı. Ucuz; her zaman yüklenebilir.
- **baslangic_noktalari.json** — giriş noktası listesi (REST/JAX-RS/servlet/filter/listener/
  cron/kafka/jms/main). Her giriş noktasının detaylı akışı ayrı bir parça dosyada; bu listede
  `akisDosyasi` alanı o parçaya işaret eder. Akış GÖVDESİ burada DEĞİL.
- **baslangic_akis/<GirisSinifi>.json** — yalnızca ilgili giriş sınıfının endpoint'lerinin
  ADIM ADIM akışı (çağrı zinciri açılmış). Tek bir endpoint'i anlamak/değiştirmek için
  SADECE ilgili parçayı yükle — diğerlerini okumana gerek yok.

| Soru | Dosya | Alan |
|------|-------|------|
| Proje hangi framework'ü kullanıyor, nasıl paketleniyor (jar/war)? | proje.json | `cerceve.birincilCerceve`, `cerceve.yiginlar`, `derleme.paketleme` |
| Hangi port, hangi veritabanı, hangi ayarlar? | proje.json | `cerceve.sunucuPort`, `cerceve.veriKaynagiUrl`, `uygulamaKonfig` |
| Hangi bağımlılıklar var, hangi katmanda hangi sınıflar? | proje.json | `derleme.bagimliliklar`, `katmanlar` |
| Projede hangi sınıf/metod var, hangisi giriş noktası? | siniflar.json | `siniflar[].metodlar[].imza`, `siniflar[].girisNoktasiMi`, `metodlar[].girisNoktasiMi` |
| Uygulamaya nereden girilir? | baslangic_noktalari.json | `baslangicNoktalari[].tur/httpMetodu/yol/urlDesenleri/cron` |
| Bu giriş noktası hangi bağımlılıkları gerektirir? | baslangic_noktalari.json | `gerekenBagimliliklar`, `kullanilanDisSiniflar` |
| Bir endpoint çağrılınca ADIM ADIM ne olur? | baslangic_noktalari.json -> `akisDosyasi` -> baslangic_akis/...json | `baslangicNoktalari[].akis` (iç içe `adimlar`, her `altCagri` bir alt metodun açılımı) |
| Kaynakta olmayan (harici jar) metodun gerçek imzası? | baslangic_akis/...json | `adimlar[].disBagimlilik` (jar/imza/donusTipi) |
| Hangi adım veritabanına/dış servise gider? | baslangic_akis/...json | `adimlar[].sinir` = db / http / kafka / mail / queue |

## akış parçaları (baslangic_akis/) nasıl okunur
`tur`: `call` · `if` · `loop` · `try` · `return` · `throw` · `switch`
`baglam`: adımın bulunduğu blok: `if`/`else`/`loop`/`try`/`catch`/`finally`/`case` (null = üst seviye)
`projeIci: true` -> hedef metod projede; `altCagri` alanında gövdesi açılmıştır.
`sinir` -> adım dış dünyaya dokunur (db=repository/JPA, http=RestTemplate/WebClient/Feign, kafka, mail, queue).
`disBagimlilik` -> kaynakta olmayan hedef metodun jar/war içeriğinden javap ile çözülmüş gerçek imzası.
`dongusel: true` -> döngüsel çağrı, tekrar açılmadı. `derinlikLimiti: true` -> derinlik limiti.
`buDosyadaYukaridaAcildi: true` -> bu metod aynı parçada daha önce tam açıldı; tekrar etmemek için kısaltıldı.

report.html aynı verinin insan için görselleştirilmiş hâlidir; LLM girdisi olarak JSON'ları kullan.
