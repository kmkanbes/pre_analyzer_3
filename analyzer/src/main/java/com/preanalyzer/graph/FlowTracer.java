package com.preanalyzer.graph;

import com.preanalyzer.model.ClassModel;
import com.preanalyzer.model.EntryPoint;
import com.preanalyzer.model.EntryPoint.TraceNode;
import com.preanalyzer.model.EntryPoint.TraceStep;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bir giriş noktasından başlayarak akışı adım adım açar:
 * her proje içi çağrının altına o metodun kendi akışı yerleştirilir.
 * Döngüsel çağrılar ve derinlik limiti güvenle kesilir.
 */
public class FlowTracer {

    private static final int MAX_DEPTH = 10;

    private final MethodResolver resolver;

    public FlowTracer(MethodResolver resolver) {
        this.resolver = resolver;
    }

    public void traceAll(java.util.List<EntryPoint> entryPoints) {
        for (EntryPoint ep : entryPoints) {
            int argc = countParams(ep.signature);
            MethodResolver.Target t = resolver.find(ep.classFqn, ep.method, argc);
            if (t != null) {
                Deque<String> stack = new ArrayDeque<>();
                ep.trace = buildNode(t, stack, 0);
            }
        }
    }

    private TraceNode buildNode(MethodResolver.Target target, Deque<String> stack, int depth) {
        ClassModel c = target.cls();
        ClassModel.MethodModel m = target.method();
        TraceNode node = new TraceNode();
        node.classFqn = c.fqn;
        node.className = c.name;
        node.method = m.name;
        node.signature = m.signature;
        node.stereotype = c.stereotype;
        node.line = m.line;

        String key = c.fqn + "#" + m.signature;
        stack.push(key);
        try {
            for (ClassModel.FlowStep s : m.flow) {
                TraceStep ts = copyStep(s);
                if ("call".equals(s.type) && s.internal && s.targetClass != null && !"new".equals(s.method)) {
                    int argc = s.args == null ? 0 : s.args.size();
                    MethodResolver.Target callee = resolver.find(s.targetClass, s.method, argc);
                    if (callee != null) {
                        ClassModel calleeCls = callee.cls();
                        ts.targetStereotype = calleeCls.stereotype;
                        String calleeKey = calleeCls.fqn + "#" + callee.method().signature;
                        if (stack.contains(calleeKey)) {
                            ts.callee = stub(callee, true, false);
                        } else if (depth >= MAX_DEPTH) {
                            ts.callee = stub(callee, false, true);
                        } else if (!callee.method().flow.isEmpty()) {
                            ts.callee = buildNode(callee, stack, depth + 1);
                        }
                    }
                } else if ("call".equals(s.type) && s.targetClass != null) {
                    ClassModel tc = resolver.classOf(s.targetClass);
                    if (tc != null) ts.targetStereotype = tc.stereotype;
                }
                node.steps.add(ts);
            }
        } finally {
            stack.pop();
        }
        return node;
    }

    private TraceNode stub(MethodResolver.Target t, boolean cycle, boolean depthLimit) {
        TraceNode n = new TraceNode();
        n.classFqn = t.cls().fqn;
        n.className = t.cls().name;
        n.method = t.method().name;
        n.signature = t.method().signature;
        n.stereotype = t.cls().stereotype;
        n.line = t.method().line;
        n.cycle = cycle;
        n.depthLimit = depthLimit;
        return n;
    }

    private TraceStep copyStep(ClassModel.FlowStep s) {
        TraceStep ts = new TraceStep();
        ts.step = s.step;
        ts.line = s.line;
        ts.type = s.type;
        ts.object = s.object;
        ts.targetClass = s.targetClass;
        ts.method = s.method;
        ts.args = s.args;
        ts.assignTo = s.assignTo;
        ts.condition = s.condition;
        ts.context = s.context;
        ts.internal = s.internal;
        ts.boundary = s.boundary;
        return ts;
    }

    private int countParams(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open + 1) return 0;
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return 0;
        // generic virgüllerini sayma: List<Map<String,X>> tek parametre
        int count = 1, depth = 0;
        for (char ch : inner.toCharArray()) {
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) count++;
        }
        return count;
    }
}
