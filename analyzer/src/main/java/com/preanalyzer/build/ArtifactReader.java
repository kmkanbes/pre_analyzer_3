package com.preanalyzer.build;

import com.preanalyzer.model.ProjectModel;
import com.preanalyzer.model.ProjectModel.BuildInfo;
import com.preanalyzer.model.ProjectModel.Dependency;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Derlenmiş bir artefaktı (.war / .jar) açar ve yapısını çıkarır:
 * proje kendi sınıfları (WEB-INF/classes ya da BOOT-INF/classes ya da jar kökü),
 * paketlenmiş bağımlılık jar'ları (WEB-INF/lib / BOOT-INF/lib) ve künye bilgileri.
 *
 * Önemli: proje kendi sınıfları ile paketlenmiş 3. parti sınıflar kesin olarak
 * ayrılır; böylece bağımlılık sınıfları "proje sınıfı" sanılıp analize karışmaz.
 */
public class ArtifactReader {

    /** Açılmış artefaktın yerleşim bilgisi. */
    public static class Layout {
        public Path tempRoot;          // açılan kök dizin
        public Path classesRoot;       // projenin kendi .class köküne
        public Path resourceRoot;      // application.properties/yml burada aranır
        public List<Path> classpath = new ArrayList<>(); // classesRoot + lib jar'ları (reflection için)
        public List<Path> libJars = new ArrayList<>();
        public String packaging;       // war | jar
    }

    public Layout read(Path artifact, ProjectModel model) throws Exception {
        Layout lay = new Layout();
        lay.tempRoot = Files.createTempDirectory("preanalyzer-artifact-");
        unzip(artifact, lay.tempRoot);

        Path webInfClasses = lay.tempRoot.resolve("WEB-INF").resolve("classes");
        Path bootInfClasses = lay.tempRoot.resolve("BOOT-INF").resolve("classes");
        Path libDir;
        if (Files.isDirectory(webInfClasses)) {
            lay.packaging = "war";
            lay.classesRoot = webInfClasses;
            lay.resourceRoot = webInfClasses;
            libDir = lay.tempRoot.resolve("WEB-INF").resolve("lib");
        } else if (Files.isDirectory(bootInfClasses)) {
            lay.packaging = "jar";
            lay.classesRoot = bootInfClasses;
            lay.resourceRoot = bootInfClasses;
            libDir = lay.tempRoot.resolve("BOOT-INF").resolve("lib");
            model.framework.springBoot = true;
        } else {
            lay.packaging = artifact.getFileName().toString().toLowerCase().endsWith(".war") ? "war" : "jar";
            lay.classesRoot = lay.tempRoot;          // düz jar: kök = sınıf kökü
            lay.resourceRoot = lay.tempRoot;
            libDir = null;
        }

        lay.classpath.add(lay.classesRoot);
        if (libDir != null && Files.isDirectory(libDir)) {
            try (var s = Files.list(libDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(p -> {
                    lay.libJars.add(p);
                    lay.classpath.add(p);
                });
            }
        }

        fillBuild(artifact, lay, model);
        return lay;
    }

    private void fillBuild(Path artifact, Layout lay, ProjectModel model) {
        BuildInfo b = model.build;
        b.tool = "artifact";
        b.packaging = lay.packaging;
        model.framework.packaging = lay.packaging;
        b.finalArtifact = artifact.getFileName().toString();

        // MANIFEST: ana/başlangıç sınıfı, sürüm
        Manifest mf = readManifest(lay.tempRoot);
        if (mf != null) {
            Attributes a = mf.getMainAttributes();
            String start = a.getValue("Start-Class");
            String main = a.getValue("Main-Class");
            model.framework.mainClass = start != null ? start : main;
            String bootVer = a.getValue("Spring-Boot-Version");
            if (bootVer != null) { model.framework.springBoot = true; model.framework.springBootVersion = bootVer; }
            String implVer = a.getValue("Implementation-Version");
            if (implVer != null) b.version = implVer;
            String implTitle = a.getValue("Implementation-Title");
            if (implTitle != null && model.projectName == null) model.projectName = implTitle;
        }

        // gömülü pom.properties (groupId/artifactId/version)
        Properties pom = readEmbeddedPom(lay.tempRoot);
        if (pom != null) {
            if (b.groupId == null) b.groupId = pom.getProperty("groupId");
            if (pom.getProperty("artifactId") != null) {
                b.artifactId = pom.getProperty("artifactId");
                model.projectName = b.artifactId;
            }
            if (pom.getProperty("version") != null) b.version = pom.getProperty("version");
        }
        if (model.projectName == null) {
            String fn = artifact.getFileName().toString().replaceAll("\\.(war|jar)$", "");
            model.projectName = fn;
            if (b.artifactId == null) b.artifactId = fn;
        }

        // paketlenmiş bağımlılıklar = lib jar'ları (dosya adından artifactId-version)
        Pattern nv = Pattern.compile("^(.*?)-(\\d[\\w.\\-]*)\\.jar$");
        for (Path jar : lay.libJars) {
            String name = jar.getFileName().toString();
            Matcher m = nv.matcher(name);
            String artifactId = name, version = null;
            if (m.matches()) { artifactId = m.group(1); version = m.group(2); }
            b.dependencies.add(new Dependency(null, artifactId, version, null));
            noteFeature(artifactId, model.framework);
        }
    }

    private void noteFeature(String artifactId, ProjectModel.FrameworkInfo fw) {
        if (artifactId == null) return;
        if (artifactId.startsWith("spring-boot")) fw.springBoot = true;
        addIf(fw, artifactId.equals("spring-web") || artifactId.equals("spring-webmvc"), "web (Spring MVC)");
        addIf(fw, artifactId.equals("spring-webflux"), "webflux (reaktif)");
        addIf(fw, artifactId.contains("spring-data-jpa") || artifactId.equals("hibernate-core"), "jpa (veritabani)");
        addIf(fw, artifactId.startsWith("spring-kafka"), "kafka");
        addIf(fw, artifactId.startsWith("spring-security"), "security");
        addIf(fw, artifactId.equals("postgresql") || artifactId.equals("mysql-connector-j")
                || artifactId.startsWith("ojdbc") || artifactId.equals("h2") || artifactId.equals("mssql-jdbc"),
                "jdbc-driver: " + artifactId);
    }

    private void addIf(ProjectModel.FrameworkInfo fw, boolean cond, String feature) {
        if (cond && !fw.features.contains(feature)) fw.features.add(feature);
    }

    private Manifest readManifest(Path root) {
        Path mf = root.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(mf)) return null;
        try (InputStream in = Files.newInputStream(mf)) {
            return new Manifest(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** META-INF/maven/<g>/<a>/pom.properties — ilkini döndür. */
    private Properties readEmbeddedPom(Path root) {
        Path mavenDir = root.resolve("META-INF").resolve("maven");
        if (!Files.isDirectory(mavenDir)) return null;
        try (var s = Files.walk(mavenDir, 4)) {
            Path props = s.filter(p -> p.getFileName().toString().equals("pom.properties")).findFirst().orElse(null);
            if (props == null) return null;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(props)) { p.load(in); }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private void unzip(Path zip, Path dest) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) continue; // zip-slip koruması
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }
}
