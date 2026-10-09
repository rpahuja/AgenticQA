package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StepSignatureExtractor {

    private StepSignatureExtractor() {
    }

    private static final Pattern STEP_ANNOTATION = Pattern.compile(
            "@(?:Given|When|Then|And|But)\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)"
    );

    public static List<String> extract(String javaSource) {
        if (javaSource == null || javaSource.isBlank()) {
            return Collections.emptyList();
        }

        List<String> signatures = new ArrayList<>();

        Matcher matcher = STEP_ANNOTATION.matcher(javaSource);

        while (matcher.find()) {
            String signature = matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim();

            if (!signature.isEmpty() && !signatures.contains(signature)) {
                signatures.add(signature);
            }
        }

        return Collections.unmodifiableList(signatures);
    }

    public static List<String> extract(SourceFile chunk) {
        if (chunk == null) {
            return Collections.emptyList();
        }

        return extract(chunk.content);
    }
}