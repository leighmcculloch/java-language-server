package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.junit.Ignore;
import org.junit.Test;

public class GotoTest {
    private static final String file = "/org/javacs/example/Goto.java";
    private static final String defaultConstructorFile = "/org/javacs/example/GotoDefaultConstructor.java";

    @Test
    public void localVariable() {
        var suggestions = doGoto(file, 9, 8);

        assertThat(suggestions, contains("Goto.java:5(9)"));
    }

    @Test
    public void defaultConstructor() {
        var suggestions = doGoto(defaultConstructorFile, 4, 45);

        assertThat(suggestions, contains("GotoDefaultConstructor.java:3(1)"));
    }

    @Test
    public void constructor() {
        var suggestions = doGoto(file, 10, 20);

        assertThat(suggestions, contains("Goto.java:3(1)"));
    }

    @Test
    public void className() {
        var suggestions = doGoto(file, 15, 8);

        assertThat(suggestions, contains("Goto.java:3(1)"));
    }

    @Test
    public void staticField() {
        var suggestions = doGoto(file, 12, 21);

        assertThat(suggestions, contains("Goto.java:36(5)"));
    }

    @Test
    public void field() {
        var suggestions = doGoto(file, 13, 21);

        assertThat(suggestions, contains("Goto.java:37(5)"));
    }

    @Test
    public void staticMethod() {
        var suggestions = doGoto(file, 15, 13);

        assertThat(suggestions, contains("Goto.java:38(5)"));
    }

    @Test
    public void method() {
        var suggestions = doGoto(file, 16, 13);

        assertThat(suggestions, contains("Goto.java:41(5)"));
    }

    @Test
    public void staticMethodReference() {
        var suggestions = doGoto(file, 18, 26);

        assertThat(suggestions, contains("Goto.java:38(5)"));
    }

    @Test
    public void methodReference() {
        var suggestions = doGoto(file, 19, 26);

        assertThat(suggestions, contains("Goto.java:41(5)"));
    }

    @Test
    public void otherStaticMethod() {
        var suggestions = doGoto(file, 28, 24);

        assertThat(suggestions, contains(startsWith("GotoOther.java:")));
    }

    @Test
    public void otherMethod() {
        var suggestions = doGoto(file, 29, 17);

        assertThat(suggestions, contains(startsWith("GotoOther.java:")));
    }

    @Test
    public void otherCompiledFile() {
        var suggestions = doGoto(file, 28, 24);

        assertThat(suggestions, contains(startsWith("GotoOther.java:")));
    }

    @Test
    @Ignore // TODO
    public void typeParam() {
        var suggestions = doGoto(file, 45, 11);

        assertThat(suggestions, contains("Goto.java:3(19)"));
    }

    @Test
    public void gotoEnum() {
        String file = "/org/javacs/example/GotoEnum.java";

        assertThat(doGoto(file, 5, 30), not(empty()));
        assertThat(doGoto(file, 5, 35), not(empty()));
    }

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<String> doGoto(String file, int row, int column) {
        TextDocumentIdentifier document = new TextDocumentIdentifier();

        document.setUri(FindResource.uri(file).toString());

        Position position = new Position();

        position.setLine(row);
        position.setCharacter(column);

        TextDocumentPositionParams p = new TextDocumentPositionParams();

        p.setTextDocument(document);
        p.setPosition(position);

        // TODO extends is not coloring correctly
        List<? extends Location> locations;
        try {
            locations = server.getTextDocumentService().definition(p).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        var strings = new ArrayList<String>();
        for (var l : locations) {
            var fileName = Paths.get(URI.create(l.getUri())).getFileName();
            var start = l.getRange().getStart();
            strings.add(String.format("%s:%d(%d)", fileName, start.getLine() + 1, start.getCharacter() + 1));
        }
        return strings;
    }
}
