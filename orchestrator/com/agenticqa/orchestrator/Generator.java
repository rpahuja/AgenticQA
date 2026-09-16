package com.agenticqa.orchestrator;

import com.agenticqa.core.model.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the model prompt from the discovered layout + retrieved context,
 * and turns the model reply back into files at the right locations.
 */
public final class Generator {

    private Generator() {
    }

    public static String buildPrompt(String feature, RepoLayout layout, List<SourceFile> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior QA automation engineer working in a Java Maven Spring Cucumber repository.\n\n");
        sb.append("Repository layout (discovered automatically):\n");
        sb.append("- Feature files directory : ").append(relative(layout, layout.featureDir)).append("\n");
        sb.append("- Test source root        : ").append(relative(layout, layout.testSourceRoot)).append("\n");
        sb.append("- Step definitions package: ").append(layout.stepsPackage).append("\n");
        sb.append("- Runner package          : ").append(layout.runnerPackage).append("\n\n");
        sb.append("NEW FEATURE:\n").append(feature).append("\n\n");

        if (context != null && !context.isEmpty()) {
            sb.append("Reference - existing tests most relevant to this feature (retrieved from the repository):\n");
            for (SourceFile f : context) {
                sb.append("\n----- ").append(relative(layout, Path.of(f.path)))
                        .append(f.title == null || f.title.isBlank() ? "" : " [" + f.title + "]")
                        .append(" -----\n");
                sb.append(f.content).append("\n");
            }
        }

        sb.append("\nDeliver the generated tests in EXACTLY this format, nothing outside the markers:\n\n");
        sb.append("===FEATURE===\n");
        sb.append(
                "<complete Cucumber .feature file for the new functionality, following the style of the existing features>\n\n");
        sb.append("===STEPS===\n");
        sb.append("<complete Java step definitions class, package ").append(layout.stepsPackage)
                .append(", with imports>\n\n");
        sb.append("CRITICAL RULES for the step definitions class:\n");
        sb.append("- Do NOT redeclare any step that already exists in the repository. Cucumber aborts the\n");
        sb.append("  entire suite with a DuplicateStepDefinitionException when two methods declare the same\n");
        sb.append("  step expression, so a redeclared step is worse than a missing one.\n");
        sb.append("- Before writing a step, check the reference files above for an identical or equivalent\n");
        sb.append("  step expression. If one exists, reuse it and do NOT write a method for it.\n");
        sb.append("- Only declare methods for steps that are genuinely new. If every step in the feature\n");
        sb.append("  already exists, output the single word NONE for this section.\n\n");
        sb.append("===RUNNER===\n");
        sb.append("<Java Cucumber runner class, package ").append(layout.runnerPackage)
                .append("> - or the single word NONE when the existing runner already picks up the new feature file>\n");
        return sb.toString();
    }

    /**
     * Split the reply on the three markers and write each part to the right place.
     */
    public static List<Path> writeGenerated(String reply, String featurePrompt, RepoLayout layout) throws IOException {
        return writeGenerated(reply, featurePrompt, layout, null);
    }

    /**
     * Same as {@link #writeGenerated(String, String, RepoLayout)} but reports
     * what the duplicate-step guard removed, so the caller can log it.
     */
    public static List<Path> writeGenerated(String reply, String featurePrompt, RepoLayout layout,
            List<String> guardNotes) throws IOException {
        Map<String, String> parts = splitMarkers(reply);
        List<Path> written = new ArrayList<>();

        String feature = cleanFences(parts.get("FEATURE"));
        String steps = cleanFences(parts.get("STEPS"));
        String runner = cleanFences(parts.get("RUNNER"));

        String slug = slugify(featurePrompt);

        // Run the guard BEFORE writing anything. The guard can strip every method
        // from the generated step class, and when it does the feature file must not
        // be written either - otherwise the suite is left with scenarios that have
        // no glue code and fails with UndefinedStepException.
        String className = null;
        Path stepsPath = null;
        StepDefinitionGuard.Result guard = null;
        if (steps != null && !steps.isBlank()) {
            className = classNameOf(steps, slug + "Steps");
            if (!containsPackage(steps)) {
                steps = "package " + layout.stepsPackage + ";\n\n" + steps;
            }
            stepsPath = layout.testSourceRoot
                    .resolve(layout.stepsPackage.replace('.', '/'))
                    .resolve(className + ".java");

            // Cucumber aborts the whole suite on a duplicate step expression, so
            // drop any method that re-declares a step already in the repository.
            guard = StepDefinitionGuard.dedupe(steps, layout, stepsPath);
            if (guard.changed()) {
                steps = guard.code;
                if (guardNotes != null) {
                    guardNotes.add(StepDefinitionGuard.describe(guard));
                }
            }
        }

        // The guard removed methods because they called fixture APIs the repository
        // does not declare. Those steps cannot be implemented, so the feature that
        // depends on them is not viable - refuse to write it rather than emit a
        // feature file that is guaranteed to fail.
        if (guard != null && !guard.removedInvalidMethods.isEmpty()) {
            if (guardNotes != null) {
                guardNotes.add("refused to write " + slug + ".feature - it depends on "
                        + guard.removedInvalidMethods.size()
                        + " step(s) that call APIs the repository does not provide: "
                        + String.join(", ", guard.removedInvalidMethods));
            }
            return written;
        }

        // Nothing genuinely new left: the feature only reuses existing steps, so
        // write the feature but no step class.
        boolean stepsEmpty = steps == null || steps.isBlank()
                || StepDefinitionGuard.stepExpressions(steps).isEmpty();
        if (stepsEmpty && guard != null && guard.changed() && guardNotes != null) {
            guardNotes.add("skipped " + className
                    + ".java - every step it declared already exists in the repository");
        }

        if (feature != null && !feature.isBlank()) {
            Files.createDirectories(layout.featureDir);
            Path p = layout.featureDir.resolve(slug + ".feature");
            Files.write(p, feature.getBytes(StandardCharsets.UTF_8));
            written.add(p);
        }

        if (!stepsEmpty) {
            Files.createDirectories(stepsPath.getParent());
            Files.write(stepsPath, steps.getBytes(StandardCharsets.UTF_8));
            written.add(stepsPath);
        }

        if (runner != null && !runner.isBlank() && !runner.trim().equalsIgnoreCase("NONE")) {
            String runnerClassName = classNameOf(runner, slug + "Runner");
            if (!containsPackage(runner)) {
                runner = "package " + layout.runnerPackage + ";\n\n" + runner;
            }
            Path p = layout.testSourceRoot
                    .resolve(layout.runnerPackage.replace('.', '/'))
                    .resolve(runnerClassName + ".java");
            Files.createDirectories(p.getParent());
            Files.write(p, runner.getBytes(StandardCharsets.UTF_8));
            written.add(p);
        }
        return written;
    }

    private static Map<String, String> splitMarkers(String reply) {
        Map<String, String> parts = new LinkedHashMap<>();
        String[] markers = { "===FEATURE===", "===STEPS===", "===RUNNER===" };
        for (int i = 0; i < markers.length; i++) {
            int start = reply.indexOf(markers[i]);
            if (start < 0) {
                parts.put(markers[i].replaceAll("=", ""), "");
                continue;
            }
            int end = i + 1 < markers.length
                    ? reply.indexOf(markers[i + 1], start + markers[i].length())
                    : reply.length();
            if (end < 0)
                end = reply.length();
            parts.put(markers[i].replaceAll("=", ""), reply.substring(start + markers[i].length(), end).trim());
        }
        return parts;
    }

    private static String cleanFences(String s) {
        if (s == null)
            return "";
        s = s.trim();
        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            s = newline >= 0 ? s.substring(newline + 1) : "";
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.lastIndexOf("```")).trim();
        }
        return s.trim();
    }

    private static String classNameOf(String javaCode, String fallback) {
        Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)").matcher(javaCode);
        return m.find() ? m.group(1) : fallback;
    }

    private static boolean containsPackage(String javaCode) {
        for (String line : javaCode.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package "))
                return true;
        }
        return false;
    }

    /** A short, file-safe name derived from the first few words of the prompt. */
    private static String slugify(String text) {
        String[] words = text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(words.length, 6); i++) {
            if (words[i].isEmpty())
                continue;
            head.append(words[i]).append('-');
        }
        String slug = head.toString().replaceAll("-+$", "");
        return slug.isEmpty() ? "new-feature" : slug;
    }

    private static String relative(RepoLayout layout, Path p) {
        try {
            return layout.repo.relativize(p).toString();
        } catch (Exception e) {
            return p.toString();
        }
    }
}
