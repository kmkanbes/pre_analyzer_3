package com.preanalyzer.graph;

import com.preanalyzer.model.ClassModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Şu sınıfın şu metodu" sorusunu proje modeli üzerinde çözer.
 * Arayüz metodları için projedeki implementasyona atlar (Service arayüzü -> ServiceImpl).
 */
public class MethodResolver {

    public record Target(ClassModel cls, ClassModel.MethodModel method) {}

    private final Map<String, ClassModel> byFqn = new HashMap<>();
    private final Map<String, List<ClassModel>> implementorsOf = new HashMap<>();

    public MethodResolver(List<ClassModel> classes) {
        for (ClassModel c : classes) byFqn.put(c.fqn, c);
        for (ClassModel c : classes) {
            for (String iface : c.implementsTypes) {
                String simple = strip(iface);
                implementorsOf.computeIfAbsent(simple, k -> new java.util.ArrayList<>()).add(c);
            }
        }
    }

    public ClassModel classOf(String fqn) {
        return byFqn.get(fqn);
    }

    /** classFqn içindeki metodu bul; arayüzse implementasyona atla; bulunamazsa üst sınıfa bak. */
    public Target find(String classFqn, String methodName, int argCount) {
        ClassModel c = byFqn.get(classFqn);
        if (c == null) return null;

        ClassModel.MethodModel m = match(c, methodName, argCount);
        if (m != null && (!m.flow.isEmpty() || !"interface".equals(c.kind))) {
            return new Target(c, m);
        }
        // arayüz: projede tek implementasyon varsa oraya atla
        if ("interface".equals(c.kind)) {
            List<ClassModel> impls = implementorsOf.get(c.name);
            if (impls != null && impls.size() == 1) {
                ClassModel.MethodModel im = match(impls.get(0), methodName, argCount);
                if (im != null) return new Target(impls.get(0), im);
            }
            if (m != null) return new Target(c, m);
        }
        // üst sınıf zinciri (proje içi)
        for (String ext : c.extendsTypes) {
            ClassModel parent = bySimpleInPackage(strip(ext), c.packageName);
            if (parent != null) {
                Target t = find(parent.fqn, methodName, argCount);
                if (t != null) return t;
            }
        }
        return m != null ? new Target(c, m) : null;
    }

    private ClassModel.MethodModel match(ClassModel c, String name, int argCount) {
        ClassModel.MethodModel byName = null;
        for (ClassModel.MethodModel m : c.methods) {
            if (!m.name.equals(name)) continue;
            if (m.params.size() == argCount) return m;
            if (byName == null) byName = m;
        }
        return byName;
    }

    private ClassModel bySimpleInPackage(String simple, String pkg) {
        ClassModel c = byFqn.get(pkg + "." + simple);
        if (c != null) return c;
        for (ClassModel cm : byFqn.values()) {
            if (cm.name.equals(simple)) return cm;
        }
        return null;
    }

    private String strip(String type) {
        int lt = type.indexOf('<');
        return lt >= 0 ? type.substring(0, lt) : type;
    }
}
