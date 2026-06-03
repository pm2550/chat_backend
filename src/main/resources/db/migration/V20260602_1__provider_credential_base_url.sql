-- BYO provider keys: per-credential endpoint + default model override.
-- base_url lets a user point an OpenAI-compatible key at any gateway
-- (OpenRouter / Together / the self-hosted dashscope-proxy / Ollama).
ALTER TABLE `provider_credentials`
  ADD COLUMN `base_url` varchar(500) DEFAULT NULL AFTER `memo`,
  ADD COLUMN `model_override` varchar(120) DEFAULT NULL AFTER `base_url`;
