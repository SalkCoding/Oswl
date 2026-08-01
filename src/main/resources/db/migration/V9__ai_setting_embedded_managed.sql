-- Distinguishes the built-in llama.cpp sidecar from a user-configured Ollama endpoint.
-- Both are stored as the LOCAL provider, so without this flag the settings page cannot tell
-- which entry in the provider list is actually serving requests.
--
-- Null on every pre-existing row (idempotent, matching V2-V8) and read null-safely by AiSetting
-- as false, i.e. "user-configured endpoint" — the pre-existing behaviour.

ALTER TABLE ai_settings ADD COLUMN IF NOT EXISTS embedded_managed BOOLEAN;
