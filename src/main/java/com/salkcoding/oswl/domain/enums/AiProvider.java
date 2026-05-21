package com.salkcoding.oswl.domain.enums;

/** 지원하는 AI 프로바이더 */
public enum AiProvider {
    OPENAI,     // GPT
    GEMINI,     // Gemini
    ANTHROPIC,  // Claude
    COPILOT,    // GitHub Copilot (OpenAI 호환, GitHub 토큰 인증)
    LOCAL       // Ollama 등 로컈 LLM (OpenAI 호환 엔드포인트)
}
