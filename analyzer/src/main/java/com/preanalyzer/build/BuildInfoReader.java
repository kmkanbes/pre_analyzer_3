package com.preanalyzer.build;

import com.preanalyzer.model.ProjectModel;
import com.preanalyzer.model.ProjectModel.BuildInfo;
import com.preanalyzer.model.ProjectModel.Dependency;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pom.xml (DOM) veya build.gradle / build.gradle.kts (regex) dosyalarından
 * build bilgilerini çıkarır: koordinatlar, packaging (jar/war), Spring Boot sürümü,
 * Java sürümü, bağımlılıklar.
 */
public class BuildInfoReader {

    public BuildInfo read(Path moduleDir, ProjectModel.FrameworkInfo fw) {
        BuildInfo info = new BuildInfo();
        try {
            Path pom = moduleDir.resolve("pom.xml");
            Path gradle = moduleDir.resolve("build.gradle");
            Path gradleKts = moduleDir.resolve("build.gradle.kts");
            if (Files.exists(pom)) {
                info.tool = "maven";
                readPom(pom, info, fw);
            } else if (Files.exists(gradle)) {
                info.tool = "gradle";
                readGradle(gradle, moduleDir, info, fw);
            } else if (Files.exists(gradleKts)) {
                info.tool = "gradle";
                readGradle(gradleKts, moduleDir, info, fw);
            } else {
                info.tool = "unknown";
            }
        } catch (Exception e) {
            System.err.println("[uyari] build dosyasi okunamadi: " + e.getMessage());
        }
        if (info.packaging == null) info.packaging = "jar";
        fw.packaging = info.packaging;
        if (info.artifactId != null) {
            String ver = info.version != null ? "-" + info.version : "";
            info.finalArtifact = info.artifactId + ver + "." + info.packaging;
        }
        return info;
    }

    // ---- Maven ----

    private void readPom(Path pom, BuildInfo info, ProjectModel.FrameworkInfo fw) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document doc = dbf.newDocumentBuilder().parse(pom.toFile());
        Element project = doc.getDocumentElement();

        info.groupId = childText(project, "groupId");
        info.artifactId = childText(project, "artifactId");
        info.version = childText(project, "version");
        String packaging = childText(project, "packaging");
        info.packaging = packaging != null ? packaging : "jar";

        Element parent = childElement(project, "parent");
        if (parent != null) {
            String pArtifact = childText(parent, "artifactId");
            if (info.groupId == null) info.groupId = childText(parent, "groupId");
            if (info.version == null) info.version = childText(parent, "version");
            if ("spring-boot-starter-parent".equals(pArtifact)) {
                fw.springBoot = true;
                fw.springBootVersion = childText(parent, "version");
            }
        }

        Element props = childElement(project, "properties");
        if (props != null) {
            String jv = childText(props, "java.version");
            if (jv == null) jv = childText(props, "maven.compiler.release");
            if (jv == null) jv = childText(props, "maven.compiler.source");
            info.javaVersion = jv;
            // Quarkus genelde sürümü property üzerinden taşır
            String qv = childText(props, "quarkus.platform.version");
            if (qv == null) qv = childText(props, "quarkus.version");
            if (qv != null) { fw.quarkus = true; fw.quarkusVersion = qv; }
            // Plain Spring projeleri sürümü property'de tutabilir
            if (childText(props, "spring.version") != null
                    || childText(props, "spring-framework.version") != null) {
                fw.spring = true;
            }
        }

        Element deps = childElement(project, "dependencies");
        if (deps != null) {
            NodeList list = deps.getElementsByTagName("dependency");
            for (int i = 0; i < list.getLength(); i++) {
                Element d = (Element) list.item(i);
                Dependency dep = new Dependency(
                        childText(d, "groupId"), childText(d, "artifactId"),
                        childText(d, "version"), childText(d, "scope"));
                info.dependencies.add(dep);
                noteDependency(dep.groupId, dep.artifactId, dep.version, fw);
            }
        }

        Element modules = childElement(project, "modules");
        info.multiModule = modules != null && modules.getElementsByTagName("module").getLength() > 0;
    }

    /** Doğrudan alt eleman metni (iç içe dependency'lerin alanlarına karışmamak için). */
    private String childText(Element parent, String tag) {
        Element el = childElement(parent, tag);
        return el != null ? el.getTextContent().trim() : null;
    }

    private Element childElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                return (Element) n;
            }
        }
        return null;
    }

    // ---- Gradle ----

    private static final Pattern BOOT_PLUGIN = Pattern.compile(
            "id\\s*[('\"]+org\\.springframework\\.boot['\")]+\\s*version\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DEP_LINE = Pattern.compile(
            "(implementation|api|compileOnly|runtimeOnly|testImplementation|annotationProcessor|developmentOnly)" +
            "\\s*[('\"]+([^'\":]+):([^'\":]+)(?::([^'\"]+))?['\")]+");
    private static final Pattern GROUP = Pattern.compile("(?m)^\\s*group\\s*=?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern JAVA_VER = Pattern.compile("(?:languageVersion[^\\d]*|sourceCompatibility[^\\d]*)(\\d+)");

    private void readGradle(Path buildFile, Path moduleDir, BuildInfo info, ProjectModel.FrameworkInfo fw) throws IOException {
        String text = Files.readString(buildFile);

        Matcher m = BOOT_PLUGIN.matcher(text);
        if (m.find()) {
            fw.springBoot = true;
            fw.springBootVersion = m.group(1);
        }
        if (text.contains("io.quarkus")) {
            fw.quarkus = true;
            Matcher qm = Pattern.compile("quarkusPlatformVersion\\s*=?\\s*['\"]([^'\"]+)['\"]").matcher(text);
            if (qm.find()) fw.quarkusVersion = qm.group(1);
        }
        if (text.contains("id 'war'") || text.contains("id(\"war\")") || text.contains("apply plugin: 'war'")) {
            info.packaging = "war";
        }
        m = GROUP.matcher(text);
        if (m.find()) info.groupId = m.group(1);
        m = VERSION.matcher(text);
        if (m.find()) info.version = m.group(1);
        m = JAVA_VER.matcher(text);
        if (m.find()) info.javaVersion = m.group(1);

        // artifactId: settings.gradle rootProject.name, yoksa dizin adı
        info.artifactId = readGradleProjectName(moduleDir);
        if (info.artifactId == null) info.artifactId = moduleDir.getFileName().toString();

        m = DEP_LINE.matcher(text);
        while (m.find()) {
            String scope = m.group(1);
            Dependency dep = new Dependency(m.group(2), m.group(3), m.group(4),
                    scope.startsWith("test") ? "test" : null);
            info.dependencies.add(dep);
            noteDependency(dep.groupId, dep.artifactId, dep.version, fw);
        }
        info.multiModule = Files.exists(moduleDir.resolve("settings.gradle"))
                && includesSubprojects(moduleDir.resolve("settings.gradle"));
    }

    private String readGradleProjectName(Path moduleDir) throws IOException {
        for (String name : new String[]{"settings.gradle", "settings.gradle.kts"}) {
            Path settings = moduleDir.resolve(name);
            if (Files.exists(settings)) {
                Matcher m = Pattern.compile("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]")
                        .matcher(Files.readString(settings));
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private boolean includesSubprojects(Path settings) throws IOException {
        return Files.readString(settings).contains("include");
    }

    // ---- ortak ----

    /**
     * Tek bir bağımlılığı çerçeve sezgilerine dönüştürür. Spring Boot'a özel değildir:
     * grup kimliği ve artifact adından Spring, Quarkus, Jakarta/Java EE, JAX-RS, Servlet,
     * CDI ve EJB yığınlarını ayırt eder.
     */
    private void noteDependency(String groupId, String artifactId, String version, ProjectModel.FrameworkInfo fw) {
        if (artifactId == null) return;
        String g = groupId == null ? "" : groupId;

        // ---- Spring Boot ----
        if (artifactId.startsWith("spring-boot-starter")) {
            fw.springBoot = true;
            fw.spring = true;
            if (!fw.starters.contains(artifactId)) fw.starters.add(artifactId);
        }
        if (g.equals("org.springframework.boot")) fw.springBoot = true;

        // ---- Spring (boot olmadan da) ----
        if (g.startsWith("org.springframework")) fw.spring = true;
        addFeature(fw, artifactId, "spring-webmvc", "web (Spring MVC)");
        addFeature(fw, artifactId, "spring-boot-starter-web", "web (Spring MVC)");
        addFeature(fw, artifactId, "spring-boot-starter-webflux", "webflux (reaktif)");
        addFeature(fw, artifactId, "spring-webflux", "webflux (reaktif)");
        addFeature(fw, artifactId, "spring-boot-starter-data-jpa", "jpa (veritabani)");
        addFeature(fw, artifactId, "spring-boot-starter-data-mongodb", "mongodb");
        addFeature(fw, artifactId, "spring-boot-starter-security", "security");
        addFeature(fw, artifactId, "spring-boot-starter-actuator", "actuator");
        addFeature(fw, artifactId, "spring-boot-starter-validation", "validation");
        addFeature(fw, artifactId, "spring-kafka", "kafka");
        addFeature(fw, artifactId, "spring-boot-starter-amqp", "rabbitmq");
        addFeature(fw, artifactId, "spring-boot-starter-cache", "cache");
        addFeature(fw, artifactId, "spring-boot-starter-batch", "batch");
        addFeature(fw, artifactId, "spring-cloud-starter-openfeign", "feign (http istemci)");
        addFeature(fw, artifactId, "spring-boot-starter-quartz", "quartz scheduler");

        // ---- Quarkus ----
        if (g.startsWith("io.quarkus")) {
            fw.quarkus = true;
            if (fw.quarkusVersion == null && version != null) fw.quarkusVersion = version;
            if (artifactId.contains("resteasy") || artifactId.equals("quarkus-rest")
                    || artifactId.contains("rest-jackson") || artifactId.contains("jaxrs")) {
                fw.jaxrs = true; addUnique(fw, "jax-rs (Quarkus REST)");
            }
            if (artifactId.contains("hibernate-orm") || artifactId.contains("panache")) addUnique(fw, "jpa (veritabani)");
            if (artifactId.contains("scheduler")) addUnique(fw, "scheduling (Quarkus)");
            if (artifactId.contains("reactive-messaging") || artifactId.contains("smallrye-reactive")) addUnique(fw, "messaging (reaktif)");
            if (artifactId.contains("kafka")) addUnique(fw, "kafka");
            if (artifactId.contains("undertow") || artifactId.contains("servlet")) fw.servletApi = true;
        }

        // ---- Jakarta / Java EE şemsiye ----
        if (artifactId.equals("jakarta.jakartaee-api") || artifactId.equals("jakarta.jakartaee-web-api")
                || artifactId.equals("javaee-api") || artifactId.equals("javaee-web-api")) {
            fw.jakartaEe = true; fw.servletApi = true; fw.jaxrs = true; fw.cdi = true; fw.ejb = true;
            addUnique(fw, "jakarta-ee (tam profil)");
        }

        // ---- Servlet API ----
        if (artifactId.equals("jakarta.servlet-api") || artifactId.equals("javax.servlet-api")
                || artifactId.equals("servlet-api") || g.equals("javax.servlet")) {
            fw.servletApi = true; addUnique(fw, "servlet");
        }

        // ---- JAX-RS (Jersey / RESTEasy / API) ----
        if (artifactId.equals("jakarta.ws.rs-api") || artifactId.equals("javax.ws.rs-api")
                || artifactId.equals("jsr311-api") || g.startsWith("org.glassfish.jersey")
                || g.startsWith("org.jboss.resteasy") || artifactId.startsWith("resteasy")
                || artifactId.startsWith("jersey")) {
            fw.jaxrs = true; addUnique(fw, "jax-rs");
        }

        // ---- CDI ----
        if (artifactId.contains("cdi-api") || g.equals("jakarta.enterprise")
                || g.equals("javax.enterprise") || g.startsWith("org.jboss.weld")) {
            fw.cdi = true; addUnique(fw, "cdi");
        }

        // ---- EJB ----
        if (artifactId.equals("jakarta.ejb-api") || artifactId.equals("javax.ejb-api") || g.equals("javax.ejb")) {
            fw.ejb = true; addUnique(fw, "ejb");
        }

        // ---- JMS ----
        if (artifactId.contains("jms-api") || g.equals("javax.jms")) addUnique(fw, "jms");

        // ---- ortak ----
        addFeature(fw, artifactId, "lombok", "lombok");
        if (artifactId.equals("postgresql") || artifactId.equals("mysql-connector-j")
                || artifactId.equals("ojdbc11") || artifactId.equals("ojdbc8")
                || artifactId.equals("h2") || artifactId.equals("mssql-jdbc")) {
            addUnique(fw, "jdbc-driver: " + artifactId);
        }
    }

    private void addFeature(ProjectModel.FrameworkInfo fw, String artifactId, String match, String feature) {
        if (artifactId.equals(match)) addUnique(fw, feature);
    }

    private void addUnique(ProjectModel.FrameworkInfo fw, String feature) {
        if (!fw.features.contains(feature)) fw.features.add(feature);
    }
}
