# Pre-Analyzer 3

Java / Spring Boot projelerini statik olarak analiz eden **tek parça, saf Java** uygulama.
pre_analyzer2'deki Java orkestratör + Python tree-sitter ikilisinin sadeleştirilmiş, olgun hâli:
Python bağımlılığı yok, servis ayağa kaldırmak yok — tek jar, tek komut.

```
pre_analyzer_3/
├── analyzer/         ← Analiz uygulaması (Maven, Java 21, JavaParser tabanlı)
├── ornek-projeler/   ← Test için örnek Spring Boot projeleri
└── output/           ← Üretilen analiz sonuçları
```

## Ne Yapar?

Girdi **iki biçimden biri** olabilir:

- **Kaynak proje dizini** — `src/main/java` içeren Maven/Gradle projesi (JavaParser ile analiz).
- **Derlenmiş artefakt** — `.war` veya `.jar` dosyası. Kaynak kod gerekmez; sınıflar **bytecode'dan**
  çözümlenir: yapı ve anotasyonlar (yol/cron değerleri dahil) **reflection** ile, metod-içi çağrı
  akışı **`javap -c`** ile çıkarılır. Spring Boot fat-jar (`BOOT-INF/classes` + `BOOT-INF/lib`),
  klasik WAR (`WEB-INF/classes` + `WEB-INF/lib`) ve düz jar yerleşimleri desteklenir.
  Projenin kendi sınıfları, paketlenmiş 3. parti sınıflardan kesin ayrılır (karışmaz).
  Artefakt çıktısı, bir kaynak analizinin üzerine yazmasın diye ayrı klasöre gider
  (`<proje>_war_analiz` / `<proje>_jar_analiz`).

Her iki biçimden de şunlar çıkarılır:

1. **Framework künyesi** — Spring Boot sürümü, packaging (jar/war), starter'lar,
   Java sürümü, port, datasource, derleme zinciri (kaynak → mvn/gradle → `.jar`/`.war` → çalıştırma şekli).
   WAR projelerinde `SpringBootServletInitializer` ve `web.xml` kontrolü yapılır.
2. **Giriş noktaları** — REST endpoint'leri (HTTP metod + tam path), `@Scheduled` işler (cron),
   `@KafkaListener`, `@EventListener`, `CommandLineRunner`/`ApplicationRunner`, `main`.
   Her giriş noktası için **gereken bağımlılıklar** (akış boyunca kullanılan harici sınıflar →
   grupId/artifactId) da çıkarılır.
3. **Adım adım akış** — Her giriş noktasından başlayarak metod gövdesi adım adım açılır:
   çağrılar, if/else, döngüler, try/catch, return/throw. Proje içi çağrıların altına
   o metodun kendi akışı iç içe yerleştirilir; repository/HTTP/Kafka sınırına gelince
   `sinir` etiketi konur. Döngüsel çağrılar ve derinlik güvenle kesilir.
4. **Harici metod çözümü (jar/war)** — Kaynakta bulunmayan (harici jar) çağrıların gerçek
   imzaları, projenin/bağımlılıkların `.jar`/`.war` içeriğinden **`javap`** ile çözülür ve
   ilgili adıma `disBagimlilik` (jar + imza + dönüş tipi) olarak eklenir. Spring Boot fat-jar
   (`BOOT-INF/lib`) ve WAR (`WEB-INF/lib`) içindeki gömülü jar'lar da açılıp taranır.
5. **Çağrı grafiği** — Metod düzeyinde kim-kimi-çağırıyor grafiği (`cagri_grafigi.json`) ve
   sınıf düzeyinde **yalın çağrı grafiği** (`siniflar.json` içinde `yalinCagriGrafigi`:
   hangi sınıf hangi sınıfın metodunu çağırıyor + varsa kural).
6. **Kroki** — Katmanlı mimari haritası: Giriş Noktaları → Servisler → Harici Kütüphaneler → Dış Dünya (DB/HTTP/Kafka).

> **Not:** Üretilen JSON dosyalarının adları ve tüm alan adları (anahtarlar) Türkçedir;
> bu dosyalar bir LLM'e kaynak olarak verilmek üzere tasarlanmıştır.

Desteklenen build sistemleri: **Maven** (`pom.xml`) ve **Gradle** (`build.gradle` / `.kts`),
çok modüllü projeler dahil. Kaynakta olmayan bağımlılıklar (harici jar'lar) isim sezgileriyle
sınıflandırılır (örn. `*Repository` → DB sınırı).

## Gereksinimler

| Araç | Sürüm |
|------|-------|
| Java | 21+ |
| Maven | 3.9+ (sadece derlemek için) |

## Derleme

```bash
cd analyzer
mvn clean package
```

Çıktı: `analyzer/target/pre-analyzer.jar` (bağımsız fat jar).

## Çalıştırma

```bash
java -jar analyzer/target/pre-analyzer.jar <hedef> [--out <çıktı-dizini>]
```

`<hedef>` bir **proje dizini** ya da bir **`.war` / `.jar` dosyası** olabilir.

Örnekler:

```bash
# kaynak proje dizini
java -jar analyzer/target/pre-analyzer.jar ornek-projeler/borsaveriyazma --out output

# derlenmiş artefakt (kaynak kod olmadan)
java -jar analyzer/target/pre-analyzer.jar build/libs/uygulama.war --out output
java -jar analyzer/target/pre-analyzer.jar target/uygulama-1.0.jar --out output
```

## Çıktılar

`<çıktı>/<proje-adı>_analiz/` altında:

| Dosya | İçerik |
|-------|--------|
| `report.html` | **Etkileşimli rapor** — tarayıcıda aç: Genel Bakış, Kroki, Giriş Noktaları & Akışlar, Çağrı Grafiği sekmeleri. İnternet gerektirmez, tek dosya. |
| `proje.json` | Build/framework künyesi, modüller, katman haritası, uygulama konfigürasyonu (`derleme`, `cerceve`, `moduller`, `katmanlar`, `uygulamaKonfig`) |
| `siniflar.json` | Tüm sınıflar, alanlar, metodlar ve metod akışları (`metodlar[].akis`) + yalın çağrı grafiği (`yalinCagriGrafigi`) |
| `baslangic_noktalari.json` | Giriş noktaları + her birinin iç içe çözümlenmiş `akis`'ı, `gerekenBagimliliklar` ve harici çağrılarda `disBagimlilik` |
| `cagri_grafigi.json` | Metod çağrı grafiği (`dugumler` + `kenarlar`) |
| `LLM_REHBERI.md` | JSON'ları bir LLM'e verirken hangi alanın hangi soruya cevap verdiği |

> JSON alan adları Türkçedir (ör. `projeAdi`, `derleme`, `baslangicNoktalari`, `akis`, `adimlar`,
> `hedefSinif`, `sinir`, `disBagimlilik`). `report.html` aynı veriyi insan için görselleştirir.

## report.html Sekmeleri

- **Genel Bakış** — framework rozetleri, derleme & çalıştırma zinciri, starter/özellik çipleri,
  konfigürasyon (parolalar maskelenir), istatistikler, bağımlılık listesi.
- **Kroki** — katmanlı sınıf haritası; sürükle/yakınlaştır, kutuya tıklayınca bağlantıları vurgular.
  Giriş noktası içeren sınıflar kalın çerçeve + mavi nokta ile işaretlidir.
- **Giriş Noktaları & Akışlar** — endpoint'e tıkla, akışı satır numaralarıyla adım adım izle;
  `[+ Sınıf.metod içine in]` ile alt çağrıları aç/kapa. Log/print gürültüsü soluk gösterilir,
  DB/HTTP/KAFKA sınır çağrıları renkli rozetlidir.
- **Çağrı Grafiği** — giriş noktasına göre filtrelenebilir erişilebilirlik grafiği.

## Mimari

```
Main  (hedef dizin ise kaynak yolu, .war/.jar ise artefakt yolu)
 ├─ scan/ProjectScanner        modülleri ve kaynak köklerini bulur
 ├─ build/BuildInfoReader      pom.xml (DOM) / build.gradle (regex) → koordinat, packaging, bağımlılıklar
 ├─ build/ArtifactReader       .war/.jar açar → yerleşim (kendi sınıfları vs paketlenmiş lib), künye
 ├─ build/AppConfigReader      application.properties / .yml → port, datasource... (parola maskeli)
 ├─ parse/SourceAnalyzer       JavaParser + SymbolSolver ile tüm .java dosyaları → ClassModel
 ├─ parse/BytecodeAnalyzer     .class → reflection ile ClassModel (anotasyonlar + yapı)
 ├─ parse/BytecodeFlow         javap -c -l → metod başına çağrı akışı (bytecode'dan)
 ├─ parse/FlowExtractor        metod gövdesi → sıralı FlowStep listesi (call/if/loop/try/return/throw)
 ├─ detect/StereotypeDetector  anotasyon > miras > ad/paket sezgisi ile katman ataması
 ├─ detect/Linker              basit ad → FQN yükseltme, internal bayrağı, boundary tespiti
 ├─ detect/EntryPointDetector  REST/Scheduled/Kafka/Event/Runner/main tespiti
 ├─ graph/MethodResolver       arayüz → implementasyon atlama dahil metod çözümleme
 ├─ graph/CallGraphBuilder     metod çağrı grafiği (accessor/log gürültüsü filtreli)
 ├─ graph/FlowTracer           giriş noktasından iç içe trace (derinlik limiti + döngü koruması)
 ├─ jar/JarIndex               proje/bağımlılık jar+war'larını tarar (gömülü BOOT-INF/WEB-INF lib açılır)
 ├─ jar/JavapResolver          kaynakta olmayan sınıfların metodlarını javap ile çözer
 ├─ jar/ExternalCallEnricher   trace'teki harici çağrı hedeflerini toplar → JavapResolver
 └─ report/JsonWriter + HtmlReport   Türkçe anahtarlı JSON dosyaları + gömülü-veri tek dosya HTML rapor
```

## Bilinen Sınırlar

- Akış çıkarımı statiktir: çalışma zamanı polimorfizmi, AOP, reflection takip edilmez.
- Arayüzden implementasyona atlama, projede **tek** implementasyon varsa yapılır.
- Harici metod çözümü (`javap`) **en iyi çaba** ilkesiyle çalışır: ilgili `.jar`/`.war` ya da
  bağımlılık jar'ı (proje derleme çıktısı, `~/.m2`, Gradle önbelleği, fat-jar/WAR içi gömülü lib)
  bulunamazsa o çağrı yalnızca import/isim sezgisiyle bırakılır.
- **Artefakt (.war/.jar) analizinde** akış bytecode'dan çıkarıldığı için yalnızca **çağrı sırası**
  elde edilir; `if`/`loop`/`try` blokları ve koşullar kaynak koddaki gibi gösterilemez. Anotasyon
  ve yapı için reflection kullanıldığından, paketlenmemiş bağımlılıkları olan **düz jar**'larda
  bazı sınıflar yüklenemeyip yalnızca iskelet (ad/paket) olarak kaydedilebilir. Tam sadakat için
  kaynak dizini analizi tercih edilmelidir.
- Gradle ayrıştırması regex tabanlıdır; alışılmadık DSL kullanımlarında bağımlılık listesi eksik kalabilir.

## Yol Haritası (sonraki adımlar)

- Çoklu uygulama birleştirme (sistem geneli veri akışı haritası)
- Kafka topic / Feign URL üzerinden uygulamalar arası kenar çıkarımı
- `disBagimlilik` çözümlerinin yalın çağrı grafiğine ve çağrı grafiği düğümlerine de işlenmesi
