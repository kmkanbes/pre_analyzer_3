package com.preanalyzer.detect;

import com.preanalyzer.model.ClassModel;

import java.util.List;

/**
 * Her sınıfa mimari rol (stereotype) atar: önce anotasyonlar,
 * sonra miras (Repository arayüzleri), sonra ad/paket sezgileri.
 */
public class StereotypeDetector {

    private static final List<String> REPO_BASES = List.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
            "MongoRepository", "ListCrudRepository", "ReactiveCrudRepository",
            "ElasticsearchRepository", "Repository");

    public void assign(List<ClassModel> classes) {
        for (ClassModel c : classes) {
            c.stereotype = detect(c);
        }
    }

    private static final List<String> SERVLET_BASES = List.of("HttpServlet", "GenericServlet");
    private static final List<String> LISTENER_IFACES = List.of(
            "ServletContextListener", "ServletContextAttributeListener",
            "ServletRequestListener", "ServletRequestAttributeListener",
            "HttpSessionListener", "HttpSessionAttributeListener",
            "HttpSessionActivationListener", "HttpSessionBindingListener",
            "HttpSessionIdListener", "AsyncListener");

    private String detect(ClassModel c) {
        // Spring MVC + JAX-RS/Jakarta REST kaynakları web denetleyici sayılır
        if (hasAnno(c, "RestController") || hasAnno(c, "Controller") || hasAnno(c, "Path")) return "controller";
        if (hasAnno(c, "SpringBootApplication")) return "main";

        // Servlet API rolleri (Spring dışı / klasik web)
        if (hasAnno(c, "WebServlet") || extendsAnySimple(c.extendsTypes, SERVLET_BASES)) return "servlet";
        if (hasAnno(c, "WebFilter") || implementsSimple(c.implementsTypes, "Filter")) return "filter";
        if (hasAnno(c, "WebListener") || implementsAnySimple(c.implementsTypes, LISTENER_IFACES)) return "listener";

        if (hasAnno(c, "Service")) return "service";
        // EJB bean'leri: oturum bean'leri servis, MDB dinleyici
        if (hasAnno(c, "Stateless") || hasAnno(c, "Stateful") || hasAnno(c, "Singleton")) return "service";
        if (hasAnno(c, "MessageDriven")) return "listener";
        if (hasAnno(c, "Repository")) return "repository";
        if (hasAnno(c, "FeignClient")) return "httpclient";
        if (hasAnno(c, "Configuration") || hasAnno(c, "ConfigurationProperties")) return "config";
        if (hasAnno(c, "Entity") || hasAnno(c, "Table") || hasAnno(c, "Document")) return "entity";
        if (hasAnno(c, "ControllerAdvice") || hasAnno(c, "RestControllerAdvice")) return "advice";
        if (hasAnno(c, "Provider")) return "config"; // JAX-RS provider (filter/mapper)

        if ("interface".equals(c.kind)) {
            for (String ext : c.extendsTypes) {
                String base = ext.contains("<") ? ext.substring(0, ext.indexOf('<')) : ext;
                if (REPO_BASES.contains(base)) return "repository";
            }
        }
        // Spring @Component veya CDI yönetilen bean'ler
        if (hasAnno(c, "Component") || hasAnno(c, "Named") || hasAnno(c, "ApplicationScoped")
                || hasAnno(c, "RequestScoped") || hasAnno(c, "SessionScoped") || hasAnno(c, "Dependent")) {
            if (c.methods.stream().anyMatch(m -> m.annotations.stream().anyMatch(a -> a.contains("Scheduled")))) {
                return "scheduler";
            }
            if (c.methods.stream().anyMatch(m -> m.annotations.stream().anyMatch(a -> a.contains("KafkaListener")))) {
                return "listener";
            }
            return "component";
        }

        String n = c.name;
        if (n.endsWith("Controller") || n.endsWith("Resource")) return "controller";
        if (n.endsWith("Service") || n.endsWith("Business") || n.endsWith("ServiceImpl") || n.endsWith("Manager")) return "service";
        if (n.endsWith("Repository") || n.endsWith("Dao")) return "repository";
        if (n.endsWith("Config") || n.endsWith("Configuration")) return "config";
        if (n.endsWith("Dto") || n.endsWith("DTO") || n.endsWith("Request") || n.endsWith("Response")
                || n.endsWith("Req") || n.endsWith("Res")) return "dto";
        if (n.endsWith("Entity")) return "entity";
        if (n.endsWith("Exception")) return "exception";
        if (n.endsWith("Util") || n.endsWith("Utils") || n.endsWith("Helper")) return "util";

        String pkg = lastSegment(c.packageName);
        switch (pkg) {
            case "controller", "controllers", "web", "rest", "api": return "controller";
            case "service", "services", "business", "logic", "usecase": return "service";
            case "repository", "repositories", "dao", "persistence": return "repository";
            case "model", "models", "dto", "domain", "entity", "entities", "pojo", "vo": return "model";
            case "config", "configuration": return "config";
            case "schedule", "scheduler", "job", "jobs", "task", "tasks": return "scheduler";
            case "util", "utils", "common", "helper": return "util";
            case "exception", "exceptions", "error": return "exception";
        }

        // sadece alan + getter/setter içeren sınıflar veri modeli sayılır
        if (!c.fields.isEmpty() && c.methods.stream().allMatch(m ->
                m.name.startsWith("get") || m.name.startsWith("set") || m.name.startsWith("is")
                        || m.name.equals("toString") || m.name.equals("equals") || m.name.equals("hashCode"))) {
            return "model";
        }
        return "other";
    }

    private boolean hasAnno(ClassModel c, String name) {
        return c.annotations.stream().anyMatch(a ->
                a.equals("@" + name) || a.startsWith("@" + name + "(") || a.startsWith("@" + name + " "));
    }

    private boolean extendsAnySimple(List<String> types, List<String> bases) {
        return types.stream().anyMatch(t -> bases.contains(simple(t)));
    }

    private boolean implementsSimple(List<String> types, String iface) {
        return types.stream().anyMatch(t -> iface.equals(simple(t)));
    }

    private boolean implementsAnySimple(List<String> types, List<String> ifaces) {
        return types.stream().anyMatch(t -> ifaces.contains(simple(t)));
    }

    /** Generic ve paket önekini soyulmuş basit ad: javax.servlet.Filter -> Filter */
    private String simple(String t) {
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private String lastSegment(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        int dot = pkg.lastIndexOf('.');
        return dot >= 0 ? pkg.substring(dot + 1) : pkg;
    }
}
