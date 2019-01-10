package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Interpreter uses an existing JavacTask/Scope to evaluate simple expressions that weren't part of the original
 * compilation.
 */
class Interpreter {
    private final JavacTask task;
    private final Scope scope;
    private final Trees trees;
    private final Elements elements;
    private final Types types;

    Interpreter(JavacTask task, Scope scope) {
        this.task = task;
        this.scope = scope;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
    }

    private Element empty() {
        return elements.getTypeElement("java.lang.Void");
    }

    private boolean isThisOrSuper(Name name) {
        return name.contentEquals("this") || name.contentEquals("super");
    }

    private List<Element> members(Scope scope) {
        var list = new ArrayList<Element>();
        for (var el : scope.getLocalElements()) {
            list.add(el);
            if (isThisOrSuper(el.getSimpleName())) {
                var thisType = el.asType();
                var thisEl = types.asElement(thisType);
                if (thisEl instanceof TypeElement) {
                    list.addAll(elements.getAllMembers((TypeElement) thisEl));
                }
            }
        }
        return list;
    }

    private List<ExecutableElement> envMethod(String identifier) {
        // TODO outermost scopes take forever to resolve, skip them like in CompileFocus?
        var matches = new ArrayList<ExecutableElement>();
        for (var s = scope; s != null; s = s.getEnclosingScope()) {
            for (var el : members(s)) {
                if (el.getSimpleName().contentEquals(identifier) && el.getKind() == ElementKind.METHOD) {
                    var exe = (ExecutableElement) el;
                    matches.add(exe);
                }
            }
        }
        return matches;
    }

    private Element env(String identifier) {
        // TODO outermost scopes take forever to resolve, skip them like in CompileFocus?
        for (var s = scope; s != null; s = s.getEnclosingScope()) {
            for (var el : members(s)) {
                if (el.getSimpleName().contentEquals(identifier) && el.getKind() != ElementKind.METHOD) {
                    return el;
                }
            }
        }
        return empty();
    }

    private boolean isCompatible(ExecutableElement method, List<Element> args) {
        var params = method.getParameters();
        if (params.size() != args.size()) return false;
        for (var i = 0; i < params.size(); i++) {
            var p = params.get(i).asType();
            var a = args.get(i).asType();
            if (!types.isAssignable(a, p)) return false;
        }
        return true;
    }

    private List<Element> evalList(List<? extends ExpressionTree> ts) {
        var els = new ArrayList<Element>();
        for (var t : ts) {
            var e = eval(t);
            els.add(e);
        }
        return els;
    }

    List<ExecutableElement> evalMethod(ExpressionTree t) {
        if (t instanceof IdentifierTree) {
            var id = (IdentifierTree) t;
            return envMethod(id.getName().toString());
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            var expr = eval(select.getExpression());
            var exprType = expr.asType();
            var exprEl = types.asElement(exprType);
            if (!(exprEl instanceof TypeElement)) return List.of();
            var members = elements.getAllMembers((TypeElement) exprEl);
            var name = select.getIdentifier();
            var matches = new ArrayList<ExecutableElement>();
            for (var m : members) {
                if (m.getSimpleName().contentEquals(name) && m.getKind() == ElementKind.METHOD) {
                    var exe = (ExecutableElement) m;
                    matches.add(exe);
                }
            }
            return matches;
        } else {
            return List.of();
        }
    }

    Element eval(Tree t) {
        if (t instanceof IdentifierTree) {
            var id = (IdentifierTree) t;
            return env(id.getName().toString());
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            var expr = eval(select.getExpression());
            var exprType = expr.asType();
            var exprEl = types.asElement(exprType);
            if (!(exprEl instanceof TypeElement)) return empty();
            var members = elements.getAllMembers((TypeElement) exprEl);
            var name = select.getIdentifier();
            for (var m : members) {
                if (m.getSimpleName().contentEquals(name) && m.getKind() == ElementKind.FIELD) {
                    return m;
                }
            }
            return empty();
        } else if (t instanceof MethodInvocationTree) {
            var invoke = (MethodInvocationTree) t;
            var overloads = evalMethod(invoke.getMethodSelect());
            var args = evalList(invoke.getArguments());
            for (var m : overloads) {
                if (isCompatible(m, args)) {
                    var type = m.getReturnType();
                    return types.asElement(type);
                }
            }
            return empty();
        } else {
            return empty();
        }
    }
}
