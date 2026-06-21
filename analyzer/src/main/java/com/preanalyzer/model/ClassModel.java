package com.preanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Projedeki tek bir Java tipinin (class/interface/enum/record) modeli. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClassModel {
    public String fqn;
    public String name;
    public String packageName;
    public String module;
    public String filePath;
    public String kind;        // class | interface | enum | record | annotation
    /** controller | service | repository | entity | dto | config | scheduler | component | listener | main | other */
    public String stereotype;
    public List<String> annotations = new ArrayList<>();
    public List<String> extendsTypes = new ArrayList<>();
    public List<String> implementsTypes = new ArrayList<>();
    public List<FieldModel> fields = new ArrayList<>();
    public List<MethodModel> methods = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldModel {
        public String name;
        public String type;
        /** Spring DI ile enjekte edilen bağımlılık mı (constructor/field injection) */
        public boolean injected;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MethodModel {
        public String name;
        public String signature;     // name(paramType1, paramType2)
        public String returnType;
        public List<String> params = new ArrayList<>();
        public List<String> annotations = new ArrayList<>();
        public int line;
        public List<FlowStep> flow = new ArrayList<>();
    }

    /** Metod gövdesindeki sıralı bir adım. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FlowStep {
        public int step;
        public int line;
        /** call | if | loop | try | return | throw | switch */
        public String type;
        public String object;        // çağrının yapıldığı nesne/sınıf adı
        public String targetClass;   // çözümlenen hedef sınıf FQN (çözümlenemezse basit ad)
        public String method;
        public List<String> args;
        public String assignTo;      // sonucun atandığı değişken
        public String condition;     // if/loop/switch koşulu
        /** null (üst seviye) | if | else | loop | try | catch | finally | case */
        public String context;
        public boolean internal;     // hedef sınıf proje içinde mi
        /** db | http | kafka | mail | none — dış dünya sınırı */
        public String boundary;
    }
}
