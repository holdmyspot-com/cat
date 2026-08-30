package io.github.cowwoc.cat.codex.runner;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that CAT's configured Codex hooks are trusted and that every newly started subagent receives its start hook.
 */
final class CodexHookDelivery
{
	private static final String SESSION_START = "cat@cat:hooks/hooks.json:session_start:0:0";
	private static final String SUBAGENT_START = "cat@cat:hooks/hooks.json:subagent_start:0:0";

	/**
	 * Verifies that Codex has accepted CAT's context hooks before a CAT-enabled run starts.
	 *
	 * @param response the native {@code hooks/list} response
	 * @throws IOException if either CAT hook is unavailable or not trusted
	 */
	static void verifyConfiguredHooks(JsonNode response) throws IOException
	{
		assert response != null;
		JsonNode hooks = response.path("result").path("data").path(0).path("hooks");
		verifyConfiguredHook(hooks, SESSION_START);
		verifyConfiguredHook(hooks, SUBAGENT_START);
	}

	/**
	 * Verifies that each subagent activity in one completed turn has a matching native {@code subagentStart} hook event.
	 *
	 * @param events the line-delimited native event stream for one turn
	 * @param json the JSON parser used by the owning runner session
	 * @throws IOException if the event stream is malformed or Codex omitted a required subagent hook event
	 */
	static void verifySubagentDelivery(String events, JsonMapper json) throws IOException
	{
		assert events != null;
		assert json != null;
		Set<String> subagents = new HashSet<>();
		Set<String> delivered = new HashSet<>();
		for (String line : events.lines().toList())
		{
			try
			{
				JsonNode event = json.readTree(line);
				JsonNode item = event.path("params").path("item");
				if ("subAgentActivity".equals(text(item.path("type"))) &&
					"started".equals(text(item.path("kind"))))
				{
					String threadId = text(item.path("agentThreadId"));
					if (!threadId.isBlank())
						subagents.add(threadId);
				}
				JsonNode run = event.path("params").path("run");
				if ("hook/completed".equals(text(event.path("method"))) &&
					"subagentStart".equals(text(run.path("eventName"))))
				{
					String threadId = text(event.path("params").path("threadId"));
					if (!threadId.isBlank())
						delivered.add(threadId);
				}
			}
			catch (JacksonException e)
			{
				throw new IOException("Codex app-server returned malformed native event while verifying CAT hooks", e);
			}
		}
		subagents.removeAll(delivered);
		if (!subagents.isEmpty())
		{
			throw new IOException("Codex did not deliver CAT's SubagentStart hook for subagent thread " +
				subagents.iterator().next());
		}
	}

	/**
	 * Verifies one configured CAT hook is trusted or managed by Codex.
	 *
	 * @param hooks the native hook metadata list
	 * @param key the expected CAT hook key
	 * @throws IOException if the hook is unavailable or untrusted
	 */
	private static void verifyConfiguredHook(JsonNode hooks, String key) throws IOException
	{
		if (!hooks.isArray())
			throw new IOException("Codex app-server returned no configured hooks");
		for (JsonNode hook : hooks)
		{
			if (!key.equals(text(hook.path("key"))))
				continue;
			String trust = text(hook.path("trustStatus"));
			if (trust.equals("trusted") || trust.equals("managed"))
				return;
			throw new IOException("CAT " + hookName(key) +
				" hook is " + trust + "; approve its current hash in the host Codex home before starting an isolated run");
		}
		throw new IOException("Codex did not configure CAT hook " + key);
	}

	/**
	 * Returns the reader-facing name of one CAT context hook.
	 *
	 * @param key the configured CAT hook key
	 * @return the hook name
	 */
	private static String hookName(String key)
	{
		assert key != null;
		if (key.equals(SESSION_START))
			return "session start";
		return "subagent start";
	}

	/**
	 * Returns a JSON string value or an empty value for an absent or non-string property.
	 *
	 * @param value a JSON value
	 * @return the string value, or an empty string
	 */
	private static String text(JsonNode value)
	{
		assert value != null;
		if (value.isString())
			return value.stringValue();
		return "";
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexHookDelivery()
	{
	}
}
