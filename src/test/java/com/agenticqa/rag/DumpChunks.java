package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Temporary helper: dump every chunk with its ID, path and title. */
public final class DumpChunks {

    public static void main(String[] args) throws Exception {
        Path featureDir = Paths.get(
                "C:\\Users\\Sandeep\\Downloads\\rag_sample_qa\\src\\test\\resources\\features");
        Path testSourceRoot = Paths.get(
                "C:/Users/Sandeep/Downloads/rag_sample_qa");

        List<SourceFile> features = FeatureChunker.chunkDirectory(featureDir).chunks;
        List<SourceFile> java = JavaChunker.chunkDirectory(testSourceRoot).chunks;

        System.out.println("===== FEATURE CHUNKS =====");
        for (SourceFile c : features) {
            System.out.println(RetrievalMetrics.chunkId(c) + " | " + c.title);
            System.out.println("    path=" + c.path);
        }

        System.out.println("===== JAVA CHUNKS =====");
        for (SourceFile c : java) {
            System.out.println(RetrievalMetrics.chunkId(c) + " | " + c.title);
            System.out.println("    path=" + c.path);
        }
    }
}
