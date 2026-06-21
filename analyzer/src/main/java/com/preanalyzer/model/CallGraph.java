package com.preanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Proje geneli metod çağrı grafiği. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CallGraph {
    public List<Node> nodes = new ArrayList<>();
    public List<Edge> edges = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Node {
        public String id;          // fqn#method(sig)
        public String classFqn;
        public String className;
        public String method;
        public String stereotype;
        public boolean entryPoint;
        public boolean external;   // proje dışı (kaynakta yok)
        public String boundary;    // db | http | kafka | mail
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Edge {
        public String from;
        public String to;
        public int line;

        public Edge() {}
        public Edge(String from, String to, int line) {
            this.from = from; this.to = to; this.line = line;
        }
    }
}
