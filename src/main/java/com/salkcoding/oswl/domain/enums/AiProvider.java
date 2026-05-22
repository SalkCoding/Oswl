package com.salkcoding.oswl.domain.enums;

/** Supported AI providers */
public enum AiProvider {
    OPENAI,     // GPT
    GEMINI,     // Gemini
    ANTHROPIC,  // Claude
    COPILOT,    // GitHub Copilot (OpenAI-compatible, authenticated with GitHub token)
    LOCAL       // Local LLM such as Ollama (OpenAI-compatible endpoint)
}
