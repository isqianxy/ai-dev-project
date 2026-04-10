package com.nexus.agent.kb;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Component
public class KbDocumentLoader {

    public String load(Path path) throws IOException {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".md") || filename.endsWith(".txt")) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("暂不支持的文档类型: " + filename + "，当前仅支持 .md/.txt");
    }
}
