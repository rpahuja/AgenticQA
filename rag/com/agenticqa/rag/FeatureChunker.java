package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class FeatureChunker {

    private FeatureChunker() {
    }

    static Result chunkDirectory(Path featureDir) throws IOException {
        if (featureDir == null || !Files.isDirectory(featureDir)) {
            return new Result(0, Collections.emptyList());
        }

        List<Path> files = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(featureDir)) {
            walk.filter(Files::isRegularFile)
                .filter(FeatureChunker::isFeatureFile)
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
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return chunkText(file.toString(), text);
    }

    static List<SourceFile> chunkText(String path, String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        text = normalizeLineEndings(text);

        String[] lines = text.split("\n", -1);

        String featureName = "";
        int featureStart = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (startsWithKeyword(trimmed, "Feature:")) {
                featureStart = i;
                featureName = valueAfterColon(trimmed);
                break;
            }
        }

        if (featureStart < 0) {
            return Collections.emptyList();
        }

        int firstScenario = -1;

        for (int i = featureStart + 1; i < lines.length; i++) {
            if (isScenarioStart(lines[i].trim())) {
                firstScenario = i;
                break;
            }
        }

        if (firstScenario < 0) {
            return Collections.emptyList();
        }

        String sharedContext = join(lines, featureStart, firstScenario).trim();

        List<SourceFile> result = new ArrayList<>();

        int scenarioStart = firstScenario;

        while (scenarioStart >= 0 && scenarioStart < lines.length) {

            int nextScenario = findNextScenario(lines, scenarioStart + 1);

            int chunkEnd = nextScenario >= 0
                ? nextScenario
                : lines.length;

            if (nextScenario >= 0) {
                int boundary = moveTrailingScenarioMetadata(
                    lines,
                    scenarioStart,
                    nextScenario
                );

                if (boundary > scenarioStart) {
                    chunkEnd = boundary;
                }
            }

            String scenarioBlock = join(lines, scenarioStart, chunkEnd).trim();

            if (!scenarioBlock.isBlank()) {
                String scenarioName = scenarioName(lines[scenarioStart]);

                StringBuilder content = new StringBuilder();

                if (!sharedContext.isBlank()) {
                    content.append(sharedContext).append("\n\n");
                }

                content.append(scenarioBlock);

                String title = featureName.isBlank()
                    ? scenarioName
                    : featureName + " / " + scenarioName;

                result.add(new SourceFile(
                    path,
                    title,
                    content.toString().trim()
                ));
            }

            scenarioStart = nextScenario;
        }

        return result;
    }

    private static int moveTrailingScenarioMetadata(
            String[] lines,
            int currentScenarioStart,
            int nextScenarioStart) {

        int boundary = nextScenarioStart;

        while (boundary > currentScenarioStart + 1) {
            String previous = lines[boundary - 1].trim();

            if (previous.isEmpty()
                    || previous.startsWith("#")
                    || previous.startsWith("@")) {
                boundary--;
            } else {
                break;
            }
        }

        return boundary;
    }

    private static int findNextScenario(
            String[] lines,
            int from) {

        for (int i = from; i < lines.length; i++) {
            if (isScenarioStart(lines[i].trim())) {
                return i;
            }
        }

        return -1;
    }

    private static boolean isScenarioStart(String line) {
        String lower = line.toLowerCase(Locale.ROOT);

        return lower.startsWith("scenario:")
            || lower.startsWith("scenario outline:")
            || lower.startsWith("scenario template:");
    }

    private static String scenarioName(String line) {
        return valueAfterColon(line);
    }

    private static boolean startsWithKeyword(
            String line,
            String keyword) {

        return line.regionMatches(
            true,
            0,
            keyword,
            0,
            keyword.length()
        );
    }

    private static String valueAfterColon(String line) {
        int colon = line.indexOf(':');

        if (colon < 0) {
            return line.trim();
        }

        return line.substring(colon + 1).trim();
    }

    private static String join(
            String[] lines,
            int fromInclusive,
            int toExclusive) {

        StringBuilder sb = new StringBuilder();

        for (int i = fromInclusive; i < toExclusive; i++) {
            sb.append(lines[i]);

            if (i < toExclusive - 1) {
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    private static String normalizeLineEndings(String text) {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n');
    }

    private static boolean isFeatureFile(Path path) {
        return path.getFileName()
            .toString()
            .toLowerCase(Locale.ROOT)
            .endsWith(".feature");
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