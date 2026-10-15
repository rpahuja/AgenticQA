
package com.agenticqa.rag;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.agenticqa.core.model.SourceFile;

public class GoldenDatasetBuilder {

    public static void main(String[] args) throws Exception {
        Path featureDir = Paths.get("C:\\Users\\Sandeep\\Downloads\\rag_sample_qa\\src\\test\\resources\\features");
        Path testSourceRoot = Paths.get("C:/Users/Sandeep/Downloads/rag_sample_qa");

        RagEngine.RagContext context = RagEngine.buildContext(
                featureDir,
                testSourceRoot,
                // "catalog all repository scenarios and methods",
                // Integer.MAX_VALUE,
                // Integer.MAX_VALUE);
                "Find the Java method that enters a username",
        5,
        1000,
        true);

        System.out.println("Total chunks: " + context.normalizedTexts.size());

        for (SourceFile chunk : context.normalizedTexts.keySet()) {
            System.out.println("--------------------------------");
            System.out.println("ID: " + RetrievalMetrics.chunkId(chunk));
            System.out.println("Path: " + chunk.path);
            System.out.println("Title: " + chunk.title);
            System.out.println("Content: " + chunk.content);
        }
    }
}
