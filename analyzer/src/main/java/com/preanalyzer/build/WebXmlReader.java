package com.preanalyzer.build;

import com.preanalyzer.model.ProjectModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Klasik servlet konfigürasyonunu (WEB-INF/web.xml) ayrıştırır.
 * servlet/filter tanımlarını url-pattern eşlemeleriyle, listener sınıflarını çıkarır.
 * Spring Boot dışı / web.xml tabanlı eski projelerin giriş haritasıdır.
 */
public class WebXmlReader {

    /** web.xml'den çıkan tanımlar: sınıf FQN -> url-pattern listesi. */
    public static class WebDescriptor {
        public boolean present;
        public Map<String, List<String>> servlets = new LinkedHashMap<>(); // servlet-class -> url-pattern'ler
        public Map<String, List<String>> filters = new LinkedHashMap<>();  // filter-class  -> url-pattern'ler
        public Set<String> listeners = new LinkedHashSet<>();              // listener-class'lar

        public boolean isEmpty() {
            return servlets.isEmpty() && filters.isEmpty() && listeners.isEmpty();
        }
    }

    public WebDescriptor read(List<ProjectModel.ModuleInfo> modules) {
        WebDescriptor d = new WebDescriptor();
        Set<Path> seen = new LinkedHashSet<>(); // aynı web.xml'i iki kez ayrıştırma (webapp hem path hem resourceRoot olabilir)
        for (ProjectModel.ModuleInfo m : modules) {
            for (Path candidate : candidates(m)) {
                Path norm;
                try {
                    norm = candidate.toAbsolutePath().normalize();
                } catch (Exception e) {
                    norm = candidate;
                }
                if (!Files.isRegularFile(norm) || !seen.add(norm)) continue;
                d.present = true;
                try {
                    parse(norm, d);
                } catch (Exception e) {
                    System.err.println("[uyari] web.xml okunamadi " + norm + ": " + e.getMessage());
                }
            }
        }
        return d;
    }

    private List<Path> candidates(ProjectModel.ModuleInfo m) {
        List<Path> out = new ArrayList<>();
        Path base = Path.of(m.path);
        out.add(base.resolve("src/main/webapp/WEB-INF/web.xml")); // kaynak proje
        out.add(base.resolve("WEB-INF/web.xml"));                 // açılmış WAR artefaktı
        for (String res : m.resourceRoots) {
            out.add(Path.of(res).resolve("WEB-INF/web.xml"));
        }
        return out;
    }

    private void parse(Path webXml, WebDescriptor d) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setNamespaceAware(false);
        Document doc = dbf.newDocumentBuilder().parse(webXml.toFile());
        Element root = doc.getDocumentElement();

        // servlet-name -> servlet-class
        Map<String, String> servletClassByName = new LinkedHashMap<>();
        for (Element s : children(root, "servlet")) {
            String name = childText(s, "servlet-name");
            String cls = childText(s, "servlet-class");
            if (name != null && cls != null) servletClassByName.put(name, cls.trim());
        }
        for (Element sm : children(root, "servlet-mapping")) {
            String name = childText(sm, "servlet-name");
            String cls = servletClassByName.get(name);
            if (cls == null) continue;
            d.servlets.computeIfAbsent(cls, k -> new ArrayList<>()).addAll(urlPatterns(sm));
        }
        // eşlemesi olmayan servlet'leri de kaydet (programatik mapping olabilir)
        for (Map.Entry<String, String> e : servletClassByName.entrySet()) {
            d.servlets.computeIfAbsent(e.getValue(), k -> new ArrayList<>());
        }

        // filter-name -> filter-class
        Map<String, String> filterClassByName = new LinkedHashMap<>();
        for (Element f : children(root, "filter")) {
            String name = childText(f, "filter-name");
            String cls = childText(f, "filter-class");
            if (name != null && cls != null) filterClassByName.put(name, cls.trim());
        }
        for (Element fm : children(root, "filter-mapping")) {
            String name = childText(fm, "filter-name");
            String cls = filterClassByName.get(name);
            if (cls == null) continue;
            d.filters.computeIfAbsent(cls, k -> new ArrayList<>()).addAll(urlPatterns(fm));
        }
        for (String cls : filterClassByName.values()) {
            d.filters.computeIfAbsent(cls, k -> new ArrayList<>());
        }

        // listener'lar
        for (Element l : children(root, "listener")) {
            String cls = childText(l, "listener-class");
            if (cls != null) d.listeners.add(cls.trim());
        }
    }

    private List<String> urlPatterns(Element mapping) {
        List<String> out = new ArrayList<>();
        for (Element u : children(mapping, "url-pattern")) {
            out.add(u.getTextContent().trim());
        }
        return out;
    }

    // ---- DOM yardımcıları (doğrudan alt elemanlar) ----

    private List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList ns = parent.getChildNodes();
        for (int i = 0; i < ns.getLength(); i++) {
            Node n = ns.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName(n).equals(tag)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private String childText(Element parent, String tag) {
        for (Element e : children(parent, tag)) return e.getTextContent().trim();
        return null;
    }

    /** Namespace ön ekini (varsa) at: j2ee:servlet -> servlet */
    private String localName(Node n) {
        String name = n.getNodeName();
        int c = name.indexOf(':');
        return c >= 0 ? name.substring(c + 1) : name;
    }
}
