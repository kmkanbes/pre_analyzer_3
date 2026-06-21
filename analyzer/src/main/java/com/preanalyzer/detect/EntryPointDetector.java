package com.preanalyzer.detect;

import com.preanalyzer.build.WebXmlReader.WebDescriptor;
import com.preanalyzer.model.ClassModel;
import com.preanalyzer.model.EntryPoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uygulama giriş noktalarını bulur. Spring'e özel değildir; şu yığınları tanır:
 *   - Spring MVC (@GetMapping ...), Spring async (@Scheduled, @KafkaListener, @JmsListener, @EventListener)
 *   - JAX-RS / Jakarta REST (@Path + @GET/@POST ...) — Jakarta EE, Quarkus, Jersey, RESTEasy
 *   - Servlet API: @WebServlet / HttpServlet türevleri (doGet/doPost ...), @WebFilter / Filter, @WebListener / *Listener
 *   - web.xml ile tanımlı servlet/filter/listener'lar
 *   - EJB zamanlayıcı (@Schedule), MDB (@MessageDriven), MicroProfile messaging (@Incoming)
 *   - CommandLineRunner/ApplicationRunner ve main metodu
 */
public class EntryPointDetector {

    private static final List<String> MAPPINGS = List.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping");

    /** JAX-RS HTTP fiil anotasyonları (basit ad). */
    private static final List<String> JAXRS_VERBS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** Servlet servis metodu -> HTTP metodu. */
    private static final Map<String, String> SERVLET_METHODS = Map.of(
            "doGet", "GET", "doPost", "POST", "doPut", "PUT", "doDelete", "DELETE",
            "doHead", "HEAD", "doOptions", "OPTIONS", "doTrace", "TRACE", "service", "*");

    private static final List<String> SERVLET_BASES = List.of("HttpServlet", "GenericServlet");
    private static final List<String> FILTER_IFACES = List.of("Filter");
    private static final Set<String> LISTENER_IFACES = Set.of(
            "ServletContextListener", "ServletContextAttributeListener",
            "ServletRequestListener", "ServletRequestAttributeListener",
            "HttpSessionListener", "HttpSessionAttributeListener",
            "HttpSessionActivationListener", "HttpSessionBindingListener",
            "HttpSessionIdListener", "AsyncListener");
    private static final Set<String> LISTENER_METHODS = Set.of(
            "contextInitialized", "contextDestroyed", "requestInitialized", "requestDestroyed",
            "sessionCreated", "sessionDestroyed", "attributeAdded", "attributeRemoved",
            "attributeReplaced", "sessionWillPassivate", "sessionDidActivate",
            "valueBound", "valueUnbound", "sessionIdChanged");

    public List<EntryPoint> detect(List<ClassModel> classes) {
        return detect(classes, null);
    }

    public List<EntryPoint> detect(List<ClassModel> classes, WebDescriptor web) {
        List<EntryPoint> result = new ArrayList<>();
        Set<String> classesWithEntry = new LinkedHashSet<>(); // hangi sınıflar zaten giriş noktası üretti
        int[] seq = {0};

        for (ClassModel c : classes) {
            boolean springController = hasAnno(c, "RestController") || hasAnno(c, "Controller");
            String basePath = springController ? firstPath(find(c.annotations, "RequestMapping")) : "";

            boolean jaxrsResource = hasAnno(c, "Path") || hasAnyVerbMethod(c);
            String classPath = jaxrsResource ? firstPath(find(c.annotations, "Path")) : "";

            String webServletAnno = find(c.annotations, "WebServlet");
            boolean servletClass = webServletAnno != null || extendsAny(c, SERVLET_BASES)
                    || (web != null && web.servlets.containsKey(c.fqn));
            List<String> servletPatterns = servletClass ? servletPatterns(c, webServletAnno, web) : null;

            String webFilterAnno = find(c.annotations, "WebFilter");
            boolean filterClass = webFilterAnno != null || implementsAny(c, FILTER_IFACES)
                    || (web != null && web.filters.containsKey(c.fqn));
            List<String> filterPatterns = filterClass ? filterPatterns(c, webFilterAnno, web) : null;

            boolean listenerClass = hasAnno(c, "WebListener") || implementsAnyListener(c)
                    || (web != null && web.listeners.contains(c.fqn));
            String listenerDetail = listenerClass ? listenerIfaces(c) : null;

            boolean isRunner = c.implementsTypes.stream()
                    .anyMatch(t -> t.startsWith("CommandLineRunner") || t.startsWith("ApplicationRunner"));
            boolean isMdb = hasAnno(c, "MessageDriven");

            for (ClassModel.MethodModel m : c.methods) {
                EntryPoint ep = null;

                // ---- Spring MVC ----
                if (springController) {
                    for (String mapping : MAPPINGS) {
                        String anno = find(m.annotations, mapping);
                        if (anno == null) continue;
                        ep = base(c, m, "REST");
                        ep.httpMethod = httpMethodOf(mapping, anno);
                        String methodPath = firstPath(anno);
                        if (methodPath.isEmpty()) {
                            for (String other : MAPPINGS) {
                                String otherAnno = find(m.annotations, other);
                                if (otherAnno != null && !firstPath(otherAnno).isEmpty()) {
                                    methodPath = firstPath(otherAnno);
                                    break;
                                }
                            }
                        }
                        ep.path = joinPath(basePath, methodPath);
                        ep.source = "anotasyon";
                        break;
                    }
                }

                // ---- JAX-RS / Jakarta REST ----
                if (ep == null && jaxrsResource) {
                    for (String verb : JAXRS_VERBS) {
                        if (find(m.annotations, verb) == null) continue;
                        ep = base(c, m, "JAXRS");
                        ep.httpMethod = verb;
                        ep.path = joinPath(classPath, firstPath(find(m.annotations, "Path")));
                        ep.produces = mediaType(find(m.annotations, "Produces"), find(c.annotations, "Produces"));
                        ep.consumes = mediaType(find(m.annotations, "Consumes"), find(c.annotations, "Consumes"));
                        ep.source = "anotasyon";
                        break;
                    }
                }

                // ---- Servlet ----
                if (ep == null && servletClass && SERVLET_METHODS.containsKey(m.name)) {
                    ep = base(c, m, "SERVLET");
                    String hm = SERVLET_METHODS.get(m.name);
                    ep.httpMethod = "*".equals(hm) ? null : hm;
                    ep.urlPatterns = servletPatterns;
                    ep.detail = attr(webServletAnno, "name");
                    ep.source = (webServletAnno != null) ? "anotasyon" : (web != null && web.servlets.containsKey(c.fqn) ? "web.xml" : "kod");
                }

                // ---- Filter ----
                if (ep == null && filterClass && m.name.equals("doFilter")) {
                    ep = base(c, m, "FILTER");
                    ep.urlPatterns = filterPatterns;
                    ep.detail = attr(webFilterAnno, "filterName");
                    ep.source = (webFilterAnno != null) ? "anotasyon" : (web != null && web.filters.containsKey(c.fqn) ? "web.xml" : "kod");
                }

                // ---- Listener ----
                if (ep == null && listenerClass && LISTENER_METHODS.contains(m.name)) {
                    ep = base(c, m, "LISTENER");
                    ep.detail = listenerDetail;
                    ep.source = hasAnno(c, "WebListener") ? "anotasyon" : (web != null && web.listeners.contains(c.fqn) ? "web.xml" : "kod");
                }

                // ---- Zamanlayıcı (Spring @Scheduled, Quarkus @Scheduled, EJB @Schedule) ----
                String scheduled = find(m.annotations, "Scheduled");
                if (scheduled == null) scheduled = find(m.annotations, "Schedule");
                if (ep == null && scheduled != null) {
                    ep = base(c, m, "SCHEDULED");
                    ep.cron = attr(scheduled, "cron");
                    String rate = attr(scheduled, "fixedRate");
                    if (rate == null) rate = attr(scheduled, "fixedDelay");
                    if (rate == null) rate = attr(scheduled, "fixedRateString");
                    if (rate == null) rate = attr(scheduled, "every");        // Quarkus
                    ep.fixedRate = rate;
                    ep.source = "anotasyon";
                }

                // ---- Kafka ----
                String kafka = find(m.annotations, "KafkaListener");
                if (ep == null && kafka != null) {
                    ep = base(c, m, "KAFKA");
                    ep.topics = attr(kafka, "topics");
                    if (ep.topics == null) ep.topics = firstPath(kafka);
                    ep.source = "anotasyon";
                }

                // ---- JMS (Spring @JmsListener veya EJB MDB onMessage) ----
                String jms = find(m.annotations, "JmsListener");
                if (ep == null && jms != null) {
                    ep = base(c, m, "JMS");
                    ep.topics = attr(jms, "destination");
                    ep.source = "anotasyon";
                }
                if (ep == null && isMdb && m.name.equals("onMessage")) {
                    ep = base(c, m, "JMS");
                    ep.detail = "MessageDriven bean";
                    ep.source = "anotasyon";
                }

                // ---- MicroProfile / reaktif messaging (@Incoming) ----
                String incoming = find(m.annotations, "Incoming");
                if (ep == null && incoming != null) {
                    ep = base(c, m, "MESSAGING");
                    ep.topics = firstPath(incoming);
                    ep.source = "anotasyon";
                }

                // ---- Spring event listener ----
                if (ep == null && (find(m.annotations, "EventListener") != null
                        || find(m.annotations, "TransactionalEventListener") != null)) {
                    ep = base(c, m, "EVENT");
                    ep.source = "anotasyon";
                }

                // ---- Runner / main ----
                if (ep == null && isRunner && m.name.equals("run")) {
                    ep = base(c, m, "RUNNER");
                }
                if (ep == null && m.name.equals("main")
                        && m.params.size() == 1 && m.params.get(0).startsWith("String[]")) {
                    ep = base(c, m, "MAIN");
                }

                if (ep != null) {
                    ep.id = "ep_" + (++seq[0]);
                    result.add(ep);
                    classesWithEntry.add(c.fqn);
                }
            }

            // servlet/filter/listener sınıfı var ama ilgili metod gövdede bulunamadıysa sınıf düzeyinde işaretle
            if (servletClass && !classesWithEntry.contains(c.fqn)) {
                result.add(classLevel(c, "SERVLET", seq, servletPatterns, null, web, "servlets"));
                classesWithEntry.add(c.fqn);
            }
            if (filterClass && !classesWithEntry.contains(c.fqn)) {
                result.add(classLevel(c, "FILTER", seq, filterPatterns, null, web, "filters"));
                classesWithEntry.add(c.fqn);
            }
            if (listenerClass && !classesWithEntry.contains(c.fqn)) {
                result.add(classLevel(c, "LISTENER", seq, null, listenerDetail, web, "listeners"));
                classesWithEntry.add(c.fqn);
            }
        }

        // web.xml'de tanımlı ama kaynak sınıfı projede olmayan servlet/filter/listener'lar
        if (web != null) {
            addOrphans(result, web.servlets, "SERVLET", classesWithEntry, seq, true);
            addOrphans(result, web.filters, "FILTER", classesWithEntry, seq, true);
            for (String cls : web.listeners) {
                if (classesWithEntry.contains(cls)) continue;
                EntryPoint ep = orphan(cls, "LISTENER", seq);
                result.add(ep);
                classesWithEntry.add(cls);
            }
        }
        return result;
    }

    // ---- yardımcı üreticiler ----

    private EntryPoint base(ClassModel c, ClassModel.MethodModel m, String kind) {
        EntryPoint ep = new EntryPoint();
        ep.kind = kind;
        ep.classFqn = c.fqn;
        ep.className = c.name;
        ep.method = m.name;
        ep.signature = m.signature;
        ep.line = m.line;
        return ep;
    }

    private EntryPoint classLevel(ClassModel c, String kind, int[] seq, List<String> patterns,
                                  String detail, WebDescriptor web, String descKey) {
        EntryPoint ep = new EntryPoint();
        ep.id = "ep_" + (++seq[0]);
        ep.kind = kind;
        ep.classFqn = c.fqn;
        ep.className = c.name;
        ep.line = 0;
        ep.urlPatterns = patterns;
        ep.detail = detail;
        ep.source = (web != null && hasInDescriptor(web, descKey, c.fqn)) ? "web.xml" : "kod";
        return ep;
    }

    private void addOrphans(List<EntryPoint> result, Map<String, List<String>> map, String kind,
                            Set<String> seen, int[] seq, boolean withPatterns) {
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            EntryPoint ep = orphan(e.getKey(), kind, seq);
            if (withPatterns && !e.getValue().isEmpty()) ep.urlPatterns = new ArrayList<>(e.getValue());
            result.add(ep);
            seen.add(e.getKey());
        }
    }

    private EntryPoint orphan(String classFqn, String kind, int[] seq) {
        EntryPoint ep = new EntryPoint();
        ep.id = "ep_" + (++seq[0]);
        ep.kind = kind;
        ep.classFqn = classFqn;
        ep.className = simpleName(classFqn);
        ep.line = 0;
        ep.source = "web.xml";
        ep.detail = "kaynak kodu projede yok (harici/derlenmiş sınıf)";
        return ep;
    }

    private boolean hasInDescriptor(WebDescriptor web, String key, String fqn) {
        return switch (key) {
            case "servlets" -> web.servlets.containsKey(fqn);
            case "filters" -> web.filters.containsKey(fqn);
            case "listeners" -> web.listeners.contains(fqn);
            default -> false;
        };
    }

    // ---- sınıf düzeyi sezgiler ----

    private boolean hasAnyVerbMethod(ClassModel c) {
        return c.methods.stream().anyMatch(m ->
                JAXRS_VERBS.stream().anyMatch(v -> find(m.annotations, v) != null));
    }

    private List<String> servletPatterns(ClassModel c, String anno, WebDescriptor web) {
        List<String> patterns = patternsFromAnno(anno);
        if (patterns.isEmpty() && web != null && web.servlets.containsKey(c.fqn)) {
            patterns = new ArrayList<>(web.servlets.get(c.fqn));
        }
        return patterns.isEmpty() ? null : patterns;
    }

    private List<String> filterPatterns(ClassModel c, String anno, WebDescriptor web) {
        List<String> patterns = patternsFromAnno(anno);
        if (patterns.isEmpty() && web != null && web.filters.containsKey(c.fqn)) {
            patterns = new ArrayList<>(web.filters.get(c.fqn));
        }
        return patterns.isEmpty() ? null : patterns;
    }

    /** @WebServlet("/x") | urlPatterns={"/a","/b"} | value="/x" -> url listesi. */
    private List<String> patternsFromAnno(String anno) {
        List<String> out = new ArrayList<>();
        if (anno == null) return out;
        Matcher m = Pattern.compile("(?:urlPatterns|value|path)\\s*=\\s*\\{([^}]*)\\}").matcher(anno);
        if (m.find()) {
            Matcher s = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
            while (s.find()) out.add(s.group(1));
            return out;
        }
        m = Pattern.compile("(?:urlPatterns|value|path)\\s*=\\s*\"([^\"]*)\"").matcher(anno);
        if (m.find()) { out.add(m.group(1)); return out; }
        // @WebServlet("/x") düz biçim
        m = Pattern.compile("\\(\\s*\"([^\"]*)\"").matcher(anno);
        if (m.find()) out.add(m.group(1));
        return out;
    }

    private boolean extendsAny(ClassModel c, List<String> bases) {
        return c.extendsTypes.stream().anyMatch(t -> bases.contains(stripGenerics(simpleName(t))));
    }

    private boolean implementsAny(ClassModel c, List<String> ifaces) {
        return c.implementsTypes.stream().anyMatch(t -> ifaces.contains(stripGenerics(simpleName(t))));
    }

    private boolean implementsAnyListener(ClassModel c) {
        return c.implementsTypes.stream().anyMatch(t -> LISTENER_IFACES.contains(stripGenerics(simpleName(t))));
    }

    private String listenerIfaces(ClassModel c) {
        List<String> found = c.implementsTypes.stream()
                .map(t -> stripGenerics(simpleName(t)))
                .filter(LISTENER_IFACES::contains)
                .toList();
        if (!found.isEmpty()) return String.join(", ", found);
        return hasAnno(c, "WebListener") ? "@WebListener" : null;
    }

    // ---- anotasyon metni yardımcıları ----

    private boolean hasAnno(ClassModel c, String name) {
        return find(c.annotations, name) != null;
    }

    private String find(List<String> annotations, String name) {
        for (String a : annotations) {
            if (a.equals("@" + name) || a.startsWith("@" + name + "(") || a.startsWith("@" + name + " ")) return a;
        }
        return null;
    }

    /** @GetMapping("/x") ya da @RequestMapping(value="/x") içindeki ilk path */
    private String firstPath(String anno) {
        if (anno == null) return "";
        Matcher m = Pattern.compile("(?:value|path)\\s*=\\s*\\{?\\s*\"([^\"]*)\"").matcher(anno);
        if (m.find()) return m.group(1);
        m = Pattern.compile("\\(\\s*\\{?\\s*\"([^\"]*)\"").matcher(anno);
        if (m.find()) return m.group(1);
        return "";
    }

    /** anotasyon içinden ad=değer çek: cron="..." veya fixedRate=5000 */
    private String attr(String anno, String name) {
        if (anno == null) return null;
        Matcher m = Pattern.compile(name + "\\s*=\\s*\"([^\"]*)\"").matcher(anno);
        if (m.find()) return m.group(1);
        m = Pattern.compile(name + "\\s*=\\s*([\\w.]+)").matcher(anno);
        if (m.find()) return m.group(1);
        return null;
    }

    /** @Produces(MediaType.APPLICATION_JSON) / @Produces("application/json") -> ham değer. */
    private String mediaType(String methodAnno, String classAnno) {
        String anno = methodAnno != null ? methodAnno : classAnno;
        if (anno == null) return null;
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(anno);
        if (m.find()) return m.group(1);
        m = Pattern.compile("\\(\\s*\\{?\\s*([\\w.]+)").matcher(anno);
        if (m.find()) return m.group(1);
        return null;
    }

    private String httpMethodOf(String mapping, String anno) {
        switch (mapping) {
            case "GetMapping": return "GET";
            case "PostMapping": return "POST";
            case "PutMapping": return "PUT";
            case "DeleteMapping": return "DELETE";
            case "PatchMapping": return "PATCH";
            default:
                Matcher m = Pattern.compile("RequestMethod\\.(\\w+)").matcher(anno);
                return m.find() ? m.group(1) : "GET|POST";
        }
    }

    private String joinPath(String base, String path) {
        String full = (norm(base) + norm(path));
        return full.isEmpty() ? "/" : full;
    }

    private String norm(String p) {
        if (p == null || p.isEmpty() || p.equals("/")) return "";
        return p.startsWith("/") ? p : "/" + p;
    }

    private String simpleName(String t) {
        if (t == null) return "";
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private String stripGenerics(String t) {
        int lt = t.indexOf('<');
        return lt >= 0 ? t.substring(0, lt) : t;
    }
}
