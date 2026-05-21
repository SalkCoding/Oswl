package com.salkcoding.oswl.domain.enums;

/** Supported AI providers */
public enum AiProvider {
    OPENAI,     // GPT
    GEMINI,     // Gemini
    ANTHROPIC,  // Claude
    COPILOT,    // GitHub Copilot (OpenAI-compatible, GitHub token auth)
    LOCAL       // Ollama and similar local LLMs (OpenAI-compatible endpoint)
}
