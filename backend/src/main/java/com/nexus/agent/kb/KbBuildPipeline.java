package com.nexus.agent.kb;

import com.nexus.agent.kb.model.KbBuildResult;
import com.nexus.agent.kb.model.KbChunk;
import com.nexus.agent.kb.model.KbTriple;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class KbBuildPipeline {

    private final KbBuildProperties properties;
    private final KbDocumentLoader documentLoader;
    private final KbChunker chunker;
    private final KbEmbeddingService embeddingService;
    private final ChromaVectorStoreClient vectorStoreClient;
    private final GraphTripleExtractor graphTripleExtractor;
    private final Neo4jGraphStoreClient graphStoreClient;

    public KbBuildPipeline(
            KbBuildProperties properties,
            KbDocumentLoader documentLoader,
            KbChunker chunker,
            KbEmbeddingService embeddingService,
            ChromaVectorStoreClient vectorStoreClient,
            GraphTripleExtractor graphTripleExtractor,
            Neo4jGraphStoreClient graphStoreClient
    ) {
        this.properties = properties;
        this.documentLoader = documentLoader;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.vectorStoreClient = vectorStoreClient;
        this.graphTripleExtractor = graphTripleExtractor;
        this.graphStoreClient = graphStoreClient;
    }

    public KbBuildResult run() {
        List<Path> files = resolveInputFiles(properties.getInput());
        if (files.isEmpty()) {
            throw new IllegalStateException("未找到可构建文件，请检查 kb.build.input");
        }
        List<KbChunk> allChunks = new ArrayList<>();
        for (Path file : files) {
            String text = loadDocument(file);
            String documentId = file.getFileName().toString();
            List<KbChunk> chunks = chunker.split(
                    properties.getKbId(),
                    documentId,
                    file.toAbsolutePath().toString(),
                    text,
                    properties.getChunkSize(),
                    properties.getChunkOverlap()
            );
            allChunks.addAll(chunks);
        }
        List<float[]> vectors = embeddingService.embed(allChunks);
        int vectorCount = vectorStoreClient.upsert(allChunks, vectors);
        List<KbTriple> triples = graphTripleExtractor.extract(allChunks);
        int tripleCount = graphStoreClient.upsert(triples);
        return new KbBuildResult(files.size(), allChunks.size(), vectorCount, tripleCount);
    }

    private String loadDocument(Path file) {
        try {
            return documentLoader.load(file);
        } catch (Exception e) {
            throw new IllegalStateException("读取文档失败: " + file, e);
        }
    }

    private static List<Path> resolveInputFiles(String inputPattern) {
        if (inputPattern == null || inputPattern.isBlank()) {
            return List.of();
        }
        String normalized = inputPattern.replace("\\", "/");
        int wildcardIdx = normalized.indexOf('*');
        String root = normalized;
        String fileGlob = "**";
        if (wildcardIdx >= 0) {
            int slash = normalized.lastIndexOf('/', wildcardIdx);
            root = slash > 0 ? normalized.substring(0, slash) : ".";
            fileGlob = slash > 0 ? normalized.substring(slash + 1) : normalized;
        }

        Path rootPath = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.exists(rootPath) && !Paths.get(root).isAbsolute()) {
            Path alt = Paths.get("..").resolve(root).toAbsolutePath().normalize();
            if (Files.exists(alt)) {
                rootPath = alt;
            }
        }
        if (!Files.exists(rootPath)) {
            return List.of();
        }

        final Path scanRoot = rootPath;
        java.nio.file.PathMatcher matcher = scanRoot.getFileSystem().getPathMatcher("glob:" + fileGlob);
        try (java.util.stream.Stream<Path> stream = Files.walk(rootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(scanRoot.relativize(path)))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("解析输入文件失败: " + inputPattern, e);
        }
    }
}
