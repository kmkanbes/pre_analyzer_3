package com.preanalyzer.jar;

import com.preanalyzer.model.EntryPoint;
import com.preanalyzer.model.EntryPoint.TraceNode;
import com.preanalyzer.model.EntryPoint.TraceStep;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Giriş noktası akışlarındaki kaynakta-olmayan (harici) çağrıların hedef
 * sınıflarını toplar ve {@link JavapResolver} ile jar/war içeriğinden çözer.
 * Sonuç: FQN -> metod listesi. JsonWriter bu haritayı kullanarak her harici
 * çağrı adımına gerçek imzayı (disBagimlilik) ekler (Gereksinim 14).
 */
public class ExternalCallEnricher {

    private static final String[] NOISE = {
            "java.", "javax.", "jakarta.", "org.slf4j", "org.apache.logging", "lombok."
    };

    public static Map<String, List<JavapResolver.MetodBilgi>> enrich(
            List<EntryPoint> entryPoints, JavapResolver resolver) {
        Set<String> fqns = new LinkedHashSet<>();
        for (EntryPoint ep : entryPoints) {
            collect(ep.trace, fqns);
        }
        return resolver.resolveAll(fqns);
    }

    private static void collect(TraceNode node, Set<String> out) {
        if (node == null) return;
        for (TraceStep s : node.steps) {
            if ("call".equals(s.type) && !s.internal && s.targetClass != null
                    && s.targetClass.contains(".") && !"new".equals(s.method) && !isNoise(s.targetClass)) {
                out.add(s.targetClass);
            }
            if (s.callee != null) collect(s.callee, out);
        }
    }

    private static boolean isNoise(String fqn) {
        for (String p : NOISE) if (fqn.startsWith(p)) return true;
        return false;
    }
}
