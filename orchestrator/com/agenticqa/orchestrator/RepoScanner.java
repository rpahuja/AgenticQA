package com.agenticqa.orchestrator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Auto-discovers the layout of a Maven + Cucumber test repository.
 * The orchestrator learns where everything lives, so the USER never has to
 * tell the AI where to put the generated tests or which files to look at.
 */
public final class RepoScanner {

    private RepoScanner() {}

    public static RepoLayout scan(Path repo) throws IOException {
        Path pom = repo.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException(repo + " does not look like a Maven repository (pom.xml missing).");
        }
        String pomText = new String(Files.readAllBytes(pom));

        // The PROJECT's own groupId - the <parent> block (e.g. spring-boot)
        // must not be mistaken for it.
        String groupId = firstTag(pomText.replaceFirst("(?s)<parent>.*?</parent>", ""), "groupId");
        if (groupId.isEmpty()) groupId = "com.example";

        Path testSourceRoot = repo.resolve("src/test/java");
        Path featureDir = repo.resolve("src/test/resources/features");
        if (!Files.isDirectory(testSourceRoot)) {
            throw new IOException("Missing standard test source root: src/test/java");
        }

        // The packages come from what already exists in the repo -
        // fallbacks only apply when the folders are empty.
        String stepsPackage = detectPackage(testSourceRoot, "stepdefs", groupId + ".stepdefs");
        String runnerPackage = detectPackage(testSourceRoot, "runner", groupId + ".runner");

        return new RepoLayout(repo, pomText, groupId, testSourceRoot, featureDir, stepsPackage, runnerPackage);
    }

    private static String firstTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Package declared by the first .java file found under a known folder (e.g. stepdefs). */
    private static String detectPackage(Path testRoot, String folder, String fallback) {
        try (Stream<Path> walk = Files.walk(testRoot)) {
            Optional<Path> first = walk
                .filter(p -> p.toString().contains(File.separator + folder + File.separator))
                .filter(p -> p.toString().endsWith(".java"))
                .findFirst();
            if (first.isPresent()) {
                String pkg = packageOf(first.get());
                if (!pkg.isEmpty()) return pkg;
            }
        } catch (IOException ignored) {
            /* fall through to the fallback package */
        }
        return fallback;
    }

    /** Extract the package declared in a .java file. */
    private static String packageOf(Path javaFile) throws IOException {
        for (String line : new String(Files.readAllBytes(javaFile)).split("\n")) {
            String t = line.trim();
            if (t.startsWith("package ") && t.endsWith(";")) {
                return t.substring("package ".length(), t.length() - 1).trim();
            }
        }
        return "";
    }
}
