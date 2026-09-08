package com.agenticqa.core.model;

/** One document or chunk used as RAG context (a file, or a chunk of one). */
public final class SourceFile {

    public final String path;
    public final String title;    // e.g. "Feature name / Scenario name" or "Class.method"
    public final String content;

    public SourceFile(String path, String content) {
        this(path, "", content);
    }

    public SourceFile(String path, String title, String content) {
        this.path = path;
        this.title = title;
        this.content = content;
    }
}
