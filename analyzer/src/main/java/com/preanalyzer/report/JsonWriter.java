package com.preanalyzer.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.preanalyzer.jar.JavapResolver;
import com.preanalyzer.model.ClassModel;
import com.preanalyzer.model.EntryPoint;
import com.preanalyzer.model.EntryPoint.TraceNode;
import com.preanalyzer.model.EntryPoint.TraceStep;
import com.preanalyzer.model.ProjectModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analiz modelini Türkçe anahtarlı, ayrı JSON dosyalarına yazar (LLM tüketimi için).
 * Çıktı dosya adları ve tüm alan adları Türkçedir (Gereksinim 5, 6, 9, 15).
 *
 * Üretilen dosyalar (giriş noktası odaklı, katmanlı — token tasarrufu için):
 *   proje.json                   build/framework künyesi, modüller, katmanlar, konfigürasyon
 *   siniflar.json                tüm sınıfların özet katalogu (imza düzeyi, akış YOK)
 *   baslangic_noktalari.json     giriş noktaları listesi + gereken bağımlılıklar + akış parça referansı
 *   baslangic_akis/<sinif>.json  giriş sınıfı başına adım adım detaylı akış (kendi içinde tam)
 *   LLM_REHBERI.md               JSON'ların LLM tarafından nasıl yorumlanacağı
 */
public class JsonWriter {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** stereotype -> Türkçe katman etiketi (katmanlar haritası anahtarları için) */
    private static final Map<String, String> KATMAN_ADI = Map.ofEntries(
            Map.entry("controller", "denetleyiciler"),
            Map.entry("service", "servisler"),
            Map.entry("repository", "depolar"),
            Map.entry("entity", "varliklar"),
            Map.entry("dto", "veriNesneleri"),
            Map.entry("config", "yapilandirmalar"),
            Map.entry("scheduler", "zamanlayicilar"),
            Map.entry("component", "bilesenler"),
            Map.entry("listener", "dinleyiciler"),
            Map.entry("servlet", "servletler"),
            Map.entry("filter", "filtreler"),
            Map.entry("advice", "tavsiyeler"),
            Map.entry("httpclient", "httpIstemcileri"),
            Map.entry("util", "yardimcilar"),
            Map.entry("exception", "istisnalar"),
            Map.entry("main", "anaSinif"),
            Map.entry("other", "digerleri"));

    private Map<String, String> sinifStereotipi;   // fqn -> stereotype
    private Map<String, List<JavapResolver.MetodBilgi>> disMetodlar;
    private Set<String> girisMethodIdleri;         // classFqn#imza giriş noktası olan metodlar
    private Set<String> girisSiniflari;            // giriş noktası içeren sınıf FQN'leri

    public void writeAll(ProjectModel model, Path outDir,
                         Map<String, List<JavapResolver.MetodBilgi>> disMetodlar) throws IOException {
        this.disMetodlar = disMetodlar != null ? disMetodlar : Map.of();
        this.sinifStereotipi = new LinkedHashMap<>();
        for (ClassModel c : model.classes) sinifStereotipi.put(c.fqn, c.stereotype);
        this.girisMethodIdleri = new LinkedHashSet<>();
        this.girisSiniflari = new LinkedHashSet<>();
        for (EntryPoint ep : model.entryPoints) {
            if (ep.signature != null) girisMethodIdleri.add(ep.classFqn + "#" + ep.signature);
            girisSiniflari.add(ep.classFqn);
        }

        mapper.writeValue(outDir.resolve("proje.json").toFile(), projeMap(model));
        mapper.writeValue(outDir.resolve("siniflar.json").toFile(), siniflarOzetMap(model));
        mapper.writeValue(outDir.resolve("baslangic_noktalari.json").toFile(), baslangicMap(model));
        int parca = writeAkisParcalari(model, outDir);

        Files.writeString(outDir.resolve("LLM_REHBERI.md"), llmGuide(model), StandardCharsets.UTF_8);

        System.out.println("  JSON dosyalari yazildi: proje, siniflar (ozet), baslangic_noktalari, "
                + "baslangic_akis/ (" + parca + " parca) (+LLM_REHBERI.md)");
    }

    // ---------------- proje.json ----------------

    private Map<String, Object> projeMap(ProjectModel model) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("projeAdi", model.projectName);
        root.put("kokDizin", model.rootPath);
        root.put("analizZamani", model.analyzedAt);
        root.put("derleme", derlemeMap(model.build));
        root.put("cerceve", cerceveMap(model.framework));
        root.put("moduller", modullerList(model.modules));
        root.put("katmanlar", katmanlarMap(model.layers));
        root.put("uygulamaKonfig", model.appConfig);
        return root;
    }

    private Map<String, Object> derlemeMap(ProjectModel.BuildInfo b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b == null) return m;
        put(m, "arac", b.tool);
        put(m, "grupId", b.groupId);
        put(m, "artifactId", b.artifactId);
        put(m, "surum", b.version);
        put(m, "paketleme", b.packaging);
        put(m, "nihaiArtifact", b.finalArtifact);
        put(m, "javaSurumu", b.javaVersion);
        m.put("cokModullu", b.multiModule);
        List<Map<String, Object>> deps = new ArrayList<>();
        for (ProjectModel.Dependency d : b.dependencies) deps.add(bagimlilikMap(d));
        m.put("bagimliliklar", deps);
        return m;
    }

    private Map<String, Object> bagimlilikMap(ProjectModel.Dependency d) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "grupId", d.groupId);
        put(m, "artifactId", d.artifactId);
        put(m, "surum", d.version);
        put(m, "kapsam", d.scope);
        return m;
    }

    private Map<String, Object> cerceveMap(ProjectModel.FrameworkInfo f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f == null) return m;
        put(m, "birincilCerceve", f.primaryFramework);
        m.put("yiginlar", f.stacks);
        m.put("springBoot", f.springBoot);
        put(m, "springBootSurumu", f.springBootVersion);
        m.put("spring", f.spring);
        m.put("quarkus", f.quarkus);
        put(m, "quarkusSurumu", f.quarkusVersion);
        m.put("jakartaEe", f.jakartaEe);
        m.put("jaxrs", f.jaxrs);
        m.put("servletApi", f.servletApi);
        m.put("cdi", f.cdi);
        m.put("ejb", f.ejb);
        put(m, "paketleme", f.packaging);
        m.put("servletInitializer", f.servletInitializer);
        m.put("webXmlVar", f.hasWebXml);
        m.put("starterlar", f.starters);
        m.put("ozellikler", f.features);
        put(m, "sunucuPort", f.serverPort);
        put(m, "uygulamaAdi", f.applicationName);
        put(m, "contextYolu", f.contextPath);
        put(m, "veriKaynagiUrl", f.datasourceUrl);
        put(m, "anaSinif", f.mainClass);
        return m;
    }

    private List<Map<String, Object>> modullerList(List<ProjectModel.ModuleInfo> modules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectModel.ModuleInfo mo : modules) {
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "ad", mo.name);
            put(m, "yol", mo.path);
            put(m, "paketleme", mo.packaging);
            m.put("kaynakKokleri", mo.sourceRoots);
            m.put("kaynakDosyaKokleri", mo.resourceRoots);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> katmanlarMap(Map<String, List<String>> layers) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : layers.entrySet()) {
            String key = KATMAN_ADI.getOrDefault(e.getKey(), e.getKey());
            m.put(key, e.getValue());
        }
        return m;
    }

    // ---------------- siniflar.json (özet katalog — akış yok) ----------------

    private Map<String, Object> siniflarOzetMap(ProjectModel model) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("projeAdi", model.projectName);
        root.put("aciklama", "Projedeki sınıfların özet katalogu (imza düzeyi, akış YOK). "
                + "Bir metodun adım adım akışı için baslangic_akis/<GirisSinifi>.json dosyalarına bakın.");
        List<Map<String, Object>> siniflar = new ArrayList<>();
        for (ClassModel c : model.classes) siniflar.add(sinifOzetMap(c));
        root.put("siniflar", siniflar);
        return root;
    }

    private Map<String, Object> sinifOzetMap(ClassModel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "fqn", c.fqn);
        put(m, "ad", c.name);
        put(m, "paket", c.packageName);
        put(m, "modul", c.module);
        put(m, "dosyaYolu", c.filePath);
        put(m, "tur", c.kind);
        put(m, "stereotip", c.stereotype);
        m.put("girisNoktasiMi", girisSiniflari.contains(c.fqn));
        m.put("anotasyonlar", c.annotations);
        m.put("kalitim", c.extendsTypes);
        m.put("arayuzler", c.implementsTypes);
        List<Map<String, Object>> alanlar = new ArrayList<>();
        for (ClassModel.FieldModel f : c.fields) {
            Map<String, Object> fm = new LinkedHashMap<>();
            put(fm, "ad", f.name);
            put(fm, "tip", f.type);
            fm.put("enjekte", f.injected);
            alanlar.add(fm);
        }
        m.put("alanlar", alanlar);
        List<Map<String, Object>> metodlar = new ArrayList<>();
        for (ClassModel.MethodModel mm : c.methods) metodlar.add(metodOzetMap(c, mm));
        m.put("metodlar", metodlar);
        return m;
    }

    /** Metod özeti: imza/dönüş/anotasyon + giriş noktası bayrağı. Akış YOK. */
    private Map<String, Object> metodOzetMap(ClassModel c, ClassModel.MethodModel mm) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "ad", mm.name);
        put(m, "imza", mm.signature);
        put(m, "donusTipi", mm.returnType);
        m.put("parametreler", mm.params);
        m.put("anotasyonlar", mm.annotations);
        m.put("satir", mm.line);
        if (girisMethodIdleri.contains(c.fqn + "#" + mm.signature)) m.put("girisNoktasiMi", true);
        return m;
    }

    // ---------------- baslangic_noktalari.json ----------------

    private Map<String, Object> baslangicMap(ProjectModel model) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("projeAdi", model.projectName);
        List<Map<String, Object>> eps = new ArrayList<>();
        for (EntryPoint ep : model.entryPoints) eps.add(entryPointMap(ep, model));
        root.put("baslangicNoktalari", eps);
        return root;
    }

    private Map<String, Object> entryPointMap(EntryPoint ep, ProjectModel model) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "id", ep.id);
        put(m, "tur", ep.kind);                              // REST | JAXRS | SERVLET | FILTER | LISTENER | SCHEDULED | KAFKA | JMS | MESSAGING | EVENT | RUNNER | MAIN
        put(m, "girisStereotipi", sinifStereotipi.get(ep.classFqn)); // controller vb (Gereksinim 11)
        put(m, "kaynak", ep.source);                         // anotasyon | web.xml | kod
        put(m, "httpMetodu", ep.httpMethod);
        put(m, "yol", ep.path);
        if (ep.urlPatterns != null) m.put("urlDesenleri", ep.urlPatterns);
        put(m, "uretir", ep.produces);
        put(m, "tuketir", ep.consumes);
        put(m, "cron", ep.cron);
        put(m, "sabitHiz", ep.fixedRate);
        put(m, "konular", ep.topics);
        put(m, "ayrinti", ep.detail);
        put(m, "sinifFqn", ep.classFqn);
        put(m, "sinifAdi", ep.className);
        put(m, "metod", ep.method);
        put(m, "imza", ep.signature);
        m.put("satir", ep.line);

        // Gereksinim 13: bu metodun (akışı boyunca) gerektirdiği bağımlılıklar
        Set<String> disSiniflar = new LinkedHashSet<>();
        collectExternal(ep.trace, disSiniflar);
        m.put("kullanilanDisSiniflar", new ArrayList<>(disSiniflar));
        m.put("gerekenBagimliliklar", gerekenBagimliliklar(disSiniflar, model.build));

        // Detaylı adım adım akış ayrı parça dosyasında (giriş sınıfı başına)
        if (ep.trace != null) m.put("akisDosyasi", akisDosyaYolu(ep.classFqn));
        return m;
    }

    /** Giriş sınıfı FQN -> akış parçası göreli yolu. */
    private String akisDosyaYolu(String classFqn) {
        return "baslangic_akis/" + classFqn + ".json";
    }

    private void collectExternal(TraceNode node, Set<String> out) {
        if (node == null) return;
        for (TraceStep s : node.steps) {
            if ("call".equals(s.type) && !s.internal && s.targetClass != null
                    && s.targetClass.contains(".") && !"new".equals(s.method) && !isJdkNoise(s.targetClass)) {
                out.add(s.targetClass);
            }
            if (s.callee != null) collectExternal(s.callee, out);
        }
    }

    /** Harici sınıf FQN'lerini beyan edilmiş bağımlılıklara (grupId/artifactId) eşler. */
    private List<Map<String, Object>> gerekenBagimliliklar(Set<String> disSiniflar, ProjectModel.BuildInfo build) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (build == null) return out;
        Set<String> eklendi = new LinkedHashSet<>();
        for (String fqn : disSiniflar) {
            ProjectModel.Dependency best = null;
            for (ProjectModel.Dependency d : build.dependencies) {
                if (d.groupId != null && fqn.startsWith(d.groupId)) {
                    if (best == null || d.groupId.length() > best.groupId.length()) best = d;
                }
            }
            if (best == null) continue;
            String key = best.groupId + ":" + best.artifactId;
            if (!eklendi.add(key)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "grupId", best.groupId);
            put(m, "artifactId", best.artifactId);
            put(m, "surum", best.version);
            out.add(m);
        }
        return out;
    }

    // ---------------- baslangic_akis/<GirisSinifi>.json (giriş sınıfı başına detaylı akış) ----------------

    /**
     * Giriş noktalarını giriş sınıfına göre gruplayıp her grup için ayrı bir akış parçası yazar.
     * Parça kendi içinde tam (self-contained); ama aynı metod aynı parçada ikinci kez açılmaz
     * ("buDosyadaYukaridaAcildi" ile işaretlenir) — tekrar gürültüsü azalır.
     */
    private int writeAkisParcalari(ProjectModel model, Path outDir) throws IOException {
        Map<String, List<EntryPoint>> gruplar = new LinkedHashMap<>();
        for (EntryPoint ep : model.entryPoints) {
            if (ep.trace == null) continue;
            gruplar.computeIfAbsent(ep.classFqn, k -> new ArrayList<>()).add(ep);
        }
        if (gruplar.isEmpty()) return 0;
        Path dir = outDir.resolve("baslangic_akis");
        Files.createDirectories(dir);
        for (Map.Entry<String, List<EntryPoint>> g : gruplar.entrySet()) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("projeAdi", model.projectName);
            root.put("girisSinifi", g.getKey());
            root.put("sinifAdi", simpleName(g.getKey()));
            Set<String> acilan = new LinkedHashSet<>();   // bu parçada açılmış metodlar
            List<Map<String, Object>> eps = new ArrayList<>();
            for (EntryPoint ep : g.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "id", ep.id);
                put(m, "tur", ep.kind);
                put(m, "httpMetodu", ep.httpMethod);
                put(m, "yol", ep.path);
                put(m, "metod", ep.method);
                put(m, "imza", ep.signature);
                m.put("satir", ep.line);
                m.put("akis", traceNodeMap(ep.trace, acilan));
                eps.add(m);
            }
            root.put("baslangicNoktalari", eps);
            mapper.writeValue(dir.resolve(g.getKey() + ".json").toFile(), root);
        }
        return gruplar.size();
    }

    /** EntryPoint.TraceNode -> Türkçe anahtarlı düğüm (alt çağrılarıyla iç içe; parça-içi dedup). */
    private Map<String, Object> traceNodeMap(TraceNode n, Set<String> acilan) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "sinifFqn", n.classFqn);
        put(m, "sinifAdi", n.className);
        put(m, "metod", n.method);
        put(m, "imza", n.signature);
        put(m, "stereotip", n.stereotype);
        m.put("satir", n.line);
        if (n.cycle) m.put("dongusel", true);
        if (n.depthLimit) m.put("derinlikLimiti", true);

        // aynı metod bu parçada zaten açıldıysa tekrar açma, referansla bırak
        String id = n.classFqn + "#" + (n.signature != null ? n.signature : n.method);
        if (n.classFqn != null && !acilan.add(id)) {
            m.put("buDosyadaYukaridaAcildi", true);
            return m;
        }
        List<Map<String, Object>> adimlar = new ArrayList<>();
        for (TraceStep s : n.steps) adimlar.add(traceStepMap(s, acilan));
        m.put("adimlar", adimlar);
        return m;
    }

    private Map<String, Object> traceStepMap(TraceStep s, Set<String> acilan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adim", s.step);
        m.put("satir", s.line);
        put(m, "tur", s.type);
        put(m, "nesne", s.object);
        put(m, "hedefSinif", s.targetClass);
        put(m, "hedefStereotip", s.targetStereotype);
        put(m, "metod", s.method);
        if (s.args != null) m.put("argumanlar", s.args);
        put(m, "atananDegisken", s.assignTo);
        put(m, "kosul", s.condition);
        put(m, "baglam", s.context);
        m.put("projeIci", s.internal);
        put(m, "sinir", s.boundary);
        disBagimlilik(m, s.type, s.internal, s.targetClass, s.method, s.args);
        if (s.callee != null) m.put("altCagri", traceNodeMap(s.callee, acilan));
        return m;
    }

    private String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /** Harici çağrı adımına jar/war'dan çözülmüş gerçek imzayı ekler (Gereksinim 14). */
    private void disBagimlilik(Map<String, Object> m, String type, boolean internal,
                               String targetClass, String method, List<String> args) {
        if (!"call".equals(type) || internal || targetClass == null || "new".equals(method)) return;
        List<JavapResolver.MetodBilgi> metodlar = disMetodlar.get(targetClass);
        if (metodlar == null || metodlar.isEmpty()) return;
        int argc = args == null ? 0 : args.size();
        JavapResolver.MetodBilgi mb = JavapResolver.match(metodlar, method, argc);
        if (mb == null) return;
        Map<String, Object> d = new LinkedHashMap<>();
        put(d, "jar", mb.jar);
        put(d, "imza", mb.imza);
        put(d, "donusTipi", mb.donusTipi);
        d.put("statik", mb.statik);
        m.put("disBagimlilik", d);
    }

    // ---------------- yardımcılar ----------------

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }

    private static boolean isJdkNoise(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("jakarta.")
                || fqn.startsWith("org.slf4j") || fqn.startsWith("org.apache.logging")
                || fqn.startsWith("lombok.");
    }

    /** JSON çıktılarının bir LLM'e verilirken nasıl yorumlanacağını anlatan rehber. */
    private String llmGuide(ProjectModel model) {
        return """
            # %s — Analiz Çıktısı Rehberi (LLM için)

            Bu dizindeki dosyalar `%s` projesinin statik analiz sonucudur.
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
            """.formatted(model.projectName, model.projectName);
    }
}
