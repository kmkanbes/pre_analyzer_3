package com.preanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Analizin kök modeli: tüm çıktı dosyaları bu modelden üretilir. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectModel {
    public String projectName;
    public String rootPath;
    public String analyzedAt;

    public BuildInfo build = new BuildInfo();
    public FrameworkInfo framework = new FrameworkInfo();
    public Map<String, String> appConfig = new LinkedHashMap<>();
    public List<ModuleInfo> modules = new ArrayList<>();
    public List<ClassModel> classes = new ArrayList<>();
    public List<EntryPoint> entryPoints = new ArrayList<>();
    public CallGraph callGraph = new CallGraph();
    /** layer adı -> sınıf FQN listesi (kroki için katman haritası) */
    public Map<String, List<String>> layers = new LinkedHashMap<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BuildInfo {
        public String tool;            // maven | gradle | unknown
        public String groupId;
        public String artifactId;
        public String version;
        public String packaging;       // jar | war
        public String finalArtifact;   // ör: borsaveriyazma-0.0.1-SNAPSHOT.war
        public String javaVersion;
        public boolean multiModule;
        public List<Dependency> dependencies = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Dependency {
        public String groupId;
        public String artifactId;
        public String version;
        public String scope;

        public Dependency() {}
        public Dependency(String g, String a, String v, String s) {
            groupId = g; artifactId = a; version = v; scope = s;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FrameworkInfo {
        /** En belirgin çerçeve: spring-boot | spring | quarkus | jakarta-ee | servlet | java-web | java-se */
        public String primaryFramework;
        /** Tespit edilen tüm yığınlar: spring-boot, spring, quarkus, jakarta-ee, jax-rs, servlet, cdi, ejb ... */
        public List<String> stacks = new ArrayList<>();

        public boolean springBoot;
        public String springBootVersion;
        public boolean spring;               // Spring çekirdeği (boot olmasa da)
        public boolean quarkus;
        public String quarkusVersion;
        public boolean jakartaEe;            // Jakarta/Java EE şemsiye API
        public boolean jaxrs;                // JAX-RS / Jakarta REST (Jersey, RESTEasy, Quarkus REST)
        public boolean servletApi;           // Servlet API (servlet/filter/listener)
        public boolean cdi;                  // CDI (Jakarta/Java EE bean yönetimi)
        public boolean ejb;                  // EJB (session/MDB bean'leri)

        public String packaging;            // jar | war
        public boolean servletInitializer;  // WAR deploy için SpringBootServletInitializer var mı
        public boolean hasWebXml;
        public List<String> starters = new ArrayList<>();
        /** web, jpa, kafka, scheduling, security, actuator, webflux ... */
        public List<String> features = new ArrayList<>();
        public String serverPort;
        public String applicationName;
        public String contextPath;
        public String datasourceUrl;
        public String mainClass;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModuleInfo {
        public String name;
        public String path;
        public String packaging;
        public List<String> sourceRoots = new ArrayList<>();
        public List<String> resourceRoots = new ArrayList<>();
    }
}
