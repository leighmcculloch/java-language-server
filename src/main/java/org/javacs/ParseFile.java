package org.javacs;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.lsp.*;

public class ParseFile {
    private final String contents;
    private final JavacTask task;
    private final Trees trees;
    private final CompilationUnitTree root;

    ParseFile(JavaCompilerService parent, URI file) {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(file);

        this.contents = FileStore.contents(file);
        this.task = CompileFocus.singleFileTask(parent, file, contents);
        this.trees = Trees.instance(task);
        var profiler = new Profiler();
        task.addTaskListener(profiler);
        try {
            this.root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
    }

    ParseFile(JavacTask task, CompilationUnitTree root) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(root);

        try {
            this.contents = root.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.task = task;
        this.trees = Trees.instance(task);
        this.root = root;
    }

    public boolean isTestMethod(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        for (var ann : method.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Test") || name.contentEquals("org.junit.Test")) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isTestClass(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof ClassTree)) return false;
        var cls = (ClassTree) leaf;
        for (var m : cls.getMembers()) {
            if (isTestMethod(new TreePath(path, m))) return true;
        }
        return false;
    }

    public List<TreePath> declarations() {
        return declarations(root);
    }

    static List<TreePath> declarations(CompilationUnitTree root) {
        var found = new ArrayList<TreePath>();
        class FindDeclarations extends TreePathScanner<Void, Void> {
            boolean isClass(Tree t) {
                if (!(t instanceof ClassTree)) return false;
                var cls = (ClassTree) t;
                return cls.getKind() == Tree.Kind.CLASS;
            }

            boolean isPrivate(ModifiersTree t) {
                return t.getFlags().contains(Modifier.PRIVATE);
            }

            @Override
            public Void visitClass(ClassTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                found.add(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitMethod(MethodTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                var path = getCurrentPath();
                found.add(path);
                // Skip code lenses for local classes
                return null;
            }

            @Override
            public Void visitVariable(VariableTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                var path = getCurrentPath();
                var parent = path.getParentPath().getLeaf();
                if (isClass(parent)) {
                    found.add(path);
                }
                // Skip code lenses for local classes
                return null;
            }
        }
        new FindDeclarations().scan(root, null);

        return found;
    }

    public Optional<Range> range(TreePath path) {
        return range(task, contents, path);
    }

    public Optional<CompletionContext> completionContext(int line, int character) {
        LOG.info(
                String.format(
                        "Finding completion position near %s(%d,%d)...",
                        root.getSourceFile().toUri().getPath(), line, character));

        var pos = trees.getSourcePositions();
        var lines = root.getLineMap();
        var cursor = lines.getPosition(line, character);

        class FindCompletionPosition extends TreeScanner<Void, Void> {
            CompletionContext result = null;
            int insideClass = 0, insideMethod = 0;

            boolean containsCursor(Tree node) {
                return pos.getStartPosition(root, node) <= cursor && cursor <= pos.getEndPosition(root, node);
            }

            @Override
            public Void visitClass(ClassTree node, Void nothing) {
                insideClass++;
                super.visitClass(node, null);
                insideClass--;
                return null;
            }

            @Override
            public Void visitMethod(MethodTree node, Void nothing) {
                insideMethod++;
                super.visitMethod(node, null);
                insideMethod--;
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void nothing) {
                super.visitMemberSelect(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getExpression()) && result == null) {
                    LOG.info("...position cursor before '.' in " + node);
                    long offset = pos.getEndPosition(root, node.getExpression());
                    int line = (int) lines.getLineNumber(offset), character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getIdentifier(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.MemberSelect,
                                    partialName);
                }
                return null;
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void nothing) {
                super.visitMemberReference(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getQualifierExpression()) && result == null) {
                    LOG.info("...position cursor before '::' in " + node);
                    long offset = pos.getEndPosition(root, node.getQualifierExpression());
                    int line = (int) lines.getLineNumber(offset), character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getName(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.MemberReference,
                                    partialName);
                }
                return null;
            }

            @Override
            public Void visitCase(CaseTree node, Void nothing) {
                var containsCursor = containsCursor(node);
                for (var s : node.getStatements()) {
                    if (containsCursor(s)) containsCursor = false;
                }

                if (containsCursor) {
                    LOG.info("...position cursor after case " + node.getExpression());
                    long offset = pos.getEndPosition(root, node.getExpression());
                    int line = (int) lines.getLineNumber(offset), character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getExpression(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.Case,
                                    partialName);
                } else {
                    super.visitCase(node, nothing);
                }
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                super.visitIdentifier(node, nothing);

                if (containsCursor(node) && result == null) {
                    LOG.info("...position cursor after identifier " + node.getName());
                    var partialName = Objects.toString(node.getName(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.Identifier,
                                    partialName);
                }
                return null;
            }

            @Override
            public Void visitAnnotation(AnnotationTree node, Void nothing) {
                if (containsCursor(node.getAnnotationType()) && result == null) {
                    LOG.info("...position cursor after annotation " + node.getAnnotationType());
                    var id = (IdentifierTree) node.getAnnotationType();
                    var partialName = Objects.toString(id.getName(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.Annotation,
                                    partialName);
                } else {
                    super.visitAnnotation(node, nothing);
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                for (var t : node.getErrorTrees()) {
                    t.accept(this, null);
                }
                return null;
            }
        }
        var find = new FindCompletionPosition();
        find.scan(root, null);
        if (find.result == null) {
            LOG.info("...found nothing near cursor!");
            return Optional.empty();
        }
        return Optional.of(find.result);
    }

    public FoldingRanges foldingRanges() {
        var imports = new ArrayList<TreePath>();
        var blocks = new ArrayList<TreePath>();
        // TODO find comment trees
        var comments = new ArrayList<TreePath>();
        class FindFoldingRanges extends TreePathScanner<Void, Void> {
            @Override
            public Void visitClass(ClassTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitBlock(BlockTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitBlock(t, null);
            }

            @Override
            public Void visitImport(ImportTree t, Void __) {
                imports.add(getCurrentPath());
                return null;
            }
        }
        new FindFoldingRanges().scan(root, null);

        return new FoldingRanges(imports, blocks, comments);
    }

    public SourcePositions sourcePositions() {
        return trees.getSourcePositions();
    }

    /** Find and source code associated with a ptr */
    public Optional<TreePath> fuzzyFind(Ptr ptr) {
        LOG.info(
                String.format(
                        "...find fuzzy match of %s in %s ...", ptr, Parser.fileName(root.getSourceFile().toUri())));

        class FindPtr extends TreePathScanner<Void, Void> {
            int bestMatch = Ptr.NOT_MATCHED;
            TreePath found;

            void check() {
                var path = getCurrentPath();
                var mismatch = ptr.fuzzyMatch(path);
                if (mismatch < bestMatch) {
                    found = path;
                    bestMatch = mismatch;
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                check();
                return super.visitClass(node, aVoid);
            }

            @Override
            public Void visitMethod(MethodTree node, Void aVoid) {
                check();
                // Ptr can't point inside a method
                return null;
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                check();
                // Ptr can't point inside a method
                return null;
            }
        }
        var find = new FindPtr();
        find.scan(root, null);
        if (find.found != null)
            LOG.info(
                    String.format(
                            "...`%s` with score %d is best match",
                            Parser.describeTree(find.found.getLeaf()), find.bestMatch));
        else LOG.info("...no match found");
        return Optional.ofNullable(find.found);
    }

    public DocCommentTree doc(TreePath path) {
        // Find ptr in the file
        // Find the documentation attached to el
        var docs = DocTrees.instance(task);
        var doc = docs.getDocCommentTree(path);
        if (doc == null) return EMPTY_DOC;
        return doc;
    }

    static Optional<Range> range(JavacTask task, String contents, TreePath path) {
        // Find start position
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var root = path.getCompilationUnit();
        var lines = root.getLineMap();
        var start = (int) pos.getStartPosition(root, path.getLeaf());
        var end = (int) pos.getEndPosition(root, path.getLeaf());

        // If start is -1, give up
        if (start == -1) {
            LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
            return Optional.empty();
        }
        // If end is bad, guess based on start
        if (end == -1) {
            end = start + path.getLeaf().toString().length();
        }

        if (path.getLeaf() instanceof ClassTree) {
            var cls = (ClassTree) path.getLeaf();

            // If class has annotations, skip over them
            if (!cls.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, cls.getModifiers());

            // Find position of class name
            var name = cls.getSimpleName().toString();
            start = contents.indexOf(name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof MethodTree) {
            var method = (MethodTree) path.getLeaf();

            // If method has annotations, skip over them
            if (!method.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, method.getModifiers());

            // Find position of method name
            var name = method.getName().toString();
            if (name.equals("<init>")) {
                name = JavaCompilerService.className(path);
            }
            start = contents.indexOf(name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof VariableTree) {
            var field = (VariableTree) path.getLeaf();

            // If field has annotations, skip over them
            if (!field.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, field.getModifiers());

            // Find position of method name
            var name = field.getName().toString();
            start = contents.indexOf(name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        var startLine = (int) lines.getLineNumber(start);
        var startCol = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endCol = (int) lines.getColumnNumber(end);
        var range = new Range(new Position(startLine - 1, startCol - 1), new Position(endLine - 1, endCol - 1));

        return Optional.of(range);
    }

    public List<TreePath> documentSymbols() {
        return Parser.findSymbolsMatching(root, "");
    }

    private static final DocCommentTree EMPTY_DOC = makeEmptyDoc();

    private static DocCommentTree makeEmptyDoc() {
        var file = new SourceFileObject(URI.create("file:///Foo.java"), "/** */ class Foo { }");
        var task = Parser.parseTask(file);
        var docs = DocTrees.instance(task);
        CompilationUnitTree root;
        try {
            root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        class FindEmptyDoc extends TreePathScanner<Void, Void> {
            DocCommentTree found;

            @Override
            public Void visitClass(ClassTree t, Void __) {
                found = docs.getDocCommentTree(getCurrentPath());
                return null;
            }
        }
        var find = new FindEmptyDoc();
        find.scan(root, null);
        return Objects.requireNonNull(find.found);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
