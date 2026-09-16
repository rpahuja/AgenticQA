package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;


final class JavaChunker {

    private JavaChunker() {
    }

    static Result chunkDirectory(Path sourceRoot) throws IOException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return new Result(0, Collections.emptyList());
        }

        List<Path> files = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(JavaChunker::isJavaFile)
                .sorted()
                .forEach(files::add);
        }

        List<SourceFile> chunks = new ArrayList<>();

        for (Path file : files) {
            chunks.addAll(chunkFile(file));
        }

        return new Result(files.size(), chunks);
    }

    static List<SourceFile> chunkFile(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);

        if (source.isBlank()) {
            return Collections.emptyList();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null) {
            throw new IOException(
                "No Java compiler is available. "
                + "Run AgenticQA with a JDK, not a JRE."
            );
        }

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(
                         null,
                         null,
                         StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> javaFiles =
                fileManager.getJavaFileObjects(file.toFile());

            JavacTask task = (JavacTask) compiler.getTask(
                null,
                fileManager,
                null,
                List.of("-proc:none"),
                null,
                javaFiles
            );

            Iterable<? extends CompilationUnitTree> parsed =
                task.parse();

            Trees trees = Trees.instance(task);

            List<SourceFile> result = new ArrayList<>();

            for (CompilationUnitTree unit : parsed) {
                MethodCollector collector =
                    new MethodCollector(
                        file.toString(),
                        source,
                        unit,
                        trees,
                        result
                    );

                collector.scan(unit, null);
            }

            return result;

        } catch (RuntimeException e) {
            throw new IOException(
                "Failed to parse Java source: " + file,
                e
            );
        }
    }

    private static boolean isJavaFile(Path path) {
        return path.getFileName()
            .toString()
            .toLowerCase(Locale.ROOT)
            .endsWith(".java");
    }

    private static final class MethodCollector
            extends TreePathScanner<Void, Void> {

        private final String path;
        private final String source;
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final List<SourceFile> result;

        private final Deque<String> classNames =
            new ArrayDeque<>();

        MethodCollector(
                String path,
                String source,
                CompilationUnitTree unit,
                Trees trees,
                List<SourceFile> result) {

            this.path = path;
            this.source = source;
            this.unit = unit;
            this.trees = trees;
            this.result = result;
        }

        @Override
        public Void visitClass(
                ClassTree node,
                Void unused) {

            String name = node.getSimpleName().toString();

            if (!name.isBlank()) {
                classNames.addLast(name);
            }

            try {
                return super.visitClass(node, unused);
            } finally {
                if (!name.isBlank()) {
                    classNames.removeLast();
                }
            }
        }

        @Override
        public Void visitMethod(
                MethodTree node,
                Void unused) {

          
            if (node.getBody() == null) {
                return super.visitMethod(node, unused);
            }

            long start = trees.getSourcePositions()
                .getStartPosition(unit, node);

            long end = trees.getSourcePositions()
                .getEndPosition(unit, node);

            if (start < 0
                    || end < 0
                    || end <= start
                    || end > source.length()) {

                return super.visitMethod(node, unused);
            }

            String methodContent = source
                .substring((int) start, (int) end)
                .trim();

            if (!methodContent.isBlank()) {
                String className = classNames.isEmpty()
                    ? ""
                    : String.join(".", classNames);

                String methodName = node.getName().toString();

                String title;

                if ("<init>".equals(methodName)) {
                    title = className.isBlank()
                        ? "<constructor>"
                        : className + ".<constructor>";
                } else {
                    title = className.isBlank()
                        ? methodName
                        : className + "." + methodName;
                }

                result.add(new SourceFile(
                    path,
                    title,
                    methodContent
                ));
            }

            return super.visitMethod(node, unused);
        }
    }

    static final class Result {
        final int fileCount;
        final List<SourceFile> chunks;

        Result(int fileCount, List<SourceFile> chunks) {
            this.fileCount = fileCount;
            this.chunks = chunks;
        }
    }
}