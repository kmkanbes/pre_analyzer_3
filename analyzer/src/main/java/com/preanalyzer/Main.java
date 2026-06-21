package com.preanalyzer;

import com.preanalyzer.build.AppConfigReader;
import com.preanalyzer.build.ArtifactReader;
import com.preanalyzer.build.BuildInfoReader;
import com.preanalyzer.build.WebXmlReader;
import com.preanalyzer.detect.EntryPointDetector;
import com.preanalyzer.detect.Linker;
import com.preanalyzer.detect.StereotypeDetector;
import com.preanalyzer.graph.CallGraphBuilder;
import com.preanalyzer.graph.FlowTracer;
import com.preanalyzer.graph.MethodResolver;
import com.preanalyzer.jar.ExternalCallEnricher;
import com.preanalyzer.jar.JarIndex;
import com.preanalyzer.jar.JavapResolver;
import com.preanalyzer.model.ClassModel;
import com.preanalyzer.model.ProjectModel;
import com.preanalyzer.parse.BytecodeAnalyzer;
import com.preanalyzer.parse.SourceAnalyzer;
import com.preanalyzer.report.HtmlReport;
import com.preanalyzer.report.JsonWriter;
import com.preanalyzer.scan.ProjectScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Kullanım: java -jar pre-analyzer.jar <hedef-proje-dizini> [--out <çıktı-dizini>]
 *
 * Verilen dizindeki Java / Spring Boot projesini statik olarak analiz eder;
 * giriş noktalarını, metod akışlarını, çağrı grafiğini ve framework künyesini çıkarır.
 * Çıktı: JSON dosyaları + etkileşimli HTML kroki raporu.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println("""
                Kullanim: java -jar pre-analyzer.jar <hedef> [--out <cikti-dizini>]

                  <hedef>      Kaynak proje dizini (src/main/java) YA DA .war / .jar dosyasi
                               (artefakt; kaynak kod olmadan bytecode'dan analiz edilir)
                  --out <dizin>  Cikti dizini (varsayilan: ./output)

                Cikti: <cikti>/<proje>_analiz/ altinda
                  report.html               Etkilesimli kroki + akis + cagri grafigi raporu
                  proje.json                Build/framework kunyesi, moduller, katmanlar, config
                  siniflar.json             Tum siniflarin ozet katalogu (imza duzeyi, akis YOK)
                  baslangic_noktalari.json  Giris noktalari listesi + gereken bagimliliklar + akis parca referansi
                  baslangic_akis/<sinif>.json  Giris sinifi basina adim adim detayli akis
                """);
            return;
        }

        Path target = Path.of(args[0]).toAbsolutePath().normalize();
        boolean artifactMode = Files.isRegularFile(target) && isArtifact(target);
        if (!Files.isDirectory(target) && !artifactMode) {
            System.err.println("HATA: kaynak dizini ya da .war/.jar dosyasi bulunamadi: " + target);
            System.exit(1);
        }
        Path outBase = Path.of("output");
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--out")) outBase = Path.of(args[i + 1]);
        }

        long start = System.currentTimeMillis();
        System.out.println("Analiz basliyor: " + target + (artifactMode ? "  (derlenmis artefakt)" : ""));

        ProjectModel model = new ProjectModel();
        model.rootPath = target.toString();
        model.analyzedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // 1-4) sinif modelini uret (kaynak kodundan ya da derlenmis artefakttan)
        List<ClassModel> classes = artifactMode ? analyzeArtifact(target, model) : analyzeSource(target, model);
        model.classes = classes;
        System.out.println("  sinif sayisi: " + classes.size());

        // 5) stereotype + baglama
        new StereotypeDetector().assign(classes);
        new Linker().link(classes);

        // framework detaylari (kaynaktan)
        enrichFrameworkFromSources(model, classes);

        // web.xml (klasik servlet konfigürasyonu) — Spring Boot dışı projelerin giriş haritası
        WebXmlReader.WebDescriptor web = new WebXmlReader().read(model.modules);
        if (web.present) {
            model.framework.hasWebXml = true;
            model.framework.servletApi = true;
            System.out.println("  web.xml: " + web.servlets.size() + " servlet, "
                    + web.filters.size() + " filter, " + web.listeners.size() + " listener");
        }

        // 6) giris noktalari
        model.entryPoints = new EntryPointDetector().detect(classes, web);
        finalizeFramework(model);
        System.out.println("  cerceve: " + model.framework.primaryFramework
                + (model.framework.stacks.isEmpty() ? "" : " " + model.framework.stacks));
        System.out.println("  giris noktasi: " + model.entryPoints.size());

        // 7) cagri grafigi + akis izleme
        MethodResolver resolver = new MethodResolver(classes);
        model.callGraph = new CallGraphBuilder().build(classes, model.entryPoints, resolver);
        new FlowTracer(resolver).traceAll(model.entryPoints);
        System.out.println("  cagri grafigi: " + model.callGraph.nodes.size() + " dugum, "
                + model.callGraph.edges.size() + " kenar");

        // 8) katman haritasi
        for (ClassModel c : classes) {
            model.layers.computeIfAbsent(c.stereotype, k -> new java.util.ArrayList<>()).add(c.fqn);
        }

        // 9) kaynakta olmayan alt metodlari jar/war iceriginden coz (javap)
        JarIndex jarIndex = new JarIndex();
        if (artifactMode) {
            jarIndex.indexArtifact(target);       // analiz edilen artefakt zaten tum bagimliliklari icerir
        } else {
            jarIndex.build(model, target);
        }
        java.util.Map<String, List<JavapResolver.MetodBilgi>> disMetodlar = java.util.Map.of();
        if (jarIndex.size() > 0) {
            System.out.println("  jar/war indeksi: " + jarIndex.size() + " sinif, " + jarIndex.jars().size() + " arsiv");
            disMetodlar = ExternalCallEnricher.enrich(model.entryPoints, new JavapResolver(jarIndex));
            System.out.println("  jar/war'dan cozulen harici sinif: " + disMetodlar.size());
        } else {
            System.out.println("  jar/war bulunamadi; harici metodlar isim sezgisiyle birakildi.");
        }

        // 10) ciktilar — artefakt analizi ayri klasore yazilir ki onceki kaynak analiziyle karismasin
        String suffix = artifactMode ? "_" + model.build.packaging + "_analiz" : "_analiz";
        Path outDir = outBase.toAbsolutePath().resolve(model.projectName + suffix);
        Files.createDirectories(outDir);
        new JsonWriter().writeAll(model, outDir, disMetodlar);
        new HtmlReport().write(model, outDir.resolve("report.html"));

        long ms = System.currentTimeMillis() - start;
        System.out.println();
        System.out.println("Analiz tamamlandi (" + ms + " ms). Cikti: " + outDir);
        System.out.println("  Rapor: " + outDir.resolve("report.html"));
    }

    private static boolean isArtifact(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".war") || n.endsWith(".jar");
    }

    /** Kaynak kod (src/main/java) tabanlı analiz: modüller, build, config, JavaParser. */
    private static List<ClassModel> analyzeSource(Path target, ProjectModel model) throws Exception {
        ProjectScanner scanner = new ProjectScanner();
        model.modules = scanner.scanModules(target);
        if (model.modules.isEmpty()) {
            System.err.println("HATA: " + target + " altinda Java kaynak kodu (src/main/java) bulunamadi.");
            System.exit(2);
        }
        System.out.println("  modul sayisi: " + model.modules.size());

        BuildInfoReader buildReader = new BuildInfoReader();
        model.build = buildReader.read(target, model.framework);
        if (model.build.artifactId == null && !model.modules.isEmpty()) {
            model.build = buildReader.read(Path.of(model.modules.get(0).path), model.framework);
        }
        for (ProjectModel.ModuleInfo m : model.modules) {
            Path mPath = Path.of(m.path);
            if (!mPath.equals(target)) {
                ProjectModel.BuildInfo sub = buildReader.read(mPath, model.framework);
                m.packaging = sub.packaging;
            } else {
                m.packaging = model.build.packaging;
            }
        }
        model.projectName = model.build.artifactId != null ? model.build.artifactId : target.getFileName().toString();

        AppConfigReader configReader = new AppConfigReader();
        for (ProjectModel.ModuleInfo m : model.modules) {
            for (String res : m.resourceRoots) {
                configReader.read(Path.of(res), model.appConfig, model.framework);
            }
        }
        System.out.println("  kaynak kod ayristiriliyor...");
        return new SourceAnalyzer().analyze(model.modules);
    }

    /** Derlenmiş artefakt (.war/.jar) tabanlı analiz: bytecode + reflection + javap. */
    private static List<ClassModel> analyzeArtifact(Path artifact, ProjectModel model) throws Exception {
        System.out.println("  artefakt aciliyor...");
        ArtifactReader.Layout lay = new ArtifactReader().read(artifact, model);
        System.out.println("  paketleme: " + lay.packaging + ", paketlenmis kutuphane: " + lay.libJars.size());

        // sentetik modül kaydı (konfig okuma + çıktı tutarlılığı için)
        ProjectModel.ModuleInfo m = new ProjectModel.ModuleInfo();
        m.name = model.projectName;
        m.path = lay.tempRoot.toString();
        m.packaging = lay.packaging;
        m.sourceRoots.add(lay.classesRoot.toString());
        m.resourceRoots.add(lay.resourceRoot.toString());
        model.modules.add(m);

        new AppConfigReader().read(lay.resourceRoot, model.appConfig, model.framework);

        System.out.println("  bytecode ayristiriliyor (reflection + javap)...");
        return new BytecodeAnalyzer(lay).analyze();
    }

    /** Kaynak koddan framework detaylarını tamamla: main class, servlet initializer, stack sezgileri vb. */
    private static void enrichFrameworkFromSources(ProjectModel model, List<ClassModel> classes) {
        ProjectModel.FrameworkInfo fw = model.framework;
        for (ClassModel c : classes) {
            boolean isMain = c.annotations.stream().anyMatch(a -> a.startsWith("@SpringBootApplication"));
            if (isMain) {
                fw.springBoot = true;
                fw.spring = true;
                fw.mainClass = c.fqn;
            }
            if (c.extendsTypes.stream().anyMatch(t -> t.contains("SpringBootServletInitializer"))) {
                fw.servletInitializer = true;
            }
            if (c.annotations.stream().anyMatch(a -> a.contains("EnableScheduling"))) {
                addFeature(model, "scheduling (@EnableScheduling)");
            }
            if (c.annotations.stream().anyMatch(a -> a.contains("EnableFeignClients"))) {
                addFeature(model, "feign (http istemci)");
            }
            if (c.annotations.stream().anyMatch(a -> a.contains("EnableAsync"))) {
                addFeature(model, "async (@EnableAsync)");
            }
            // ---- stack sezgileri (bağımlılık ayrıştırma eksik kalsa da kaynaktan yakala) ----
            if (annoAny(c, "@Controller", "@RestController", "@Service", "@Repository",
                    "@Component", "@Configuration", "@SpringBootApplication")) {
                fw.spring = true;
            }
            if (annoAny(c, "@Path") || c.methods.stream().anyMatch(m ->
                    annoAnyOn(m.annotations, "@GET", "@POST", "@PUT", "@DELETE", "@PATCH", "@HEAD", "@OPTIONS"))) {
                fw.jaxrs = true;
            }
            if (annoAny(c, "@WebServlet", "@WebFilter", "@WebListener")
                    || extendsContains(c, "HttpServlet", "GenericServlet")
                    || implementsContains(c, "Filter", "ServletContextListener", "ServletRequestListener",
                            "HttpSessionListener")) {
                fw.servletApi = true;
            }
            if (annoAny(c, "@Stateless", "@Stateful", "@MessageDriven")) fw.ejb = true;
            if (annoAny(c, "@ApplicationScoped", "@RequestScoped", "@SessionScoped", "@Named", "@Dependent")) {
                fw.cdi = true;
            }
        }
        // kaynakta @Scheduled varsa build'de gorunmese de scheduling ozelligi say
        boolean hasScheduled = classes.stream().anyMatch(c -> c.methods.stream()
                .anyMatch(mm -> mm.annotations.stream().anyMatch(a -> a.startsWith("@Scheduled"))));
        if (hasScheduled) addFeature(model, "scheduling (@Scheduled)");
    }

    /** Tespit edilen bayraklardan birincil çerçeveyi ve yığın listesini türet. */
    private static void finalizeFramework(ProjectModel model) {
        ProjectModel.FrameworkInfo fw = model.framework;
        List<String> stacks = fw.stacks;
        if (fw.springBoot) addStack(stacks, "spring-boot");
        else if (fw.spring) addStack(stacks, "spring");
        if (fw.quarkus) addStack(stacks, "quarkus");
        if (fw.jakartaEe) addStack(stacks, "jakarta-ee");
        if (fw.jaxrs) addStack(stacks, "jax-rs");
        if (fw.servletApi || fw.hasWebXml) addStack(stacks, "servlet");
        if (fw.cdi) addStack(stacks, "cdi");
        if (fw.ejb) addStack(stacks, "ejb");

        boolean hasMain = model.entryPoints.stream().anyMatch(e -> "MAIN".equals(e.kind));
        if (fw.springBoot) fw.primaryFramework = "spring-boot";
        else if (fw.quarkus) fw.primaryFramework = "quarkus";
        else if (fw.jakartaEe) fw.primaryFramework = "jakarta-ee";
        else if (fw.spring) fw.primaryFramework = "spring";
        else if (fw.jaxrs) fw.primaryFramework = "jax-rs";
        else if (fw.servletApi || fw.hasWebXml) fw.primaryFramework = "servlet";
        else if (hasMain) fw.primaryFramework = "java-se";
        else fw.primaryFramework = "java";
    }

    private static void addStack(List<String> stacks, String s) {
        if (!stacks.contains(s)) stacks.add(s);
    }

    private static boolean annoAny(ClassModel c, String... names) {
        return annoAnyOn(c.annotations, names);
    }

    private static boolean annoAnyOn(List<String> annotations, String... names) {
        for (String a : annotations) {
            for (String n : names) {
                if (a.equals(n) || a.startsWith(n + "(") || a.startsWith(n + " ")) return true;
            }
        }
        return false;
    }

    private static boolean extendsContains(ClassModel c, String... simples) {
        return c.extendsTypes.stream().anyMatch(t -> containsSimple(t, simples));
    }

    private static boolean implementsContains(ClassModel c, String... simples) {
        return c.implementsTypes.stream().anyMatch(t -> containsSimple(t, simples));
    }

    private static boolean containsSimple(String type, String... simples) {
        int lt = type.indexOf('<');
        if (lt >= 0) type = type.substring(0, lt);
        int dot = type.lastIndexOf('.');
        String simple = dot >= 0 ? type.substring(dot + 1) : type;
        for (String s : simples) if (simple.equals(s)) return true;
        return false;
    }

    private static void addFeature(ProjectModel model, String feature) {
        if (!model.framework.features.contains(feature)) model.framework.features.add(feature);
    }
}
