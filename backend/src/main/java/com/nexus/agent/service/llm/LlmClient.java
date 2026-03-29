package com.nexus.agent.service.llm;

public interface LlmClient {

    boolean supports(String provider);

    LlmReply generate(String prompt);
}
