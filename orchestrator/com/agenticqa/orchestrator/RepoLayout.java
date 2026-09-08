package com.agenticqa.orchestrator;

import java.nio.file.Path;

/** Everything the scanner discovered about the target repository. */
public final class RepoLayout {

    public final Path repo;
    public final String pomText;
    public final String groupId;
    public final Path testSourceRoot;
    public final Path featureDir;
    public final String stepsPackage;
    public final String runnerPackage;

    public RepoLayout(Path repo, String pomText, String groupId, Path testSourceRoot,
                      Path featureDir, String stepsPackage, String runnerPackage) {
        this.repo = repo;
        this.pomText = pomText;
        this.groupId = groupId;
        this.testSourceRoot = testSourceRoot;
        this.featureDir = featureDir;
        this.stepsPackage = stepsPackage;
        this.runnerPackage = runnerPackage;
    }
}
