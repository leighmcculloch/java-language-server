package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private Tree.Kind retainedPart; // TODO not coloring correctly
    private TypeMirror retainedType;

    Check(JavacTask task, Scope scope) {
        this.task = task;
        this.scope = scope;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
    }

    Check withRetainedType(Tree.Kind retainedPart, TypeMirror retainedType) {
        this.retainedPart = retainedPart;
        this.retainedType = retainedType;
        return this;
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

    private List<ExecutableType> checkMethod(ExpressionTree t) {
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

    /**
     * Check the type of a tree without invoking the full Java compiler. Some expressions can't be checked, see
     * cantCheck(...) for how to handle those.
     */
    TypeMirror check(Tree t) {
        if (!canCheck(t)) {
            if (t.getKind() == retainedPart) {
                return retainedType;
            } else {
                return empty();
            }
        } else if (t instanceof ArrayAccessTree) {
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
            if (overloads.size() == 1) return overloads.get(0).getReturnType();
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

    /**
     * cantCheck(_, root, line) finds the part of the expression to the left of the cursor that can't be checked by
     * `check(Tree)`. If this part of the expression has previously been typechecked by javac, the previous type can be
     * re-used by calling `withRetainedType(kind, type)`.
     */
    static Optional<TreePath> cantCheck(JavacTask task, CompilationUnitTree root, int line, int character) {
        var path = beforeCursor(task, root, line, character);
        return path.flatMap(Check::findCantCheck);
    }

    private static Optional<TreePath> findCantCheck(TreePath path) {
        var t = path.getLeaf();
        if (!canCheck(t)) {
            return Optional.of(path);
        } else if (t instanceof ArrayAccessTree) {
            var access = (ArrayAccessTree) t;
            return findCantCheck(new TreePath(path, access.getExpression()));
        } else if (t instanceof ConditionalExpressionTree) {
            var cond = (ConditionalExpressionTree) t;
            return findCantCheck(new TreePath(path, cond.getTrueExpression()));
        } else if (t instanceof IdentifierTree) {
            return Optional.empty();
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            return findCantCheck(new TreePath(path, select.getExpression()));
        } else if (t instanceof MethodInvocationTree) {
            // If any part of the method call can't be checked, then the whole method can't be checked
            // TODO we could be more aggressive when there are no overloads
            var invoke = (MethodInvocationTree) t;
            if (!canCheck(invoke.getMethodSelect())) {
                return Optional.of(path);
            }
            for (var arg : invoke.getArguments()) {
                if (!canCheck(arg)) {
                    return Optional.of(path);
                }
            }
            return Optional.empty();
        } else if (t instanceof ParenthesizedTree) {
            var paren = (ParenthesizedTree) t;
            return findCantCheck(new TreePath(path, paren.getExpression()));
        } else {
            return Optional.of(path);
        }
    }

    private static boolean canCheck(Tree t) {
        switch (t.getKind()) {
            case ARRAY_ACCESS:
            case CONDITIONAL_EXPRESSION:
            case IDENTIFIER:
            case MEMBER_SELECT:
            case PARENTHESIZED:
                return true;
            case METHOD_INVOCATION:
                var invoke = (MethodInvocationTree) t;
                if (!canCheck(invoke.getMethodSelect())) return false;
                for (var arg : invoke.getArguments()) {
                    if (!canCheck(arg)) return false;
                }
                return true;
            default:
                return false;
        }
    }

    private static Optional<TreePath> beforeCursor(JavacTask task, CompilationUnitTree root, int line, int character) {
        var pos = Trees.instance(task).getSourcePositions();
        var lines = root.getLineMap();
        var cursor = lines.getPosition(line, character);

        // Find line
        var findLine =
                new TreePathScanner<Void, Void>() {
                    TreePath found;

                    boolean includesCursor(Tree t) {
                        var start = pos.getStartPosition(root, t);
                        var end = pos.getEndPosition(root, t);
                        return start <= cursor && cursor <= end;
                    }

                    void check(List<? extends Tree> lines) {
                        for (var s : lines) {
                            if (includesCursor(s)) {
                                found = new TreePath(getCurrentPath(), s);
                            }
                        }
                    }

                    @Override
                    public Void visitClass(ClassTree t, Void __) {
                        check(t.getMembers());
                        return super.visitClass(t, null);
                    }

                    @Override
                    public Void visitBlock(BlockTree t, Void __) {
                        check(t.getStatements());
                        return super.visitBlock(t, null);
                    }
                };
        findLine.scan(root, null);
        if (findLine.found == null) return Optional.empty();

        // Find part of expression to the left of cursor
        var findLeft =
                new TreePathScanner<Void, Void>() {
                    TreePath found;

                    boolean beforeCursor(Tree t) {
                        var start = pos.getStartPosition(root, t);
                        var end = pos.getEndPosition(root, t);
                        if (start == -1 || end == -1) return false;
                        return start <= cursor && end <= cursor;
                    }

                    @Override
                    public Void scan(Tree t, Void __) {
                        if (found == null && beforeCursor(t)) {
                            found = getCurrentPath();
                        }
                        return super.scan(t, null);
                    }
                };
        findLeft.scan(findLine.found, null);
        if (findLeft.found == null) return Optional.empty();

        return Optional.of(findLeft.found);
    }
}
