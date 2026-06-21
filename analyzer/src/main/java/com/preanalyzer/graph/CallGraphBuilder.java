package com.preanalyzer.graph;

import com.preanalyzer.model.CallGraph;
import com.preanalyzer.model.ClassModel;
import com.preanalyzer.model.EntryPoint;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proje geneli metod çağrı grafiğini kurar.
 * Proje içi çağrılar metod düzeyinde bağlanır; sınır (db/http/kafka) çağrıları ve
 * kaynakta olmayan ama projeye ait görünen harici sınıflar da düğüm olarak eklenir.
 * JDK / framework gürültüsü (String, System.out, log...) grafiğe alınmaz.
 */
public class CallGraphBuilder {

    private static final List<String> NOISE_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "org.slf4j", "org.apache.logging",
            "lombok.", "org.springframework.util", "org.springframework.http.ResponseEntity");

    public CallGraph build(List<ClassModel> classes, List<EntryPoint> entryPoints, MethodResolver resolver) {
        CallGraph graph = new CallGraph();
        Map<String, CallGraph.Node> nodes = new LinkedHashMap<>();
        Set<String> edgeKeys = new HashSet<>();

        // proje metodları
        for (ClassModel c : classes) {
            for (ClassModel.MethodModel m : c.methods) {
                CallGraph.Node n = new CallGraph.Node();
                n.id = c.fqn + "#" + m.signature;
                n.classFqn = c.fqn;
                n.className = c.name;
                n.method = m.name;
                n.stereotype = c.stereotype;
                nodes.put(n.id, n);
            }
        }
        // giriş noktası işaretleri
        Set<String> epIds = new HashSet<>();
        for (EntryPoint ep : entryPoints) epIds.add(ep.classFqn + "#" + ep.signature);
        nodes.values().forEach(n -> n.entryPoint = epIds.contains(n.id));

        // kenarlar
        for (ClassModel c : classes) {
            for (ClassModel.MethodModel m : c.methods) {
                String fromId = c.fqn + "#" + m.signature;
                for (ClassModel.FlowStep s : m.flow) {
                    if (!"call".equals(s.type) || s.targetClass == null || "new".equals(s.method)) continue;

                    String toId;
                    if (s.internal) {
                        int argc = s.args == null ? 0 : s.args.size();
                        MethodResolver.Target t = resolver.find(s.targetClass, s.method, argc);
                        if (t == null) continue;
                        toId = t.cls().fqn + "#" + t.method().signature;
                        if (!nodes.containsKey(toId)) continue;
                    } else {
                        if (s.boundary == null && isNoise(s.targetClass)) continue;
                        if (s.boundary == null && !s.targetClass.contains(".")) continue; // çözülememiş basit ad
                        if (s.boundary == null && isAccessor(s.method)) continue; // harici get/set gürültüsü
                        toId = s.targetClass + "#" + s.method;
                        CallGraph.Node ext = nodes.computeIfAbsent(toId, k -> {
                            CallGraph.Node n = new CallGraph.Node();
                            n.id = k;
                            n.classFqn = s.targetClass;
                            n.className = simpleName(s.targetClass);
                            n.method = s.method;
                            n.external = true;
                            n.stereotype = "external";
                            return n;
                        });
                        if (s.boundary != null) ext.boundary = s.boundary;
                    }
                    String key = fromId + "->" + toId;
                    if (edgeKeys.add(key)) {
                        graph.edges.add(new CallGraph.Edge(fromId, toId, s.line));
                    }
                }
            }
        }
        graph.nodes.addAll(nodes.values());
        return graph;
    }

    /** getX/setX/isX biçimli erişimciler: veri taşıma gürültüsü, grafiğe katılmaz. */
    private boolean isAccessor(String method) {
        return (method.startsWith("get") || method.startsWith("set") || method.startsWith("is"))
                && method.length() > 3 && Character.isUpperCase(method.charAt(method.startsWith("is") ? 2 : 3));
    }

    private boolean isNoise(String fqn) {
        for (String p : NOISE_PREFIXES) {
            if (fqn.startsWith(p)) return true;
        }
        return false;
    }

    private String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
