package com.preanalyzer.jar;

import com.preanalyzer.model.ProjectModel;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Proje çıktısındaki ve bağımlılık olarak gelen jar/war dosyalarını tarar;
 * hangi sınıfın (FQN) hangi jar içinde olduğunu indeksler.
 *
 * Spring Boot fat-jar (BOOT-INF/lib) ve WAR (WEB-INF/lib) içindeki gömülü
 * jar'lar geçici bir dizine açılır ve onlar da indekslenir; böylece kaynakta
 * olmayan alt metodlar bile "jar veya war içeriğinden" bulunabilir (Gereksinim 14).
 */
public class JarIndex {

    /** sınıf FQN -> javap ile okunabilir gerçek jar yolu */
    private final Map<String, Path> classToJar = new LinkedHashMap<>();
    /** tüm aday jar yolları (gömülüler açılmış hâliyle) */
    private final Set<Path> allJars = new LinkedHashSet<>();
    private Path tempDir;

    public Path jarOf(String fqn) { return classToJar.get(fqn); }
    public boolean contains(String fqn) { return classToJar.containsKey(fqn); }
    public Set<Path> jars() { return allJars; }
    public int size() { return classToJar.size(); }

    /** Tek bir artefaktı (.war/.jar) doğrudan indeksler; gömülü lib jar'ları da açılır. */
    public void indexArtifact(Path artifact) {
        if (tempDir == null) {
            try { tempDir = Files.createTempDirectory("preanalyzer-jars-"); } catch (Exception ignore) { }
        }
        indexJar(artifact, true);
    }

    /** Proje köküne ve modüllere göre aday jar'ları toplar ve indeksler. */
    public void build(ProjectModel model, Path target) {
        List<Path> candidates = new ArrayList<>();
        // 1) proje derleme çıktıları (kendi fat/boot jar'ları — bağımlılıkları da içerir)
        for (ProjectModel.ModuleInfo m : model.modules) {
            Path mp = Path.of(m.path);
            addJarsIn(candidates, mp.resolve("target"));
            addJarsIn(candidates, mp.resolve("build").resolve("libs"));
            addJarsIn(candidates, mp.resolve("bin").resolve("build").resolve("libs"));
            addJarsIn(candidates, mp.resolve("lib"));
            addJarsIn(candidates, mp.resolve("libs"));
        }
        // 2) yerel Maven deposundan beyan edilmiş bağımlılıklar
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        if (Files.isDirectory(m2) && model.build != null) {
            for (ProjectModel.Dependency d : model.build.dependencies) {
                Path jar = mavenJar(m2, d);
                if (jar != null) candidates.add(jar);
            }
        }
        // 3) Gradle önbelleği (modules-2) — beyan edilmiş bağımlılıkları ada göre ara
        Path gradleCache = Path.of(System.getProperty("user.home"), ".gradle", "caches",
                "modules-2", "files-2.1");
        if (Files.isDirectory(gradleCache) && model.build != null) {
            for (ProjectModel.Dependency d : model.build.dependencies) {
                Path jar = gradleJar(gradleCache, d);
                if (jar != null) candidates.add(jar);
            }
        }

        try {
            tempDir = Files.createTempDirectory("preanalyzer-jars-");
        } catch (Exception e) {
            tempDir = null;
        }
        for (Path jar : candidates) {
            indexJar(jar, true);
        }
    }

    private void addJarsIn(List<Path> out, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return (n.endsWith(".jar") || n.endsWith(".war")) && !n.endsWith("-plain.jar")
                        && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
            }).forEach(out::add);
        } catch (Exception ignore) { }
    }

    private Path mavenJar(Path repo, ProjectModel.Dependency d) {
        if (d.groupId == null || d.artifactId == null || d.version == null) return null;
        Path dir = repo;
        for (String seg : d.groupId.split("\\.")) dir = dir.resolve(seg);
        dir = dir.resolve(d.artifactId).resolve(d.version);
        Path jar = dir.resolve(d.artifactId + "-" + d.version + ".jar");
        return Files.exists(jar) ? jar : null;
    }

    private Path gradleJar(Path cache, ProjectModel.Dependency d) {
        if (d.groupId == null || d.artifactId == null) return null;
        Path dir = cache.resolve(d.groupId).resolve(d.artifactId);
        if (!Files.isDirectory(dir)) return null;
        // .../<version>/<hash>/<artifact>-<version>.jar
        try (var versions = Files.list(dir)) {
            for (Path vdir : (Iterable<Path>) versions::iterator) {
                if (!Files.isDirectory(vdir)) continue;
                Path found = findJarRecursive(vdir, d.artifactId);
                if (found != null) return found;
            }
        } catch (Exception ignore) { }
        return null;
    }

    private Path findJarRecursive(Path dir, String artifactId) {
        try (var s = Files.walk(dir, 3)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jar") && n.startsWith(artifactId)
                        && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
            }).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Bir jar/war'ı indeksler; gömülü lib jar'larını açıp onları da indeksler. */
    private void indexJar(Path jar, boolean expandNested) {
        if (jar == null || !Files.isRegularFile(jar) || !allJars.add(jar)) return;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');  // uyumsuz arşivlere karşı güvenlik
                if (name.endsWith(".class") && !name.contains("module-info")) {
                    String fqn = classFqnFromEntry(name);
                    if (fqn != null) classToJar.putIfAbsent(fqn, jar);
                } else if (expandNested && name.endsWith(".jar")
                        && (name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/")
                            || name.startsWith("lib/"))) {
                    Path extracted = extractNested(zf, e);
                    if (extracted != null) indexJar(extracted, false);
                }
            }
        } catch (Exception ignore) { }
    }

    private Path extractNested(ZipFile zf, ZipEntry e) {
        if (tempDir == null) return null;
        try {
            String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
            Path out = tempDir.resolve(base);
            if (Files.exists(out)) return out;
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, out);
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    /** "BOOT-INF/classes/com/x/Foo.class" veya "com/x/Foo.class" -> com.x.Foo */
    private String classFqnFromEntry(String entry) {
        String n = entry.substring(0, entry.length() - ".class".length());
        for (String prefix : new String[]{"BOOT-INF/classes/", "WEB-INF/classes/"}) {
            if (n.startsWith(prefix)) { n = n.substring(prefix.length()); break; }
        }
        if (n.startsWith("META-INF/")) return null;
        return n.replace('/', '.');
    }
}
