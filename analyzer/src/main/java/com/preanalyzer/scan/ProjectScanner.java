package com.preanalyzer.scan;

import com.preanalyzer.model.ProjectModel;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hedef dizini tarar; build dosyalarına göre modülleri ve kaynak köklerini bulur.
 * target/build/bin/.git gibi dizinler atlanır.
 */
public class ProjectScanner {

    private static final Set<String> SKIP_DIRS = Set.of(
            "target", "build", "bin", "out", "node_modules",
            ".git", ".idea", ".gradle", ".mvn", ".settings", ".metadata", ".vscode");

    public List<ProjectModel.ModuleInfo> scanModules(Path root) throws IOException {
        List<ProjectModel.ModuleInfo> modules = new ArrayList<>();
        findBuildFiles(root, root, modules, 0);
        // build dosyası hiç yoksa ama src/main/java varsa tek modül say
        if (modules.isEmpty()) {
            Path src = root.resolve("src/main/java");
            if (Files.isDirectory(src)) {
                modules.add(makeModule(root, root));
            }
        }
        return modules;
    }

    private void findBuildFiles(Path root, Path dir, List<ProjectModel.ModuleInfo> modules, int depth) throws IOException {
        if (depth > 4) return;
        boolean hasBuild = Files.exists(dir.resolve("pom.xml"))
                || Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("build.gradle.kts"));
        if (hasBuild && Files.isDirectory(dir.resolve("src/main/java"))) {
            modules.add(makeModule(root, dir));
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path sub : ds) {
                String name = sub.getFileName().toString();
                if (SKIP_DIRS.contains(name) || name.startsWith(".")) continue;
                if (name.equals("src")) continue;
                findBuildFiles(root, sub, modules, depth + 1);
            }
        }
    }

    private ProjectModel.ModuleInfo makeModule(Path root, Path dir) {
        ProjectModel.ModuleInfo m = new ProjectModel.ModuleInfo();
        m.name = dir.equals(root) ? root.getFileName().toString() : root.relativize(dir).toString().replace('\\', '/');
        m.path = dir.toAbsolutePath().toString();
        Path srcMain = dir.resolve("src/main/java");
        if (Files.isDirectory(srcMain)) m.sourceRoots.add(srcMain.toAbsolutePath().toString());
        Path res = dir.resolve("src/main/resources");
        if (Files.isDirectory(res)) m.resourceRoots.add(res.toAbsolutePath().toString());
        Path webapp = dir.resolve("src/main/webapp");
        if (Files.isDirectory(webapp)) m.resourceRoots.add(webapp.toAbsolutePath().toString());
        return m;
    }
}
