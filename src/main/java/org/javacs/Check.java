package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Check uses an existing JavacTask/Scope to typecheck simple expressions that weren't part of the original compilation.
 */
class Check {
    private final JavacTask task;
    private final Scope scope;
    private final Trees trees;
    private final Elements elements;
    private final Types types;

    Check(JavacTask task, Scope scope) {
        this.task = task;
        this.scope = scope;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
    }

    private TypeMirror empty() {
        return elements.getTypeElement("java.lang.Void").asType();
    }

    private boolean isThisOrSuper(Name name) {
        return name.contentEquals("this") || name.contentEquals("super");
    }

    private List<TypeMirror> thisMembers(Element thisOrSuper, String identifier) {
        var list = new ArrayList<TypeMirror>();
        var thisType = thisOrSuper.asType();
        var thisEl = types.asElement(thisType);
        if (thisEl instanceof TypeElement) {
            var thisTypeEl = (TypeElement) thisEl;
            for (var m : elements.getAllMembers(thisTypeEl)) {
                if (m.getSimpleName().contentEquals(identifier)) {
                    list.add(m.asType());
                }
            }
        }
        return list;
    }

    private List<TypeMirror> members(Scope scope, String identifier) {
        var list = new ArrayList<TypeMirror>();
        for (var el : scope.getLocalElements()) {
            if (el.getSimpleName().contentEquals(identifier)) {
                list.add(el.asType());
            }
            if (isThisOrSuper(el.getSimpleName())) {
                list.addAll(thisMembers(el, identifier));
            }
        }
        return list;
    }

    private List<ExecutableType> envMethod(String identifier) {
        // TODO outermost scopes take forever to resolve, skip them like in CompileFocus?
        var matches = new ArrayList<ExecutableType>();
        for (var s = scope; s != null; s = s.getEnclosingScope()) {
            for (var el : members(s, identifier)) {
                if (el.getKind() == TypeKind.EXECUTABLE) {
                    matches.add((ExecutableType) el);
                }
            }
        }
        return matches;
    }

    private TypeMirror env(String identifier) {
        // TODO outermost scopes take forever to resolve, skip them like in CompileFocus?
        for (var s = scope; s != null; s = s.getEnclosingScope()) {
            for (var el : members(s, identifier)) {
                if (el.getKind() != TypeKind.EXECUTABLE) {
                    return el;
                }
            }
        }
        return empty();
    }

    private boolean isCompatible(ExecutableType method, List<TypeMirror> args) {
        var params = method.getParameterTypes();
        if (params.size() != args.size()) return false;
        for (var i = 0; i < params.size(); i++) {
            var p = params.get(i);
            var a = args.get(i);
            if (!types.isAssignable(a, p)) return false;
        }
        return true;
    }

    private List<TypeMirror> checkList(List<? extends ExpressionTree> ts) {
        var els = new ArrayList<TypeMirror>();
        for (var t : ts) {
            var e = check(t);
            els.add(e);
        }
        return els;
    }

    List<ExecutableType> checkMethod(ExpressionTree t) {
        if (t instanceof IdentifierTree) {
            var id = (IdentifierTree) t;
            return envMethod(id.getName().toString());
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            var expr = check(select.getExpression());
            var exprEl = types.asElement(expr);
            if (!(exprEl instanceof TypeElement)) return List.of();
            var members = elements.getAllMembers((TypeElement) exprEl);
            var name = select.getIdentifier();
            var matches = new ArrayList<ExecutableType>();
            for (var m : members) {
                if (m.getSimpleName().contentEquals(name) && m.getKind() == ElementKind.METHOD) {
                    matches.add((ExecutableType) m.asType());
                }
            }
            return matches;
        } else {
            return List.of();
        }
    }

    TypeMirror check(Tree t) {
        if (t instanceof ArrayAccessTree) {
            var access = (ArrayAccessTree) t;
            var expr = check(access.getExpression());
            if (!(expr instanceof ArrayType)) return empty();
            var array = (ArrayType) expr;
            return array.getComponentType();
        } else if (t instanceof ConditionalExpressionTree) {
            var cond = (ConditionalExpressionTree) t;
            return check(cond.getTrueExpression());
        } else if (t instanceof IdentifierTree) {
            var id = (IdentifierTree) t;
            return env(id.getName().toString());
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            var expr = check(select.getExpression());
            var exprEl = types.asElement(expr);
            if (!(exprEl instanceof TypeElement)) return empty();
            var members = elements.getAllMembers((TypeElement) exprEl);
            var name = select.getIdentifier();
            for (var m : members) {
                if (m.getSimpleName().contentEquals(name) && m.getKind() != ElementKind.METHOD) {
                    return m.asType();
                }
            }
            return empty();
        } else if (t instanceof MethodInvocationTree) {
            var invoke = (MethodInvocationTree) t;
            var overloads = checkMethod(invoke.getMethodSelect());
            var args = checkList(invoke.getArguments());
            for (var m : overloads) {
                if (isCompatible(m, args)) {
                    return m.getReturnType();
                }
            }
            return empty();
        } else if (t instanceof ParenthesizedTree) {
            var paren = (ParenthesizedTree) t;
            return check(paren.getExpression());
        } else {
            return empty();
        }
    }
}
