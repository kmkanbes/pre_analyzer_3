package com.preanalyzer.parse;

import com.preanalyzer.build.ArtifactReader.Layout;
import com.preanalyzer.model.ClassModel;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code javap -c -l} çıktısından her metodun çağrı akışını (invoke komutları)
 * sıralı {@link ClassModel.FlowStep} listesine çevirir. Kaynak kodu olmayan
 * (.war/.jar) sınıflar için kaba bir "çağrı akışı" elde edilir; if/loop/try gibi
 * yapılar bytecode'dan güvenle çıkarılamadığından yalnızca çağrılar listelenir.
 */
public class BytecodeFlow {

    private static final int CHUNK = 60;
    private static final int MAX_STEP = 400;

    private final Layout layout;
    private boolean javapVar = true;

    public BytecodeFlow(Layout layout) {
        this.layout = layout;
    }

    /** FQN -> (metodAdi/argSayisi -> akış adımları) */
    public Map<String, Map<String, List<ClassModel.FlowStep>>> extract(List<String> fqns) {
        Map<String, Map<String, List<ClassModel.FlowStep>>> result = new LinkedHashMap<>();
        String cp = classpath();
        for (int i = 0; i < fqns.size() && javapVar; i += CHUNK) {
            List<String> chunk = fqns.subList(i, Math.min(i + CHUNK, fqns.size()));
            String out = runJavap(cp, chunk);
            if (out != null) parse(out, result);
        }
        return result;
    }

    private String classpath() {
        List<String> parts = new ArrayList<>();
        for (Path p : layout.classpath) parts.add(p.toString());
        return String.join(File.pathSeparator, parts);
    }

    private String runJavap(String cp, List<String> fqns) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javapBin());
        cmd.add("-c");
        cmd.add("-l");
        cmd.add("-p");
        cmd.add("-classpath");
        cmd.add(cp);
        cmd.addAll(fqns);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            javapVar = false;
            return null;
        }
    }

    private static final Pattern CLASS_HDR = Pattern.compile(
            "(?:class|interface|enum)\\s+([\\w.$]+)");
    private static final Pattern METHOD_HDR = Pattern.compile(
            "^\\s*(?:public|protected|private|static|final|abstract|synchronized|native|default|\\s)*"
            + "[\\w.$\\[\\]<>?,\\s]*?([\\w$]+)\\(([^)]*)\\)\\s*(?:throws[^;]*)?;\\s*$");
    private static final Pattern INVOKE = Pattern.compile(
            "^\\s*(\\d+):\\s*invoke\\w+.*?//\\s*(?:Interface)?Method\\s+(.+)$");
    private static final Pattern LINE_ENTRY = Pattern.compile("^\\s*line\\s+(\\d+):\\s*(\\d+)\\s*$");

    private void parse(String javapOut, Map<String, Map<String, List<ClassModel.FlowStep>>> result) {
        String currentClass = null;
        Map<String, List<ClassModel.FlowStep>> classMap = null;

        // metod-içi geçici toplayıcılar
        String methodKey = null;
        List<Object[]> invokes = new ArrayList<>();      // {offset(int), commentText(String)}
        TreeMap<Integer, Integer> lineTable = new TreeMap<>();

        for (String raw : javapOut.split("\\R")) {
            // sınıf başlığı
            Matcher hdr = CLASS_HDR.matcher(raw);
            if (raw.contains("{") && hdr.find() && !raw.trim().startsWith("//")) {
                flushMethod(classMap, methodKey, invokes, lineTable);
                methodKey = null; invokes = new ArrayList<>(); lineTable = new TreeMap<>();
                currentClass = hdr.group(1);
                classMap = result.computeIfAbsent(currentClass, k -> new LinkedHashMap<>());
                continue;
            }
            if (currentClass == null) continue;

            // metod başlığı
            Matcher mh = METHOD_HDR.matcher(raw);
            if (mh.matches() && !raw.contains("//")) {
                flushMethod(classMap, methodKey, invokes, lineTable);
                invokes = new ArrayList<>(); lineTable = new TreeMap<>();
                String name = mh.group(1);
                int argc = countParams(mh.group(2));
                methodKey = name + "/" + argc;
                continue;
            }
            if (methodKey == null) continue;

            Matcher inv = INVOKE.matcher(raw);
            if (inv.find()) {
                invokes.add(new Object[]{ Integer.parseInt(inv.group(1)), inv.group(2).trim() });
                continue;
            }
            Matcher le = LINE_ENTRY.matcher(raw);
            if (le.find()) {
                lineTable.put(Integer.parseInt(le.group(2)), Integer.parseInt(le.group(1)));
            }
        }
        flushMethod(classMap, methodKey, invokes, lineTable);
    }

    private void flushMethod(Map<String, List<ClassModel.FlowStep>> classMap, String methodKey,
                             List<Object[]> invokes, TreeMap<Integer, Integer> lineTable) {
        if (classMap == null || methodKey == null || invokes.isEmpty()) return;
        List<ClassModel.FlowStep> steps = new ArrayList<>();
        int n = 0;
        for (Object[] inv : invokes) {
            if (n >= MAX_STEP) break;
            int offset = (int) inv[0];
            ClassModel.FlowStep s = toStep((String) inv[1]);
            if (s == null) continue;
            s.step = ++n;
            Map.Entry<Integer, Integer> e = lineTable.floorEntry(offset);
            s.line = e != null ? e.getValue() : 0;
            steps.add(s);
        }
        if (!steps.isEmpty()) classMap.putIfAbsent(methodKey, steps);
    }

    /** "com/kmk/X.foo:(I)Ljava/lang/String;" -> FlowStep(call) */
    private ClassModel.FlowStep toStep(String comment) {
        int colon = comment.lastIndexOf(':');
        if (colon < 0) return null;
        String ownerMethod = comment.substring(0, colon);
        String descriptor = comment.substring(colon + 1);
        int dot = ownerMethod.lastIndexOf('.');
        String owner, method;
        if (dot >= 0) {
            owner = ownerMethod.substring(0, dot).replace('/', '.');
            method = ownerMethod.substring(dot + 1);
        } else {
            owner = null;            // aynı sınıf — Linker upgrade'e bırakılır (basit ad yok)
            method = ownerMethod;
        }
        if (method.equals("<clinit>")) return null;

        ClassModel.FlowStep s = new ClassModel.FlowStep();
        s.type = "call";
        s.targetClass = owner;
        s.method = method.equals("<init>") ? "new" : method;
        s.object = owner != null ? simple(owner) : "this";
        s.args = descArgs(descriptor);
        return s;
    }

    /** JVM tip imzasından argüman basit tiplerini çıkar: (ILjava/lang/String;)V -> [int, String] */
    private List<String> descArgs(String descriptor) {
        List<String> args = new ArrayList<>();
        int i = descriptor.indexOf('(');
        int end = descriptor.indexOf(')');
        if (i < 0 || end < 0) return args;
        i++;
        int arr = 0;
        while (i < end) {
            char c = descriptor.charAt(i);
            switch (c) {
                case '[': arr++; i++; continue;
                case 'B': args.add(arrName("byte", arr)); arr = 0; i++; break;
                case 'C': args.add(arrName("char", arr)); arr = 0; i++; break;
                case 'D': args.add(arrName("double", arr)); arr = 0; i++; break;
                case 'F': args.add(arrName("float", arr)); arr = 0; i++; break;
                case 'I': args.add(arrName("int", arr)); arr = 0; i++; break;
                case 'J': args.add(arrName("long", arr)); arr = 0; i++; break;
                case 'S': args.add(arrName("short", arr)); arr = 0; i++; break;
                case 'Z': args.add(arrName("boolean", arr)); arr = 0; i++; break;
                case 'L': {
                    int semi = descriptor.indexOf(';', i);
                    if (semi < 0) return args;
                    String cls = descriptor.substring(i + 1, semi);
                    args.add(arrName(simple(cls.replace('/', '.')), arr));
                    arr = 0; i = semi + 1;
                    break;
                }
                default: i++;
            }
        }
        return args;
    }

    private String arrName(String base, int arr) {
        StringBuilder sb = new StringBuilder(base);
        for (int k = 0; k < arr; k++) sb.append("[]");
        return sb.toString();
    }

    private int countParams(String inner) {
        inner = inner.trim();
        if (inner.isEmpty()) return 0;
        int count = 1, depth = 0;
        for (char ch : inner.toCharArray()) {
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) count++;
        }
        return count;
    }

    private String simple(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
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
}
