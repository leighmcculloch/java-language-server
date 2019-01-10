package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.util.Collections;
import javax.lang.model.element.*;
import org.junit.Test;

public class InterpreterTest {
    static {
        Main.setRootFormat();
    }

    private static final JavaCompilerService compiler =
            new JavaCompilerService(Collections.emptySet(), Collections.emptySet());
    private final URI uri = FindResource.uri("/org/javacs/interpreter/InterpreterExamples.java");
    private final CompileFile compile = compiler.compileFile(uri);

    @Test
    public void envLocalVariable() {
        var interpreter = compile.interpreter(6, 9);
        var el = interpreter.env("x");
        assertThat(el.getKind(), equalTo(ElementKind.LOCAL_VARIABLE));
        assertThat(el.asType(), hasToString("int"));
    }

    @Test
    public void envMethodParameter() {
        var interpreter = compile.interpreter(10, 9);
        var el = interpreter.env("param");
        assertThat(el.getKind(), equalTo(ElementKind.PARAMETER));
        assertThat(el.asType(), hasToString("int"));
    }

    @Test
    public void identifier() {
        var interpreter = compile.interpreter(6, 9);
        var expr = parse("x");
        var el = interpreter.eval(expr);
        assertThat(el.getKind(), equalTo(ElementKind.LOCAL_VARIABLE));
        assertThat(el.asType(), hasToString("int"));
    }

    @Test
    public void selectField() {
        var interpreter = compile.interpreter(14, 9);
        var expr = parse("param.field");
        var el = interpreter.eval(expr);
        assertThat(el.getKind(), equalTo(ElementKind.FIELD));
        assertThat(el.asType(), hasToString("int"));
    }

    @Test
    public void callMethod() {
        var interpreter = compile.interpreter(22, 9);
        var expr = parse("intMethod()");
        var el = interpreter.eval(expr);
        assertThat(el.getKind(), equalTo(ElementKind.CLASS));
        assertThat(el.asType(), hasToString("int"));
    }

    Tree parse(String expr) {
        var file = "/org/javacs/interpreter/Wrapper.java";
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
