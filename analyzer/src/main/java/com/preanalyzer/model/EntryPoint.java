package com.preanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Uygulamaya dışarıdan girilen bir nokta (REST endpoint, scheduler, kafka listener, main...). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntryPoint {
    public String id;
    /** REST | JAXRS | SERVLET | FILTER | LISTENER | SCHEDULED | KAFKA | JMS | MESSAGING | EVENT | RUNNER | MAIN */
    public String kind;
    public String httpMethod;    // GET/POST/... (REST/JAXRS/SERVLET için)
    public String path;          // /api/x (REST/JAXRS için)
    public List<String> urlPatterns; // SERVLET/FILTER url eşlemeleri (@WebServlet veya web.xml)
    public String produces;      // JAXRS @Produces (ör. application/json)
    public String consumes;      // JAXRS @Consumes
    public String cron;          // SCHEDULED için
    public String fixedRate;
    public String topics;        // KAFKA / MESSAGING için
    public String detail;        // ek bilgi: dinleyici arayüzleri, servlet/filter adı vb.
    public String source;        // anotasyon | web.xml | kod
    public String classFqn;
    public String className;
    public String method;
    public String signature;
    public int line;
    /** Giriş noktasından itibaren adım adım çözümlenmiş akış. */
    public TraceNode trace;

    /** Bir metodun akışı: adımlar + proje içi çağrılarda iç içe düğümler. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TraceNode {
        public String classFqn;
        public String className;
        public String method;
        public String signature;
        public String stereotype;
        public int line;
        public boolean cycle;        // döngüsel çağrı kesildi
        public boolean depthLimit;   // derinlik limitine takıldı
        public List<TraceStep> steps = new ArrayList<>();
    }

    /** Trace içindeki tek adım: FlowStep + (proje içi çağrıysa) hedef metodun alt trace'i. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TraceStep {
        public int step;
        public int line;
        public String type;
        public String object;
        public String targetClass;
        public String targetStereotype;
        public String method;
        public List<String> args;
        public String assignTo;
        public String condition;
        public String context;
        public boolean internal;
        public String boundary;
        public TraceNode callee;     // internal çağrının açılımı
    }
}
