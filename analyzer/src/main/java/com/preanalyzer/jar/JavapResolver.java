package com.preanalyzer.jar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kaynakta bulunmayan sınıfların metodlarını JDK'nın {@code javap} aracıyla
 * (jar/war içeriğinden) çözer. İstenen FQN'leri içeren jar'a göre gruplar,
 * her jar için tek bir javap çağrısı yapar ve çıktısını ayrıştırır (Gereksinim 14).
 */
public class JavapResolver {

    /** javap'tan çözülmüş tek bir metod. */
    public static class MetodBilgi {
        public String ad;          // metod adı
        public String imza;        // ad(BasitTip1, BasitTip2)
        public String donusTipi;   // basit dönüş tipi (constructor için null)
        public List<String> parametreler = new ArrayList<>();
        public String jar;         // bulunduğu jar dosyasının adı
        public boolean statik;
    }

    private final JarIndex index;
    private final Map<String, List<MetodBilgi>> cache = new LinkedHashMap<>();
    private boolean javapVar = true;

    public JavapResolver(JarIndex index) {
        this.index = index;
    }

    /** Verilen FQN kümesini çözer; sonuç FQN -> metod listesi olarak döner. */
    public Map<String, List<MetodBilgi>> resolveAll(Set<String> fqns) {
        // jar'a göre grupla
        Map<Path, List<String>> byJar = new LinkedHashMap<>();
        for (String fqn : fqns) {
            if (cache.containsKey(fqn)) continue;
            Path jar = index.jarOf(fqn);
            if (jar == null) continue;
            byJar.computeIfAbsent(jar, k -> new ArrayList<>()).add(fqn);
        }
        for (Map.Entry<Path, List<String>> e : byJar.entrySet()) {
            if (!javapVar) break;
            runJavap(e.getKey(), e.getValue());
        }
        Map<String, List<MetodBilgi>> result = new LinkedHashMap<>();
        for (String fqn : fqns) {
            List<MetodBilgi> ms = cache.get(fqn);
            if (ms != null && !ms.isEmpty()) result.put(fqn, ms);
        }
        return result;
    }

    /** Tek FQN için (önbellekten ya da yeni) çözüm. */
    public List<MetodBilgi> resolve(String fqn) {
        if (cache.containsKey(fqn)) return cache.get(fqn);
        Path jar = index.jarOf(fqn);
        if (jar != null) runJavap(jar, List.of(fqn));
        return cache.getOrDefault(fqn, List.of());
    }

    private void runJavap(Path jar, List<String> fqns) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javapBin());
        cmd.add("-protected");   // public + protected üyeler (tek erişim düzeyi bayrağı)
        cmd.add("-classpath");
        cmd.add(jar.toString());
        cmd.addAll(fqns);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            parse(out, jar, fqns);
        } catch (Exception e) {
            javapVar = false; // javap yoksa tekrar deneme
        }
        // çözülemeyenleri boş listeyle işaretle (tekrar denememek için)
        for (String fqn : fqns) cache.putIfAbsent(fqn, new ArrayList<>());
    }

    private static final Pattern CLASS_HDR = Pattern.compile(
            "(?:class|interface|enum|record)\\s+([\\w.$]+)");
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?<mods>(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|\\s)*)"
            + "(?<sig>[^;{]*?\\([^)]*\\))\\s*(?:throws[^;]*)?;");

    private void parse(String javapOut, Path jar, List<String> requested) {
        String jarName = jar.getFileName().toString();
        String currentClass = null;
        List<MetodBilgi> bucket = null;
        for (String line : javapOut.split("\\R")) {
            Matcher hdr = CLASS_HDR.matcher(line);
            if (line.contains("{") && hdr.find()) {
                currentClass = hdr.group(1);
                bucket = cache.computeIfAbsent(currentClass, k -> new ArrayList<>());
                continue;
            }
            if (currentClass == null || bucket == null) continue;
            if (line.trim().equals("}")) { currentClass = null; bucket = null; continue; }

            Matcher mm = METHOD.matcher(line);
            if (!mm.find()) continue;
            String mods = mm.group("mods");
            String sig = mm.group("sig").trim();
            MetodBilgi mb = toMethod(sig, mods, jarName);
            if (mb != null) bucket.add(mb);
        }
    }

    /** "java.lang.String getX(int, java.util.List)" -> MetodBilgi */
    private MetodBilgi toMethod(String sig, String mods, String jarName) {
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open < 0 || close < open) return null;
        String head = sig.substring(0, open).trim();   // [donus] ad  (constructor'da sadece FQN)
        String paramsRaw = sig.substring(open + 1, close).trim();

        MetodBilgi mb = new MetodBilgi();
        mb.jar = jarName;
        mb.statik = mods != null && mods.contains("static");

        String[] headParts = head.split("\\s+");
        String name;
        if (headParts.length >= 2) {
            mb.donusTipi = simple(headParts[headParts.length - 2]);
            name = headParts[headParts.length - 1];
        } else {
            // constructor: head = tam sınıf adı
            name = simple(head);
        }
        // generic/typeparam artıklarını temizle
        mb.ad = name.replaceAll("<.*>", "");

        if (!paramsRaw.isEmpty()) {
            for (String part : splitTopLevel(paramsRaw)) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                mb.parametreler.add(simple(t.replaceAll("<.*?>", "")));
            }
        }
        mb.imza = mb.ad + "(" + String.join(", ", mb.parametreler) + ")";
        if (mb.ad.isEmpty()) return null;
        return mb;
    }

    /** Üst seviye virgülle böl (generic içi virgülleri sayma). */
    private List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    private String simple(String type) {
        String t = type.replace("...", "[]");
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        boolean arr = t.endsWith("[]");
        if (arr) t = t.substring(0, t.length() - 2);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        return arr ? t + "[]" : t;
    }

    private String javapBin() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path j = Path.of(javaHome, "bin", isWindows() ? "javap.exe" : "javap");
            if (java.nio.file.Files.exists(j)) return j.toString();
        }
        return "javap";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Çözülmüş metodlar arasında ada (ve mümkünse parametre sayısına) göre eşleştir. */
    public static MetodBilgi match(List<MetodBilgi> methods, String name, int argc) {
        if (methods == null) return null;
        MetodBilgi adEslesen = null;
        for (MetodBilgi m : methods) {
            if (!m.ad.equals(name)) continue;
            if (m.parametreler.size() == argc) return m;
            if (adEslesen == null) adEslesen = m;
        }
        return adEslesen;
    }
}
