package com.salkcoding.oswl.domain.enums;

/** Supported AI providers */
public enum AiProvider {
    OPENAI,     // GPT
    GEMINI,     // Gemini
    ANTHROPIC,  // Claude
    LOCAL       // Local LLM such as Ollama (OpenAI-compatible endpoint)
}
