package com.nexus.agent.kb;

import com.nexus.agent.kb.model.KbChunk;
import com.nexus.agent.kb.model.KbTriple;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GraphTripleExtractor {

    /**
     * MVP 先使用模板化规则抽取三元组。
     * 后续可升级为 LLM 抽取，但仍建议做 schema 约束。
     */
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(.{1,30}?)(依赖于|依赖)(.{1,30})"), "DEPENDS_ON"),
            new Rule(Pattern.compile("(.{1,30}?)(属于|归属于)(.{1,30})"), "BELONGS_TO"),
            new Rule(Pattern.compile("(.{1,30}?)(位于|在)(.{1,30})"), "LOCATED_IN"),
            new Rule(Pattern.compile("(.{1,30}?)(包含|包括)(.{1,30})"), "CONTAINS"),
            new Rule(Pattern.compile("(.{1,30}?)(是)(.{1,30})"), "IS_A")
    );

    private final KbBuildProperties properties;

    public GraphTripleExtractor(KbBuildProperties properties) {
        this.properties = properties;
    }

    public List<KbTriple> extract(List<KbChunk> chunks) {
        List<KbTriple> out = new ArrayList<>();
        int capPerChunk = Math.max(1, properties.getGraph().getMaxTriplesPerChunk());
        for (KbChunk chunk : chunks) {
            int count = 0;
            for (String sentence : splitSentences(chunk.content())) {
                for (Rule rule : RULES) {
                    Matcher matcher = rule.pattern().matcher(sentence);
                    if (matcher.find()) {
                        String subject = normalizeNodeName(matcher.group(1));
                        String object = normalizeNodeName(matcher.group(3));
                        if (!subject.isBlank() && !object.isBlank() && !subject.equals(object)) {
                            out.add(new KbTriple(chunk.kbId(), chunk.chunkId(), subject, rule.relationType(), object));
                            count++;
                            if (count >= capPerChunk) {
                                break;
                            }
                        }
                    }
                }
                if (count >= capPerChunk) {
                    break;
                }
            }
        }
        return out;
    }

    private static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("[。！？!?.\\n\\r]+"));
    }

    private static String normalizeNodeName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim()
                .replaceAll("[\\s\\t]+", " ")
                .replaceAll("^[,，:：;；\\-\\s]+", "")
                .replaceAll("[,，:：;；\\-\\s]+$", "");
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized;
    }

    private record Rule(Pattern pattern, String relationType) {
    }
}
