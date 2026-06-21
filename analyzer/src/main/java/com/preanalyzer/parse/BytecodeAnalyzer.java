package com.preanalyzer.parse;

import com.preanalyzer.build.ArtifactReader.Layout;
import com.preanalyzer.model.ClassModel;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Derlenmiş bir artefaktın (.war/.jar) kendi sınıflarını bytecode'dan analiz eder.
 * Yapı ve anotasyonlar (yol/cron değerleri dahil) reflection ile; metod gövdesindeki
 * çağrı akışı ise {@code javap -c} çıktısından çıkarılır. Üretilen {@link ClassModel}
 * kaynak tabanlı analizle aynı biçimdedir; bu yüzden mevcut tüm dedektörler değişmeden çalışır.
 */
public class BytecodeAnalyzer {

    private final Layout layout;

    public BytecodeAnalyzer(Layout layout) {
        this.layout = layout;
    }

    public List<ClassModel> analyze() {
        List<String> fqns = projeSiniflari(layout.classesRoot);
        URL[] urls = layout.classpath.stream().map(this::toUrl).filter(u -> u != null).toArray(URL[]::new);
        List<ClassModel> classes = new ArrayList<>();
        Map<String, Map<String, List<ClassModel.FlowStep>>> akislar =
                new BytecodeFlow(layout).extract(fqns);

        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            for (String fqn : fqns) {
                ClassModel cm = buildClass(fqn, loader, akislar.getOrDefault(fqn, Map.of()));
                if (cm != null) classes.add(cm);
            }
        } catch (Exception e) {
            System.err.println("[uyari] bytecode analizi siniflerini yuklerken sorun: " + e.getMessage());
        }
        return classes;
    }

    /** classesRoot altındaki tüm .class -> FQN (anonim/sentetik sınıflar atlanır). */
    private List<String> projeSiniflari(Path root) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String fqn = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                if (fqn.equals("module-info") || fqn.endsWith("package-info")) return;
                if (fqn.matches(".*\\$\\d.*")) return; // anonim/lokal sınıf
                out.add(fqn);
            });
        } catch (Exception ignore) { }
        return out;
    }

    private ClassModel buildClass(String fqn, ClassLoader loader,
                                  Map<String, List<ClassModel.FlowStep>> akislar) {
        Class<?> cls;
        try {
            cls = Class.forName(fqn, false, loader); // başlatma yapma (static init çalışmaz)
        } catch (Throwable t) {
            // sınıf yüklenemedi (eksik bağımlılık vb.) -> en azından iskelet kayıt
            return iskelet(fqn);
        }
        ClassModel cm = new ClassModel();
        cm.fqn = fqn;
        int dot = fqn.lastIndexOf('.');
        cm.name = dot >= 0 ? fqn.substring(dot + 1) : fqn;
        cm.packageName = dot >= 0 ? fqn.substring(0, dot) : "";
        cm.module = "artifact";
        cm.filePath = layout.classesRoot.resolve(fqn.replace('.', '/') + ".class").toString();
        cm.kind = kindOf(cls);

        try {
            for (Annotation a : cls.getDeclaredAnnotations()) cm.annotations.add(annoToString(a));
        } catch (Throwable ignore) { }

        try {
            if (cls.isInterface()) {
                for (Class<?> i : cls.getInterfaces()) cm.extendsTypes.add(i.getSimpleName());
            } else {
                Class<?> sup = cls.getSuperclass();
                if (sup != null && sup != Object.class) cm.extendsTypes.add(sup.getSimpleName());
                for (Class<?> i : cls.getInterfaces()) cm.implementsTypes.add(i.getSimpleName());
            }
        } catch (Throwable ignore) { }

        try {
            for (Field f : cls.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                ClassModel.FieldModel fm = new ClassModel.FieldModel();
                fm.name = f.getName();
                fm.type = f.getType().getSimpleName();
                fm.injected = hasAnno(f.getDeclaredAnnotations(), "Autowired", "Inject", "Resource")
                        || (Modifier.isFinal(f.getModifiers()) && !Modifier.isStatic(f.getModifiers()));
                cm.fields.add(fm);
            }
        } catch (Throwable ignore) { }

        try {
            for (Method md : cls.getDeclaredMethods()) {
                if (md.isSynthetic() || md.isBridge()) continue;
                ClassModel.MethodModel mm = new ClassModel.MethodModel();
                mm.name = md.getName();
                mm.returnType = md.getReturnType().getSimpleName();
                Class<?>[] ps = md.getParameterTypes();
                List<String> simpleParams = new ArrayList<>();
                for (int i = 0; i < ps.length; i++) {
                    String tn = ps[i].getSimpleName();
                    simpleParams.add(tn);
                    mm.params.add(tn + " arg" + i);
                }
                mm.signature = mm.name + "(" + String.join(", ", simpleParams) + ")";
                for (Annotation a : md.getDeclaredAnnotations()) mm.annotations.add(annoToString(a));
                List<ClassModel.FlowStep> flow = akislar.get(mm.name + "/" + ps.length);
                if (flow != null) {
                    mm.flow = flow;
                    if (!flow.isEmpty()) mm.line = flow.get(0).line;
                }
                cm.methods.add(mm);
            }
        } catch (Throwable ignore) { }

        return cm;
    }

    private ClassModel iskelet(String fqn) {
        ClassModel cm = new ClassModel();
        cm.fqn = fqn;
        int dot = fqn.lastIndexOf('.');
        cm.name = dot >= 0 ? fqn.substring(dot + 1) : fqn;
        cm.packageName = dot >= 0 ? fqn.substring(0, dot) : "";
        cm.module = "artifact";
        cm.kind = "class";
        return cm;
    }

    private String kindOf(Class<?> cls) {
        if (cls.isAnnotation()) return "annotation";
        if (cls.isInterface()) return "interface";
        if (cls.isEnum()) return "enum";
        if (cls.isRecord()) return "record";
        return "class";
    }

    private boolean hasAnno(Annotation[] annos, String... names) {
        for (Annotation a : annos) {
            String simple = a.annotationType().getSimpleName();
            for (String n : names) if (simple.equals(n)) return true;
        }
        return false;
    }

    /** Anotasyonu kaynak biçimine yakın, BASİT adlı string'e çevirir: @GetMapping(value={"/x"}) */
    private String annoToString(Annotation a) {
        String simple = a.annotationType().getSimpleName();
        StringBuilder sb = new StringBuilder("@").append(simple);
        Method[] elems = a.annotationType().getDeclaredMethods();
        List<String> parts = new ArrayList<>();
        for (Method e : elems) {
            try {
                Object v = e.invoke(a);
                String val = valueToString(v);
                if (val == null || val.equals("\"\"") || val.equals("{}")) continue; // boş/varsayılan gürültüsü
                parts.add(e.getName() + "=" + val);
            } catch (Throwable ignore) { }
        }
        if (!parts.isEmpty()) sb.append("(").append(String.join(", ", parts)).append(")");
        String s = sb.toString();
        return s.length() > 200 ? s.substring(0, 197) + "..." : s;
    }

    private String valueToString(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return "\"" + s + "\"";
        if (v instanceof Class<?> c) return c.getSimpleName() + ".class";
        if (v instanceof Enum<?> en) return en.name();
        if (v.getClass().isArray()) {
            int len = Array.getLength(v);
            if (len == 0) return "{}";
            List<String> items = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                String it = valueToString(Array.get(v, i));
                if (it != null) items.add(it);
            }
            return "{" + String.join(", ", items) + "}";
        }
        if (v instanceof Annotation an) return annoToString(an);
        return String.valueOf(v);
    }

    private URL toUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (Exception e) {
            return null;
        }
    }
}
