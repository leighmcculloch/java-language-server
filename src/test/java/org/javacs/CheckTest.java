package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.util.Collections;
import java.util.Set;
import org.junit.Test;

public class CheckTest {
    static {
        Main.setRootFormat();
    }

    private static final JavaCompilerService compiler = createCompiler();
    private final URI uri = FindResource.uri("/org/javacs/check/CheckExamples.java");
    private final CompileFile compile = compiler.compileFile(uri);

    private static JavaCompilerService createCompiler() {
        FileStore.setWorkspaceRoots(Set.of(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT));
        return new JavaCompilerService(Collections.emptySet(), Collections.emptySet());
    }

    @Test
    public void matchesPartialName() {
        assertTrue(Check.matchesPartialName("foobar", "foo"));
        assertFalse(Check.matchesPartialName("foo", "foobar"));
    }

    @Test
    public void identifier() {
        var check = compile.check(6, 1);
        var expr = parse("x");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void selectField() {
        var check = compile.check(14, 1);
        var expr = parse("param.field");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void selectStaticField() {
        var check = compile.check(14, 1);
        var expr = parse("HasField.staticField");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void selectOtherClassStaticField() {
        var check = compile.check(14, 1);
        var expr = parse("OtherClass.staticField");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void selectFullyQualifiedStaticField() {
        var check = compile.check(14, 1);
        var expr = parse("org.javacs.check.OtherClass.staticField");
        var type = check.check(expr);
        assertThat(type, hasToString("List"));
    }

    @Test
    public void callMethod() {
        var check = compile.check(22, 1);
        var expr = parse("intMethod()");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void callMemberMethod() {
        var check = compile.check(26, 1);
        var expr = parse("param.intMethod()");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void arrayAccess() {
        var check = compile.check(32, 1);
        var expr = parse("arrayField[0]");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void conditionalExpr() {
        var check = compile.check(39, 1);
        var expr = parse("cond ? ifTrue : ifFalse");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void paren() {
        var check = compile.check(6, 1);
        var expr = parse("(x)");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
    }

    @Test
    public void anonymousClassMember() {
        var check = compile.check(43, 53);
        var expr = parse("(new Object(){ int foo; int bar; }).bar");
        var type = check.check(expr);
        assertThat(type, hasToString("int"));
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
