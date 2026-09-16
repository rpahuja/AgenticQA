package com.agenticqa.orchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Safety net for generated step definitions.
 *
 * <p>
 * Cucumber fails the whole suite with a
 * {@code DuplicateStepDefinitionException}
 * when two methods declare the same step expression. LLMs routinely re-declare
 * steps that already exist in the repository, even when those step definitions
 * are part of the retrieved context. This guard scans the target repository for
 * the step expressions that already exist and removes the duplicate methods
 * from
 * the generated class before it is written to disk.
 */
public final class StepDefinitionGuard {

    private StepDefinitionGuard() {
    }

    /** Cucumber step annotations, e.g. {@code @Given("the user logs in")}. */
    private static final Pattern ANNOTATION = Pattern.compile(
            "@(?:Given|When|Then|And|But)\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)");

    /** A method declaration, used to find the block that owns an annotation. */
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:public|protected|private|static|final|synchronized|\\s)*"
                    + "[\\w<>\\[\\],.? ]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws[\\w\\s,.]+)?\\{");

    /** A call on a fixture field, e.g. {@code fixture.enterPassword("x")}. */
    private static final Pattern FIXTURE_CALL = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(\\w+)\\s*\\(");

    /** Result of a de-duplication pass. */
    public static final class Result {
        public final String code;
        public final List<String> removedSteps;
        public final List<String> removedMethods;
        public final List<String> removedInvalidMethods;

        Result(String code, List<String> removedSteps, List<String> removedMethods,
                List<String> removedInvalidMethods) {
            this.code = code;
            this.removedSteps = removedSteps;
            this.removedMethods = removedMethods;
            this.removedInvalidMethods = removedInvalidMethods;
        }

        public boolean changed() {
            return !removedMethods.isEmpty() || !removedInvalidMethods.isEmpty();
        }
    }

    /**
     * Remove from {@code generatedSteps} every step method whose expression is
     * already declared somewhere under {@code layout.testSourceRoot}.
     *
     * @param generatedSteps the Java source of the generated step class
     * @param layout         the discovered repository layout
     * @param exclude        a file to ignore while scanning (the file being
     *                       written)
     */
    public static Result dedupe(String generatedSteps, RepoLayout layout, Path exclude)
            throws IOException {
        Set<String> existing = existingSteps(layout, exclude);
        Set<String> fixtureApi = fixtureMethods(layout, exclude);

        List<String> removedSteps = new ArrayList<>();
        List<String> removedMethods = new ArrayList<>();
        List<String> removedInvalid = new ArrayList<>();
        String code = generatedSteps;

        // Repeat until stable: removing one method can expose another duplicate.
        boolean progress = true;
        while (progress) {
            progress = false;
            Matcher m = METHOD.matcher(code);
            while (m.find()) {
                String methodName = m.group(1);
                int bodyStart = m.end() - 1; // position of '{'
                int bodyEnd = matchingBrace(code, bodyStart);
                if (bodyEnd < 0)
                    continue;

                // The step annotations live *above* the method signature, so read
                // them from the whole declaration (annotations + signature), not
                // from the body.
                int start = startOfDeclaration(code, m.start());
                String declaration = code.substring(start, bodyStart);
                List<String> steps = stepExpressions(declaration);
                if (steps.isEmpty())
                    continue;

                String body = code.substring(bodyStart, bodyEnd + 1);

                boolean allDuplicate = true;
                for (String s : steps) {
                    if (!existing.contains(normalize(s))) {
                        allDuplicate = false;
                        break;
                    }
                }

                // A method is also unsafe when it calls a fixture method that the
                // repository does not declare - the generated class would not compile.
                boolean callsUnknownFixture = !fixtureApi.isEmpty()
                        && callsUnknownFixtureMethod(body, fixtureApi);

                if (!allDuplicate && !callsUnknownFixture)
                    continue;

                // Also drop the annotations/Javadoc directly above the method.
                code = code.substring(0, start) + code.substring(bodyEnd + 1);
                if (allDuplicate) {
                    removedMethods.add(methodName);
                    removedSteps.addAll(steps);
                } else {
                    removedInvalid.add(methodName);
                }
                progress = true;
                break;
            }
        }

        return new Result(tidy(code), removedSteps, removedMethods, removedInvalid);
    }

    /**
     * True when {@code body} invokes a method on a fixture-like receiver that the
     * repository does not declare. Only receivers whose name looks like a fixture
     * (contains "fixture", "page", "login", "context", "world") are checked, so
     * ordinary JDK calls such as {@code value.toUpperCase()} are left alone.
     */
    private static boolean callsUnknownFixtureMethod(String body, Set<String> fixtureApi) {
        Matcher m = FIXTURE_CALL.matcher(body);
        while (m.find()) {
            String receiver = m.group(1);
            String method = m.group(2);
            if (!looksLikeFixture(receiver))
                continue;
            if (!fixtureApi.contains(method))
                return true;
        }
        return false;
    }

    private static boolean looksLikeFixture(String receiver) {
        String r = receiver.toLowerCase();
        return r.contains("fixture") || r.contains("page") || r.contains("login")
                || r.contains("context") || r.contains("world");
    }

    /**
     * Method names declared by fixture-like classes under the test source root.
     * Used to detect generated code that calls an API the repository does not have.
     */
    public static Set<String> fixtureMethods(RepoLayout layout, Path exclude) throws IOException {
        Set<String> methods = new LinkedHashSet<>();
        if (!Files.isDirectory(layout.testSourceRoot))
            return methods;

        try (Stream<Path> walk = Files.walk(layout.testSourceRoot)) {
            List<Path> files = new ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> exclude == null || !p.toAbsolutePath().normalize()
                            .equals(exclude.toAbsolutePath().normalize()))
                    .forEach(files::add);

            for (Path f : files) {
                String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                if (!looksLikeFixture(f.getFileName().toString()))
                    continue;
                Matcher m = METHOD.matcher(src);
                while (m.find()) {
                    methods.add(m.group(1));
                }
            }
        }
        return methods;
    }

    /** Every step expression already declared under the test source root. */
    public static Set<String> existingSteps(RepoLayout layout, Path exclude) throws IOException {
        Set<String> steps = new LinkedHashSet<>();
        if (!Files.isDirectory(layout.testSourceRoot))
            return steps;

        try (Stream<Path> walk = Files.walk(layout.testSourceRoot)) {
            List<Path> files = new ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> exclude == null || !p.toAbsolutePath().normalize()
                            .equals(exclude.toAbsolutePath().normalize()))
                    .forEach(files::add);

            for (Path f : files) {
                String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                for (String s : stepExpressions(src)) {
                    steps.add(normalize(s));
                }
            }
        }
        return steps;
    }

    /** Step expressions declared in a chunk of Java source. */
    public static List<String> stepExpressions(String javaSource) {
        List<String> out = new ArrayList<>();
        Matcher m = ANNOTATION.matcher(javaSource);
        while (m.find()) {
            out.add(unescape(m.group(1)));
        }
        return out;
    }

    /** Cucumber treats these as equivalent, so compare them that way. */
    private static String normalize(String expression) {
        return expression.trim().replaceAll("\\s+", " ");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Index of the '{' that closes the block opened at {@code openBrace}, or -1.
     */
    private static int matchingBrace(String code, int openBrace) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = openBrace; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n')
                    inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        return -1;
    }

    /**
     * Walk back over annotations, Javadoc and blank lines that belong to a method.
     */
    private static int startOfDeclaration(String code, int methodStart) {
        int start = methodStart;
        int lineStart = code.lastIndexOf('\n', methodStart - 1) + 1;

        while (lineStart > 0) {
            int prevEnd = lineStart - 1;
            int prevStart = code.lastIndexOf('\n', prevEnd - 1) + 1;
            String prev = code.substring(prevStart, prevEnd).trim();

            if (prev.isEmpty() || prev.startsWith("@") || prev.startsWith("*")
                    || prev.startsWith("/*") || prev.startsWith("//") || prev.endsWith("*/")) {
                start = prevStart;
                lineStart = prevStart;
            } else {
                break;
            }
        }
        return start;
    }

    /** Collapse the blank-line runs left behind by removed methods. */
    private static String tidy(String code) {
        String out = code.replaceAll("(?m)[ \\t]+$", "");
        out = out.replaceAll("\n{3,}", "\n\n");
        return out.trim() + "\n";
    }

    /** Human-readable summary of what was dropped, for the CLI log. */
    public static String describe(Result result) {
        if (!result.changed())
            return "";
        List<String> parts = new ArrayList<>();
        if (!result.removedMethods.isEmpty()) {
            parts.add("removed " + result.removedMethods.size() + " duplicate step method(s) "
                    + result.removedMethods + " covering " + result.removedSteps.size() + " step(s)");
        }
        if (!result.removedInvalidMethods.isEmpty()) {
            parts.add("removed " + result.removedInvalidMethods.size()
                    + " method(s) calling unknown fixture API " + result.removedInvalidMethods);
        }
        return String.join("; ", parts);
    }
}
