package com.nexus.agent.kb;

import com.nexus.agent.kb.model.KbChunk;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class KbChunker {

    public List<KbChunk> split(String kbId, String documentId, String sourceUri, String content, int chunkSize, int chunkOverlap) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int safeChunkSize = Math.max(100, chunkSize);
        int safeOverlap = Math.max(0, Math.min(safeChunkSize - 1, chunkOverlap));
        int step = Math.max(1, safeChunkSize - safeOverlap);
        List<KbChunk> chunks = new ArrayList<>();
        int index = 0;
        for (int start = 0; start < content.length(); start += step) {
            int end = Math.min(content.length(), start + safeChunkSize);
            String piece = content.substring(start, end).trim();
            if (!piece.isBlank()) {
                String chunkId = digestHex(documentId + "#" + index + "#" + piece);
                chunks.add(new KbChunk(chunkId, kbId, documentId, sourceUri, index, piece));
                index++;
            }
            if (end >= content.length()) {
                break;
            }
        }
        return chunks;
    }

    private static String digestHex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 chunkId 失败", e);
        }
    }
}
