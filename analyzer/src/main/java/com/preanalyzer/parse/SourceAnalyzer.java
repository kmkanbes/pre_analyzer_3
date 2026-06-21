package com.preanalyzer.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.preanalyzer.model.ClassModel;
import com.preanalyzer.model.ProjectModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Modüllerin kaynak köklerindeki tüm .java dosyalarını JavaParser ile ayrıştırır,
 * her tip için ClassModel üretir. Sembol çözümleme için tüm kaynak kökleri
 * tek bir CombinedTypeSolver'a eklenir (modüller arası çağrılar da çözülür).
 */
public class SourceAnalyzer {

    public List<ClassModel> analyze(List<ProjectModel.ModuleInfo> modules) throws IOException {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        for (ProjectModel.ModuleInfo m : modules) {
            for (String root : m.sourceRoots) {
                typeSolver.add(new JavaParserTypeSolver(Path.of(root)));
            }
        }
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(config);

        List<ClassModel> classes = new ArrayList<>();
        for (ProjectModel.ModuleInfo m : modules) {
            for (String root : m.sourceRoots) {
                try (Stream<Path> files = Files.walk(Path.of(root))) {
                    List<Path> javaFiles = files
                            .filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                            .sorted()
                            .toList();
                    for (Path file : javaFiles) {
                        parseFile(parser, file, m.name, classes);
                    }
                }
            }
        }
        return classes;
    }

    private void parseFile(JavaParser parser, Path file, String moduleName, List<ClassModel> out) {
        try {
            Optional<CompilationUnit> cuOpt = parser.parse(file).getResult();
            if (cuOpt.isEmpty()) {
                System.err.println("[uyari] ayristirilamadi: " + file);
                return;
            }
            CompilationUnit cu = cuOpt.get();
            String pkg = cu.getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");
            Map<String, String> importMap = buildImportMap(cu);
            FlowExtractor flowExtractor = new FlowExtractor(importMap, pkg);

            for (TypeDeclaration<?> type : cu.getTypes()) {
                out.add(buildClassModel(type, pkg, moduleName, file, flowExtractor, importMap));
            }
        } catch (Exception e) {
            System.err.println("[uyari] dosya islenemedi " + file + ": " + e.getMessage());
        }
    }

    /** import edilen basit ad -> FQN haritası */
    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.getImports().forEach(imp -> {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String fqn = imp.getNameAsString();
                int dot = fqn.lastIndexOf('.');
                map.put(dot >= 0 ? fqn.substring(dot + 1) : fqn, fqn);
            }
        });
        return map;
    }

    private ClassModel buildClassModel(TypeDeclaration<?> type, String pkg, String moduleName,
                                       Path file, FlowExtractor flowExtractor, Map<String, String> importMap) {
        ClassModel cm = new ClassModel();
        cm.name = type.getNameAsString();
        cm.packageName = pkg;
        cm.fqn = pkg.isEmpty() ? cm.name : pkg + "." + cm.name;
        cm.module = moduleName;
        cm.filePath = file.toAbsolutePath().toString();
        cm.kind = kindOf(type);
        type.getAnnotations().forEach(a -> cm.annotations.add(annotationToString(a)));

        if (type instanceof ClassOrInterfaceDeclaration cid) {
            cid.getExtendedTypes().forEach(t -> cm.extendsTypes.add(t.toString()));
            cid.getImplementedTypes().forEach(t -> cm.implementsTypes.add(t.toString()));
        } else if (type instanceof RecordDeclaration rd) {
            rd.getImplementedTypes().forEach(t -> cm.implementsTypes.add(t.toString()));
        }

        // alanlar
        for (FieldDeclaration fd : type.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                ClassModel.FieldModel fm = new ClassModel.FieldModel();
                fm.name = v.getNameAsString();
                fm.type = v.getTypeAsString();
                fm.injected = fd.isAnnotationPresent("Autowired")
                        || fd.isAnnotationPresent("Inject")
                        || (fd.isFinal() && !fd.isStatic());
                cm.fields.add(fm);
            }
        }
        // record bileşenleri alan gibi
        if (type instanceof RecordDeclaration rd) {
            rd.getParameters().forEach(p -> {
                ClassModel.FieldModel fm = new ClassModel.FieldModel();
                fm.name = p.getNameAsString();
                fm.type = p.getTypeAsString();
                cm.fields.add(fm);
            });
        }

        // metodlar
        Map<String, String> fieldTypes = new HashMap<>();
        cm.fields.forEach(f -> fieldTypes.put(f.name, baseType(f.type)));

        for (MethodDeclaration md : type.getMethods()) {
            ClassModel.MethodModel mm = new ClassModel.MethodModel();
            mm.name = md.getNameAsString();
            mm.returnType = md.getTypeAsString();
            md.getParameters().forEach(p -> mm.params.add(p.getTypeAsString() + " " + p.getNameAsString()));
            mm.signature = mm.name + "(" + String.join(", ",
                    md.getParameters().stream().map(p -> p.getTypeAsString()).toList()) + ")";
            md.getAnnotations().forEach(a -> mm.annotations.add(annotationToString(a)));
            mm.line = md.getBegin().map(p -> p.line).orElse(0);
            if (md.getBody().isPresent()) {
                mm.flow = flowExtractor.extract(md.getBody().get(), cm.fqn, fieldTypes);
            }
            cm.methods.add(mm);
        }
        return cm;
    }

    private String kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            return cid.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) return "enum";
        if (type instanceof RecordDeclaration) return "record";
        if (type instanceof AnnotationDeclaration) return "annotation";
        return "class";
    }

    private String annotationToString(AnnotationExpr a) {
        String s = a.toString();
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    /** Generic parametreleri soyulmuş tip adı: List&lt;Foo&gt; -> List */
    static String baseType(String type) {
        int lt = type.indexOf('<');
        return lt >= 0 ? type.substring(0, lt) : type;
    }
}
