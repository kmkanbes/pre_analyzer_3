package com.preanalyzer.build;

import com.preanalyzer.model.ProjectModel;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * src/main/resources altındaki application.properties / application.yml dosyalarını okur;
 * port, uygulama adı, datasource gibi temel ayarları FrameworkInfo'ya işler,
 * tüm ayarları düz map olarak appConfig'e koyar.
 */
public class AppConfigReader {

    public void read(Path resourceRoot, Map<String, String> target, ProjectModel.FrameworkInfo fw) {
        for (String name : new String[]{"application.properties", "application.yml", "application.yaml"}) {
            Path file = resourceRoot.resolve(name);
            if (!Files.exists(file)) continue;
            try {
                if (name.endsWith(".properties")) readProperties(file, target);
                else readYaml(file, target);
            } catch (Exception e) {
                System.err.println("[uyari] config okunamadi " + file + ": " + e.getMessage());
            }
        }
        fw.serverPort = firstNonNull(target.get("server.port"), fw.serverPort);
        fw.applicationName = firstNonNull(target.get("spring.application.name"), fw.applicationName);
        fw.contextPath = firstNonNull(target.get("server.servlet.context-path"), fw.contextPath);
        fw.datasourceUrl = firstNonNull(target.get("spring.datasource.url"), fw.datasourceUrl);
    }

    private void readProperties(Path file, Map<String, String> target) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        for (String key : p.stringPropertyNames()) {
            target.put(key, mask(key, p.getProperty(key)));
        }
    }

    @SuppressWarnings("unchecked")
    private void readYaml(Path file, Map<String, String> target) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(file)) {
            for (Object docObj : yaml.loadAll(in)) {
                if (docObj instanceof Map<?, ?> map) {
                    flatten("", (Map<String, Object>) map, target);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> map, Map<String, String> target) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object val = e.getValue();
            if (val instanceof Map<?, ?> nested) {
                flatten(key, (Map<String, Object>) nested, target);
            } else if (val != null) {
                target.put(key, mask(key, String.valueOf(val)));
            }
        }
    }

    /** Parola benzeri değerleri çıktıya yazmadan maskele. */
    private String mask(String key, String value) {
        String k = key.toLowerCase();
        if (k.contains("password") || k.contains("secret") || k.contains("credential") || k.contains("token")) {
            return "***";
        }
        return value;
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    public static Map<String, String> newConfigMap() {
        return new LinkedHashMap<>();
    }
}
