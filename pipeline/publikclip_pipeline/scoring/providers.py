"""Provider-agnostic AI routing for the processing pipeline.

Provider profiles contain no API key. Credentials are resolved from environment
variables or the server-side secrets file only; this module never returns them
in a response or log message.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import httpx

from .. import config


class ProviderError(RuntimeError):
    pass


@dataclass(frozen=True)
class ProviderCapabilities:
    chat: bool = True
    structured_output: bool = True
    vision: bool = False
    json_mode: bool = True
    streaming: bool = False


@dataclass(frozen=True)
class ProviderProfile:
    id: str
    name: str
    type: str
    base_url: str = ""
    credential_ref: str = ""
    default_model: str = ""
    fallback_model: str = ""
    enabled: bool = True
    capabilities: ProviderCapabilities = field(default_factory=ProviderCapabilities)
    input_cost_per_million: float = 0.0
    output_cost_per_million: float = 0.0


@dataclass(frozen=True)
class AIResponse:
    text: str
    structured_data: dict[str, Any]
    model: str
    provider: str
    latency_ms: int
    finish_reason: str = ""
    usage: dict[str, Any] = field(default_factory=dict)


def _usage_counts(raw: dict[str, Any], prompt: str, output: str) -> dict[str, Any]:
    """Normalize provider-specific usage without claiming estimates are actual."""
    input_tokens = raw.get("input_tokens", raw.get("prompt_tokens", raw.get("promptTokenCount")))
    output_tokens = raw.get("output_tokens", raw.get("completion_tokens", raw.get("candidatesTokenCount")))
    total_tokens = raw.get("total_tokens", raw.get("totalTokenCount"))
    actual = all(isinstance(value, (int, float)) for value in (input_tokens, output_tokens))
    if actual and total_tokens is None:
        total_tokens = int(input_tokens) + int(output_tokens)
    if not actual:
        input_tokens = max(1, round(len(prompt) / 4))
        output_tokens = max(1, round(len(output) / 4))
        total_tokens = input_tokens + output_tokens
    return {
        "input_tokens": int(input_tokens),
        "output_tokens": int(output_tokens),
        "total_tokens": int(total_tokens),
        "usage_source": "actual" if actual else "estimated",
    }


def _record_usage(profile: ProviderProfile, model: str, latency_ms: int, raw_usage: dict[str, Any], prompt: str, output: str) -> dict[str, Any]:
    usage = _usage_counts(raw_usage, prompt, output)
    event = {
        "provider": profile.id,
        "provider_name": profile.name,
        "model": model,
        "latency_ms": max(0, int(latency_ms)),
        "status": "success",
        "input_tokens": usage["input_tokens"],
        "output_tokens": usage["output_tokens"],
        "total_tokens": usage["total_tokens"],
        "usage_source": usage["usage_source"],
        "input_cost_per_million": float(getattr(profile, "input_cost_per_million", 0.0)),
        "output_cost_per_million": float(getattr(profile, "output_cost_per_million", 0.0)),
        "timestamp": time.time(),
    }
    try:
        path = config.home_dir() / "ai_usage.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, ensure_ascii=False) + "\n")
    except OSError:
        # Usage telemetry must never break an otherwise successful AI request.
        pass
    return usage


class AIProvider(Protocol):
    profile: ProviderProfile

    def generate_structured(self, prompt: str, schema: dict[str, Any], images: list[bytes] | None = None) -> AIResponse:
        ...


def _strip_fences(text: str) -> str:
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else text[3:]
        if text.rstrip().endswith("```"):
            text = text.rstrip()[:-3]
    return text.strip()


def _parse_json(text: str) -> dict[str, Any]:
    try:
        data = json.loads(_strip_fences(text))
    except json.JSONDecodeError as error:
        raise ProviderError(f"Provider returned invalid JSON: {error}") from error
    if not isinstance(data, dict):
        raise ProviderError("Provider returned JSON that is not an object")
    return data


def _credential(profile: ProviderProfile) -> str:
    ref = profile.credential_ref.removeprefix("secret://").strip()
    candidates = []
    if ref:
        candidates.append(f"PUBLIKCLIP_SECRET_{ref.upper().replace('-', '_')}")
    candidates.append(f"PUBLIKCLIP_{profile.id.upper().replace('-', '_')}_API_KEY")
    if profile.id == "gemini":
        candidates.append("PUBLIKCLIP_GEMINI_API_KEY")
    for name in candidates:
        value = os.getenv(name, "").strip()
        if value:
            return value
    secrets_path = config.home_dir() / "secrets.json"
    if secrets_path.exists():
        try:
            payload = json.loads(secrets_path.read_text())
            for key in (ref, f"{profile.id}_api_key", "gemini_api_key" if profile.id == "gemini" else ""):
                if key and payload.get(key):
                    return str(payload[key]).strip()
        except (OSError, json.JSONDecodeError):
            pass
    return ""


def _headers(key: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {key}", "Content-Type": "application/json"} if key else {"Content-Type": "application/json"}


class _HttpProvider:
    def __init__(self, profile: ProviderProfile):
        self.profile = profile
        self.key = _credential(profile)
        self.model = profile.default_model
        if profile.type not in {"ollama", "local"} and not self.key:
            raise ProviderError(f"No server-side credential configured for provider '{profile.id}'")

    def _request(self, url: str, body: dict[str, Any], headers: dict[str, str] | None = None) -> tuple[dict[str, Any], int]:
        started = time.monotonic()
        try:
            response = httpx.post(url, json=body, headers=headers or {}, timeout=config.HTTP_TIMEOUT)
            response.raise_for_status()
            return response.json(), int((time.monotonic() - started) * 1000)
        except (httpx.HTTPError, ValueError) as error:
            raise ProviderError(f"Provider '{self.profile.id}' request failed: {error}") from error


class GeminiProvider(_HttpProvider):
    def generate_structured(self, prompt: str, schema: dict[str, Any], images: list[bytes] | None = None) -> AIResponse:
        parts: list[dict[str, Any]] = [{"text": prompt}]
        for image in images or []:
            parts.append({"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(image).decode()}})
        body = {"contents": [{"parts": parts}], "generationConfig": {"responseMimeType": "application/json", "responseSchema": schema, "temperature": 0.2}}
        payload, latency = self._request(
            f"https://generativelanguage.googleapis.com/v1beta/models/{self.model or 'gemini-flash-latest'}:generateContent?key={self.key}",
            body,
        )
        text = payload["candidates"][0]["content"]["parts"][0]["text"]
        usage = _record_usage(self.profile, self.model, latency, payload.get("usageMetadata", {}), prompt, text)
        return AIResponse(_strip_fences(text), _parse_json(text), self.model, self.profile.id, latency, usage=usage)


class OpenAICompatibleProvider(_HttpProvider):
    def generate_structured(self, prompt: str, schema: dict[str, Any], images: list[bytes] | None = None) -> AIResponse:
        content: Any = prompt
        if images:
            content = [{"type": "text", "text": prompt}]
            content.extend({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64.b64encode(image).decode()}"}} for image in images)
        body = {
            "model": self.model,
            "messages": [{"role": "user", "content": content}],
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
        }
        payload, latency = self._request(f"{self.profile.base_url.rstrip('/')}/chat/completions", body, _headers(self.key))
        choice = payload.get("choices", [{}])[0]
        text = choice.get("message", {}).get("content", "")
        usage = _record_usage(self.profile, self.model, latency, payload.get("usage", {}), prompt, text)
        return AIResponse(text, _parse_json(text), self.model, self.profile.id, latency, choice.get("finish_reason", ""), usage)


class AnthropicProvider(_HttpProvider):
    def generate_structured(self, prompt: str, schema: dict[str, Any], images: list[bytes] | None = None) -> AIResponse:
        content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        for image in images or []:
            content.append({"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.b64encode(image).decode()}})
        body = {"model": self.model, "max_tokens": 4096, "messages": [{"role": "user", "content": content}], "system": f"Return only JSON matching this schema: {json.dumps(schema)}"}
        headers = {"x-api-key": self.key, "anthropic-version": "2023-06-01", "content-type": "application/json"}
        payload, latency = self._request(f"{self.profile.base_url.rstrip('/') or 'https://api.anthropic.com'}/v1/messages", body, headers)
        text = payload.get("content", [{}])[0].get("text", "")
        usage = _record_usage(self.profile, self.model, latency, payload.get("usage", {}), prompt, text)
        return AIResponse(text, _parse_json(text), self.model, self.profile.id, latency, payload.get("stop_reason", ""), usage)


class OllamaProvider(OpenAICompatibleProvider):
    pass


def _provider(profile: ProviderProfile) -> AIProvider:
    if profile.type == "gemini":
        return GeminiProvider(profile)
    if profile.type == "anthropic":
        return AnthropicProvider(profile)
    if profile.type in {"ollama", "local", "openai", "openai_compatible", "openrouter", "together", "groq", "fireworks"}:
        if profile.type == "ollama" and not profile.base_url:
            profile = ProviderProfile(**{**profile.__dict__, "base_url": "http://localhost:11434/api"})
        return OllamaProvider(profile) if profile.type == "ollama" else OpenAICompatibleProvider(profile)
    raise ProviderError(f"Unsupported provider type: {profile.type}")


class ProviderRouter:
    def __init__(self, profiles: list[ProviderProfile]):
        self.profiles = {profile.id: profile for profile in profiles if profile.enabled}

    @classmethod
    def from_disk(cls) -> "ProviderRouter":
        path = config.home_dir() / "providers.json"
        if not path.exists():
            return cls([])
        try:
            raw = json.loads(path.read_text())
            profiles = []
            for item in raw if isinstance(raw, list) else raw.get("providers", []):
                caps = ProviderCapabilities(**item.get("capabilities", {}))
                profiles.append(ProviderProfile(capabilities=caps, **{key: value for key, value in item.items() if key != "capabilities"}))
            return cls(profiles)
        except (OSError, json.JSONDecodeError, TypeError) as error:
            raise ProviderError(f"Invalid providers.json: {error}") from error

    def generate_json(self, task: str, prompt: str, schema: dict[str, Any], images: list[bytes] | None = None) -> dict[str, Any]:
        candidates = list(self.profiles.values())
        if task == "vision_analysis":
            candidates = [p for p in candidates if p.capabilities.vision]
        candidates = [p for p in candidates if p.capabilities.chat and p.capabilities.structured_output]
        if not candidates:
            raise ProviderError(f"No enabled provider supports task '{task}'")
        last: Exception | None = None
        for profile in candidates:
            for model in (profile.default_model, profile.fallback_model):
                if not model:
                    continue
                try:
                    active = ProviderProfile(**{**profile.__dict__, "default_model": model})
                    return _provider(active).generate_structured(prompt, schema, images).structured_data
                except Exception as error:  # noqa: BLE001 — try configured fallback
                    last = error
        raise ProviderError(f"All providers failed for task '{task}': {last}")

    def health(self) -> list[dict[str, Any]]:
        return [{"id": p.id, "name": p.name, "type": p.type, "enabled": p.enabled, "capabilities": p.capabilities.__dict__} for p in self.profiles.values()]
