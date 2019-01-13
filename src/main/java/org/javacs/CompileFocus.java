package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import javax.lang.model.util.Types;

public class CompileFocus {
    public static final int MAX_COMPLETION_ITEMS = 50;

    private final JavaCompilerService parent;
    private final URI file;
    private final String contents;
    private final int line, character;
    private final JavacTask task;
    private final Trees trees;
    private final Types types;
    private final CompilationUnitTree root;
    private final TreePath path;

    CompileFocus(JavaCompilerService parent, URI file, int line, int character) {
        this.parent = parent;
        this.file = file;
        this.contents = Pruner.prune(file, line, character);
        this.line = line;
        this.character = character;
        this.task = singleFileTask(parent, file, this.contents);
        this.trees = Trees.instance(task);
        this.types = task.getTypes();

        var profiler = new Profiler();
        task.addTaskListener(profiler);
        try {
            this.root = task.parse().iterator().next();
            // The results of task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
        this.path = findPath(task, root, line, character);
    }

    /** Create a task that compiles a single file */
    static JavacTask singleFileTask(JavaCompilerService parent, URI file, String contents) {
        parent.diags.clear();
        return (JavacTask)
                parent.compiler.getTask(
                        null,
                        parent.fileManager,
                        parent.diags::add,
                        JavaCompilerService.options(parent.classPath),
                        Collections.emptyList(),
                        List.of(new SourceFileObject(file, contents)));
    }

    /** Find the smallest element that includes the cursor */
    public Element element() {
        return trees.getElement(path);
    }

    public Optional<TreePath> path(Element e) {
        return Optional.ofNullable(trees.getPath(e));
    }

    /** Find all overloads for the smallest method call that includes the cursor */
    public Optional<MethodInvocation> methodInvocation() {
        LOG.info(String.format("Find method invocation around %s(%d,%d)...", file, line, character));

        for (var path = this.path; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof MethodInvocationTree) {
                // Find all overloads of method
                LOG.info(String.format("...`%s` is a method invocation", path.getLeaf()));
                var invoke = (MethodInvocationTree) path.getLeaf();
                var method = trees.getElement(trees.getPath(path.getCompilationUnit(), invoke.getMethodSelect()));
                var results = new ArrayList<ExecutableElement>();
                for (var m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.METHOD && m.getSimpleName().equals(method.getSimpleName())) {
                        results.add((ExecutableElement) m);
                    }
                }
                // Figure out which parameter is active
                var activeParameter = invoke.getArguments().indexOf(this.path.getLeaf());
                LOG.info(String.format("...active parameter `%s` is %d", this.path.getLeaf(), activeParameter));
                // Figure out which method is active, if possible
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            } else if (path.getLeaf() instanceof NewClassTree) {
                // Find all overloads of method
                LOG.info(String.format("...`%s` is a constructor invocation", path.getLeaf()));
                var invoke = (NewClassTree) path.getLeaf();
                var method = trees.getElement(path);
                var results = new ArrayList<ExecutableElement>();
                for (var m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.CONSTRUCTOR) {
                        results.add((ExecutableElement) m);
                    }
                }
                // Figure out which parameter is active
                var activeParameter = invoke.getArguments().indexOf(this.path.getLeaf());
                LOG.info(String.format("...active parameter `%s` is %d", this.path.getLeaf(), activeParameter));
                // Figure out which method is active, if possible
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            }
        }
        return Optional.empty();
    }

    /** Find the smallest tree that includes the cursor */
    static TreePath findPath(JavacTask task, CompilationUnitTree root, int line, int character) {
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var cursor = root.getLineMap().getPosition(line, character);

        // Search for the smallest element that encompasses line:column
        class FindSmallest extends TreePathScanner<Void, Void> {
            TreePath found = null;

            boolean containsCursor(Tree tree) {
                long start = pos.getStartPosition(root, tree), end = pos.getEndPosition(root, tree);
                // If element has no position, give up
                if (start == -1 || end == -1) return false;
                // int x = 1, y = 2, ... requires special handling
                if (tree instanceof VariableTree) {
                    var v = (VariableTree) tree;
                    // Get contents of source
                    String source;
                    try {
                        source = root.getSourceFile().getCharContent(true).toString();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    // Find name in contents
                    var name = v.getName().toString();
                    start = source.indexOf(name, (int) start);
                    if (start == -1) {
                        LOG.warning(String.format("Can't find name `%s` in variable declaration `%s`", name, v));
                        return false;
                    }
                    end = start + name.length();
                }
                // Check if `tree` contains line:column
                return start <= cursor && cursor <= end;
            }

            @Override
            public Void scan(Tree tree, Void nothing) {
                // This is pre-order traversal, so the deepest element will be the last one remaining in `found`
                if (containsCursor(tree)) {
                    found = new TreePath(getCurrentPath(), tree);
                }
                super.scan(tree, nothing);
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                for (var t : node.getErrorTrees()) {
                    scan(t, nothing);
                }
                return null;
            }
        }
        var find = new FindSmallest();
        find.scan(root, null);
        if (find.found == null) {
            var uri = root.getSourceFile().toUri();
            var message = String.format("No TreePath to %s %d:%d", uri, line, character);
            throw new RuntimeException(message);
        }
        return find.found;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
