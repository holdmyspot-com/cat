package com.holdmyspot.cat.codex.installer.test;

import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stands in for Codex 0.172.0's {@code codex app-server} in the exchange that the installer has with it, through an
 * in-memory {@link FakeHarnessCli}. It answers {@code initialize}, sends after the {@code initialized} notification
 * the two notifications that the real server sent there, answers {@code hooks/list} with the hooks that a test
 * scripts, or with the error that a test scripts, and ends when its input closes without answering a request that is
 * still open.
 * <p>
 * The messages follow the schema that {@code codex app-server generate-json-schema --experimental} wrote for codex-cli
 * 0.172.0 ({@code InitializeResponse}, {@code ConfigWarningNotification}, {@code HooksListResponse}) and the exchange
 * that CAT recorded with that version on 2026-11-26 in a Codex home holding CAT's plugin, in which each hook record
 * held every field that {@link #hook} writes. codex-cli 0.160.0+41 answered the same exchange in the same shapes on the
 * same day, except that each hook's {@code currentHash} held its declaration hash alone and no record held
 * {@code environmentId}.
 */
final class AppServerProgram
{
	/**
	 * Writes the server's messages and reads the installer's requests.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The hooks that {@code hooks/list} lists.
	 */
	private final List<ObjectNode> hooks;
	/**
	 * The message of the error with which {@code hooks/list} is answered, or empty to answer with {@link #hooks}.
	 */
	private final String refusal;
	/**
	 * The server's process.
	 */
	private final FakeHarnessCli process = new FakeHarnessCli(this::answer);

	/**
	 * Creates a server.
	 *
	 * @param hooks   the hooks that {@code hooks/list} lists
	 * @param refusal the message of the error with which {@code hooks/list} is answered, or empty to list
	 *                {@code hooks}
	 */
	private AppServerProgram(List<ObjectNode> hooks, String refusal)
	{
		this.hooks = List.copyOf(hooks);
		this.refusal = refusal;
	}

	/**
	 * Returns the process of a server whose {@code hooks/list} lists hooks.
	 *
	 * @param hooks the hooks, as {@link #hook} returns them
	 * @return the running process
	 */
	static Process listing(List<ObjectNode> hooks)
	{
		return new AppServerProgram(hooks, "").process;
	}

	/**
	 * Returns the process of a server that answers {@code hooks/list} with an error.
	 *
	 * @param message the error's message
	 * @return the running process
	 */
	static Process refusing(String message)
	{
		return new AppServerProgram(List.of(), message).process;
	}

	/**
	 * Returns the process of a {@code codex} program that exits with status 1 before it writes anything, as when its
	 * app-server cannot start.
	 *
	 * @return the exited process
	 */
	static Process exited()
	{
		FakeHarnessCli exited = new FakeHarnessCli(_ ->
		{
		}, 1);
		exited.exit();
		return exited;
	}

	/**
	 * Returns a hook as {@code hooks/list} lists it.
	 *
	 * @param key         the hook's key
	 * @param source      where Codex found the hook, such as {@code plugin} or {@code project}
	 * @param pluginId    the plugin that declares the hook, or null for a hook that no plugin declares
	 * @param sourcePath  the file that declares the hook
	 * @param currentHash the hashes that Codex computes for the hook: {@code <declaration hash>/<content hash>}, as
	 *                    Codex 0.172.0 lists them, or the declaration hash alone, as codex-cli 0.160.0+41, which keeps
	 *                    no content hash, lists it
	 * @param trustStatus {@code trusted}, {@code untrusted}, {@code modified} or {@code managed}
	 * @return the hook's record
	 */
	static ObjectNode hook(String key, String source, String pluginId, Path sourcePath, String currentHash,
		String trustStatus)
	{
		ObjectNode hook = JSON.createObjectNode();
		hook.put("key", key);
		hook.put("eventName", "sessionStart");
		hook.put("handlerType", "command");
		hook.put("command", "\"$PLUGIN_ROOT/hooks/session-start.sh\"");
		hook.put("async", false);
		hook.put("matcher", "^(startup|resume|clear|compact)$");
		hook.put("timeoutSec", 600);
		hook.putNull("statusMessage");
		hook.put("additionalContextLimit", 0);
		hook.put("sourcePath", sourcePath.toString());
		hook.put("source", source);
		if (pluginId == null)
			hook.putNull("pluginId");
		else
			hook.put("pluginId", pluginId);
		hook.putNull("environmentId");
		hook.put("displayOrder", 0);
		hook.put("enabled", true);
		hook.put("isManaged", false);
		hook.put("currentHash", currentHash);
		hook.put("trustStatus", trustStatus);
		return hook;
	}

	/**
	 * Answers one line of the installer's input.
	 *
	 * @param line the line
	 */
	private void answer(String line)
	{
		JsonNode message = JSON.readTree(line);
		JsonNode id = message.path("id");
		switch (message.path("method").asString(""))
		{
			case "initialize" ->
			{
				ObjectNode response = JSON.createObjectNode();
				response.set("id", id);
				ObjectNode result = response.putObject("result");
				result.put("userAgent", "cat-installer/0.172.0 (Ubuntu 24.4.0; x86_64) unknown (cat-installer; 1)");
				result.put("codexHome", "/home/user/.codex");
				result.put("platformFamily", "unix");
				result.put("platformOs", "linux");
				process.emit(JSON.writeValueAsString(response));
			}
			case "initialized" -> process.emit(
				"{\"method\":\"configWarning\",\"params\":{\"summary\":\"Project-local config, hooks, and exec policies " +
					"are disabled in the following folders until the project is trusted, but skills still load.\"," +
					"\"details\":null},\"emittedAtMs\":1795717287348}",
				"{\"method\":\"remoteControl/status/changed\",\"params\":{\"status\":\"disabled\",\"serverName\":\"host\"," +
					"\"installationId\":\"bd05588c-881b-4e0b-902f-3188f0474399\",\"environmentId\":null}," +
					"\"emittedAtMs\":1795717287348}");
			case "hooks/list" ->
			{
				ObjectNode response = JSON.createObjectNode();
				response.set("id", id);
				if (!refusal.isEmpty())
				{
					response.putObject("error").put("code", -32_600).put("message", refusal);
					process.emit(JSON.writeValueAsString(response));
					return;
				}
				ArrayNode data = response.putObject("result").putArray("data");
				ObjectNode entry = data.addObject();
				entry.put("cwd", message.path("params").path("cwds").path(0).asString(""));
				ArrayNode listed = entry.putArray("hooks");
				hooks.forEach(listed::add);
				entry.putArray("warnings");
				entry.putArray("errors");
				process.emit(JSON.writeValueAsString(response));
			}
			default -> throw new AssertionError("Unexpected app-server message: " + line);
		}
	}
}
