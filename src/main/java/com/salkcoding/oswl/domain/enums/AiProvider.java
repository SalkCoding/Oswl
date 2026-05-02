package com.salkcoding.oswl.domain.enums;

/** 지원하는 AI 제공자 */
public enum AiProvider {
    OPENAI,     // GPT
    GEMINI,     // Gemini
    ANTHROPIC,  // Claude
    LOCAL       // Ollama 등 로컬 LLM (OpenAI 호환 엔드포인트)
}
