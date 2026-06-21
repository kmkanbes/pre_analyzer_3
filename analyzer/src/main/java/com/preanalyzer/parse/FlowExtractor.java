package com.preanalyzer.parse;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.preanalyzer.model.ClassModel.FlowStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir metod gövdesini sıralı akış adımlarına (FlowStep) dönüştürür:
 * çağrılar, if/else, döngüler, try/catch, return/throw, switch.
 * Çağrı hedef sınıfı önce sembol çözümleyici ile, olmazsa
 * alan/parametre/lokal değişken tipi + import haritası ile bulunur.
 */
public class FlowExtractor {

    private final Map<String, String> importMap;
    private final String packageName;

    public FlowExtractor(Map<String, String> importMap, String packageName) {
        this.importMap = importMap;
        this.packageName = packageName;
    }

    public List<FlowStep> extract(BlockStmt body, String currentClassFqn, Map<String, String> fieldTypes) {
        Walker w = new Walker(currentClassFqn, fieldTypes);
        // metod parametrelerini tip haritasına ekle
        body.getParentNode().ifPresent(parent -> {
            if (parent instanceof MethodDeclaration md) {
                for (Parameter p : md.getParameters()) {
                    w.localTypes.put(p.getNameAsString(), SourceAnalyzer.baseType(p.getTypeAsString()));
                }
            }
        });
        w.walkBlock(body, null);
        return w.steps;
    }

    private class Walker {
        final String currentClassFqn;
        final Map<String, String> fieldTypes;
        final Map<String, String> localTypes = new HashMap<>();
        final List<FlowStep> steps = new ArrayList<>();
        int counter = 0;

        Walker(String currentClassFqn, Map<String, String> fieldTypes) {
            this.currentClassFqn = currentClassFqn;
            this.fieldTypes = fieldTypes;
        }

        void walkBlock(Statement stmt, String context) {
            if (stmt instanceof BlockStmt block) {
                for (Statement s : block.getStatements()) walk(s, context);
            } else {
                walk(stmt, context);
            }
        }

        void walk(Statement stmt, String context) {
            if (stmt instanceof ExpressionStmt es) {
                handleExpression(es.getExpression(), context);
            } else if (stmt instanceof IfStmt is) {
                FlowStep step = newStep(stmt, "if", context);
                step.condition = trunc(is.getCondition().toString(), 120);
                steps.add(step);
                walkBlock(is.getThenStmt(), "if");
                is.getElseStmt().ifPresent(elseStmt -> {
                    if (elseStmt instanceof IfStmt) {
                        walk(elseStmt, "else"); // else-if zinciri yeni if adımı üretir
                    } else {
                        walkBlock(elseStmt, "else");
                    }
                });
            } else if (stmt instanceof ForEachStmt fe) {
                FlowStep step = newStep(stmt, "loop", context);
                step.condition = trunc(fe.getVariable() + " : " + fe.getIterable(), 120);
                localTypes.put(fe.getVariable().getVariable(0).getNameAsString(),
                        SourceAnalyzer.baseType(fe.getVariable().getVariable(0).getTypeAsString()));
                steps.add(step);
                walkBlock(fe.getBody(), "loop");
            } else if (stmt instanceof ForStmt fs) {
                FlowStep step = newStep(stmt, "loop", context);
                step.condition = trunc(fs.getCompare().map(Object::toString).orElse("for"), 120);
                steps.add(step);
                walkBlock(fs.getBody(), "loop");
            } else if (stmt instanceof WhileStmt ws) {
                FlowStep step = newStep(stmt, "loop", context);
                step.condition = trunc(ws.getCondition().toString(), 120);
                steps.add(step);
                walkBlock(ws.getBody(), "loop");
            } else if (stmt instanceof DoStmt ds) {
                FlowStep step = newStep(stmt, "loop", context);
                step.condition = trunc("do..while " + ds.getCondition(), 120);
                steps.add(step);
                walkBlock(ds.getBody(), "loop");
            } else if (stmt instanceof TryStmt ts) {
                steps.add(newStep(stmt, "try", context));
                walkBlock(ts.getTryBlock(), "try");
                for (CatchClause cc : ts.getCatchClauses()) {
                    localTypes.put(cc.getParameter().getNameAsString(),
                            SourceAnalyzer.baseType(cc.getParameter().getTypeAsString()));
                    walkBlock(cc.getBody(), "catch");
                }
                ts.getFinallyBlock().ifPresent(fb -> walkBlock(fb, "finally"));
            } else if (stmt instanceof ReturnStmt rs) {
                rs.getExpression().ifPresent(expr -> extractCalls(expr, context, null));
                FlowStep step = newStep(stmt, "return", context);
                rs.getExpression().ifPresent(expr -> step.condition = trunc(expr.toString(), 120));
                steps.add(step);
            } else if (stmt instanceof ThrowStmt th) {
                FlowStep step = newStep(stmt, "throw", context);
                step.condition = trunc(th.getExpression().toString(), 120);
                steps.add(step);
            } else if (stmt instanceof SwitchStmt sw) {
                FlowStep step = newStep(stmt, "switch", context);
                step.condition = trunc(sw.getSelector().toString(), 120);
                steps.add(step);
                for (SwitchEntry entry : sw.getEntries()) {
                    for (Statement s : entry.getStatements()) walk(s, "case");
                }
            } else if (stmt instanceof BlockStmt) {
                walkBlock(stmt, context);
            } else if (stmt instanceof SynchronizedStmt sy) {
                walkBlock(sy.getBody(), context);
            } else if (stmt instanceof LabeledStmt ls) {
                walk(ls.getStatement(), context);
            }
            // break/continue/empty/assert atlanır
        }

        void handleExpression(Expression expr, String context) {
            if (expr instanceof VariableDeclarationExpr vde) {
                vde.getVariables().forEach(v -> {
                    localTypes.put(v.getNameAsString(), SourceAnalyzer.baseType(v.getTypeAsString()));
                    v.getInitializer().ifPresent(init -> extractCalls(init, context, v.getNameAsString()));
                });
            } else if (expr instanceof AssignExpr ae) {
                extractCalls(ae.getValue(), context, trunc(ae.getTarget().toString(), 60));
            } else {
                extractCalls(expr, context, null);
            }
        }

        /**
         * Bir ifade içindeki tüm metod çağrılarını ve nesne oluşturmaları
         * kaynak sırasına göre adım olarak ekler; en dıştaki çağrıya assignTo yazılır.
         */
        void extractCalls(Expression expr, String context, String assignTo) {
            List<Node> callNodes = new ArrayList<>();
            expr.walk(node -> {
                if (node instanceof MethodCallExpr || node instanceof ObjectCreationExpr) {
                    callNodes.add(node);
                }
            });
            // bitiş pozisyonuna göre sırala: zincirli/iç içe çağrılarda değerlendirme sırası
            // (a.b(x.y()).c() -> y, b, c)
            callNodes.sort((a, b) -> {
                int pa = a.getEnd().map(p -> p.line * 10000 + p.column).orElse(0);
                int pb = b.getEnd().map(p -> p.line * 10000 + p.column).orElse(0);
                return Integer.compare(pa, pb);
            });
            // en dıştaki çağrı: en son değerlendirilen
            Node outermost = (expr instanceof MethodCallExpr || expr instanceof ObjectCreationExpr)
                    ? expr : (callNodes.isEmpty() ? null : callNodes.get(callNodes.size() - 1));

            for (Node node : callNodes) {
                if (node instanceof MethodCallExpr call) {
                    FlowStep step = newStepFromNode(node, "call", context);
                    step.method = call.getNameAsString();
                    step.object = call.getScope().map(s -> trunc(s.toString(), 60)).orElse("this");
                    step.args = call.getArguments().isEmpty() ? null :
                            call.getArguments().stream().map(a -> trunc(a.toString(), 60)).toList();
                    step.targetClass = resolveTarget(call);
                    if (node == outermost && assignTo != null) step.assignTo = assignTo;
                    steps.add(step);
                } else if (node instanceof ObjectCreationExpr oce) {
                    // sadece en dıştaki 'new' kaydedilir (argüman içindeki new'ler gürültü olur)
                    if (node != outermost) continue;
                    FlowStep step = newStepFromNode(node, "call", context);
                    step.method = "new";
                    step.object = oce.getTypeAsString();
                    step.targetClass = resolveSimple(SourceAnalyzer.baseType(oce.getTypeAsString()));
                    if (assignTo != null) step.assignTo = assignTo;
                    steps.add(step);
                }
            }
        }

        String resolveTarget(MethodCallExpr call) {
            // 1) sembol çözümleyici
            try {
                String fqn = call.resolve().declaringType().getQualifiedName();
                if (fqn != null && !fqn.isEmpty()) return fqn;
            } catch (Throwable ignored) { /* kaynak dışı tip */ }

            // 2) scope analizi
            Expression scope = call.getScope().orElse(null);
            if (scope == null) return currentClassFqn;
            if (scope instanceof ThisExpr) return currentClassFqn;
            if (scope instanceof NameExpr ne) {
                String name = ne.getNameAsString();
                String type = localTypes.get(name);
                if (type == null) type = fieldTypes.get(name);
                if (type != null) return resolveSimple(type);
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    return resolveSimple(name); // statik çağrı: SınıfAdı.metod()
                }
            }
            // 3) zincirli çağrı vb: scope tipini çözmeyi dene
            try {
                String desc = scope.calculateResolvedType().describe();
                return SourceAnalyzer.baseType(desc);
            } catch (Throwable ignored) { }
            return null;
        }

        /** Basit adı import haritası ile FQN'e çevir; bulunamazsa basit ad döner (Linker tamamlar). */
        String resolveSimple(String simpleName) {
            String fqn = importMap.get(simpleName);
            if (fqn != null) return fqn;
            return simpleName;
        }

        FlowStep newStep(Statement stmt, String type, String context) {
            return newStepFromNode(stmt, type, context);
        }

        FlowStep newStepFromNode(Node node, String type, String context) {
            FlowStep step = new FlowStep();
            step.step = ++counter;
            step.line = node.getBegin().map(p -> p.line).orElse(0);
            step.type = type;
            step.context = context;
            return step;
        }
    }

    private static String trunc(String s, int max) {
        s = s.replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
