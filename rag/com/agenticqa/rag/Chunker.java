package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.List;

/**
 * Cuts repository files into retrieval-friendly pieces.
 *
 * TODO Day 1 - implement. One piece per test scenario, one per Java method.
 * Every piece must carry its source path and a title (e.g.
 * "Feature name / Scenario name" or "Class.method"). Must work on ANY
 * Cucumber/Maven repository, including files with no scenarios or methods.
 *
 * Signature contract - the orchestrator calls exactly this; keep it.
 */
public final class Chunker {

    private Chunker() {
    }

    public static List<SourceFile> chunk(List<SourceFile> files) {
        throw new UnsupportedOperationException("TODO Day 1 - Chunker not implemented yet");
    }
}
