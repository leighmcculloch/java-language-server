package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.util.Collections;
import org.junit.Test;

public class CheckTest {
    static {
        Main.setRootFormat();
    }

    private static final JavaCompilerService compiler =
            new JavaCompilerService(Collections.emptySet(), Collections.emptySet());
    private final URI uri = FindResource.uri("/org/javacs/check/CheckExamples.java");
    private final CompileFile compile = compiler.compileFile(uri);

    @Test
    public void identifier() {
        var check = compile.check(6, 9);
        var expr = parse("x");
        var el = check.check(expr);
        assertThat(el, hasToString("int"));
    }

    @Test
    public void selectField() {
        var check = compile.check(14, 9);
        var expr = parse("param.field");
        var el = check.check(expr);
        assertThat(el, hasToString("int"));
    }

    @Test
    public void callMethod() {
        var check = compile.check(22, 9);
        var expr = parse("intMethod()");
        var el = check.check(expr);
        assertThat(el, hasToString("int"));
    }

    @Test
    public void callMemberMethod() {
        var check = compile.check(26, 9);
        var expr = parse("param.intMethod()");
        var el = check.check(expr);
        assertThat(el, hasToString("int"));
    }

    Tree parse(String expr) {
        var file = "/org/javacs/check/Wrapper.java";
        var template = FindResource.contents(file);
        var uri = FindResource.uri(file);
        template = template.replace("$EXPR", expr);
        var parse = Parser.parse(new SourceFileObject(uri, template));
        class FindExpr extends TreePathScanner<Void, Void> {
            Tree found;

            @Override
            public Void visitBlock(BlockTree t, Void __) {
                found = t.getStatements().get(0);
                return null;
            }
        }
        var find = new FindExpr();
        find.scan(parse, null);
        return fix(find.found);
    }

    private Tree fix(Tree parsed) {
        if (parsed instanceof ExpressionStatementTree) {
            var exprStmt = (ExpressionStatementTree) parsed;
            var expr = exprStmt.getExpression();
            return fix(expr);
        } else if (parsed instanceof ErroneousTree) {
            var error = (ErroneousTree) parsed;
            for (var t : error.getErrorTrees()) {
                return fix(t);
            }
        }
        return parsed;
    }
}
