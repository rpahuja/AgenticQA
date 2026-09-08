# AgenticQA — RAG starter repository

This repository contains the Java orchestrator that generates Cucumber test
cases for a feature request, and the VS Code extension that hosts it. The
RAG (retrieval-augmented generation) part is **yours to build** — the
TODO markers in the code mark exactly what is missing.

## Prerequisites

- JDK 17 or newer
- Maven 3.9+
- (optional) VS Code for the `@agenticQA` chat participant

## Build

```bash
mvn package
```

The build is green with the stubs in place.

## Run the orchestrator directly

```bash
java -jar target/agenticqa-orchestrator.jar \
  --repo "<path-to-a-maven-cucumber-repo>" \
  --prompt "<feature request, e.g. Add a new option to the menu. Write the test cases.>"
```

Useful flags: `--dry-run` (scan + retrieval, no AI call, no tokens),
`--rag off` (skip the RAG context builder).

## Where the RAG lives

| Package | What you build here |
|---|---|
| `rag/` | `Chunker` (TODO Day 1) and `Retriever` (TODO Days 2–5) |
| `orchestrator/SurfaceExtractor.java` | the repository "fact sheet" (TODO Day 6) |
| `orchestrator/Generator.java` | the model prompt + file writing (improve on Day 7) |

Everything else (`core/`, `orchestrator/` plumbing, the extension) is shared
infrastructure — the method signatures above are the contract the pipeline
calls into. Keep them unchanged.

## Configuration

All Java-side configuration lives in `config/agenticqa.properties` next to
the jar (not committed — create it locally). Every key has a safe default,
so the build and `--dry-run` work without the file. Real AI runs need a
provider API key in that file.
