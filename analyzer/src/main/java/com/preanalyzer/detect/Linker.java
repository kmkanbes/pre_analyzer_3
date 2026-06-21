package com.preanalyzer.detect;

import com.preanalyzer.model.ClassModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tüm sınıflar ayrıştırıldıktan sonra çalışan bağlama geçişi:
 * - basit sınıf adlarını proje FQN'lerine yükseltir (aynı paket > benzersiz ad)
 * - her çağrı adımına internal (proje içi) bayrağını koyar
 * - dış dünya sınırlarını işaretler (db / http / kafka / mail)
 */
public class Linker {

    private final Map<String, ClassModel> byFqn = new HashMap<>();
    private final Map<String, List<ClassModel>> bySimpleName = new HashMap<>();

    public void link(List<ClassModel> classes) {
        for (ClassModel c : classes) {
            byFqn.put(c.fqn, c);
            bySimpleName.computeIfAbsent(c.name, k -> new java.util.ArrayList<>()).add(c);
        }
        for (ClassModel c : classes) {
            for (ClassModel.MethodModel m : c.methods) {
                for (ClassModel.FlowStep s : m.flow) {
                    if (!"call".equals(s.type) || s.targetClass == null) continue;
                    s.targetClass = upgrade(s.targetClass, c.packageName);
                    s.internal = byFqn.containsKey(s.targetClass);
                    s.boundary = detectBoundary(s);
                }
            }
        }
    }

    public ClassModel classOf(String fqn) {
        return byFqn.get(fqn);
    }

    /** Basit adı proje FQN'ine çevir: önce aynı paket, sonra projede benzersiz basit ad. */
    private String upgrade(String name, String currentPackage) {
        if (name.contains(".")) return name; // zaten FQN
        ClassModel samePkg = byFqn.get(currentPackage + "." + name);
        if (samePkg != null) return samePkg.fqn;
        List<ClassModel> candidates = bySimpleName.get(name);
        if (candidates != null && candidates.size() == 1) return candidates.get(0).fqn;
        return name;
    }

    /** Çağrının dış dünya sınırını belirle. */
    private String detectBoundary(ClassModel.FlowStep s) {
        String target = s.targetClass;
        String simple = simpleName(target);

        // proje içi repository arayüzü
        ClassModel targetClass = byFqn.get(target);
        if (targetClass != null && "repository".equals(targetClass.stereotype)) return "db";
        if (targetClass != null && "httpclient".equals(targetClass.stereotype)) return "http";

        // bilinen framework tipleri
        switch (simple) {
            case "RestTemplate", "WebClient", "RestClient", "HttpClient", "OkHttpClient":
                return "http";
            case "KafkaTemplate", "KafkaProducer", "KafkaConsumer":
                return "kafka";
            case "JdbcTemplate", "NamedParameterJdbcTemplate", "EntityManager", "Session", "MongoTemplate":
                return "db";
            case "JavaMailSender", "MailSender":
                return "mail";
            case "RabbitTemplate", "JmsTemplate":
                return "queue";
        }
        // kaynakta olmayan ama adı Repository/Dao ile biten tipler (harici DAO jar'ları)
        if (simple.endsWith("Repository") || simple.endsWith("Dao")) return "db";
        return null;
    }

    private String simpleName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
