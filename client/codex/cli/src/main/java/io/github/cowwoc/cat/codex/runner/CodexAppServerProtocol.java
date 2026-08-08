package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PluginInfo;

import java.nio.file.Path;

/**
 * Creates the JSON-RPC requests sent to Codex's app-server CLI process.
 */
public final class CodexAppServerProtocol
{
	/**
	 * Frames the mandatory app-server initialization request.
	 *
	 * @return the JSON-RPC request line
	 */
	public static String initialize()
	{
		return "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"cat-runner\",\"version\":\"" +
			PluginInfo.CAT_VERSION + "\"}}}\n";
	}

	/**
	 * Frames the acknowledgement that completes the app-server initialization handshake.
	 *
	 * @return the JSON-RPC notification line
	 */
	public static String initialized()
	{
		return "{\"method\":\"initialized\",\"params\":{}}\n";
	}

	/**
	 * Frames a request that creates an isolated workspace-write thread without interactive approval requests.
	 *
	 * @param workingDirectory the absolute copied-project directory
	 * @param model the optional model name
	 * @return the JSON-RPC request line
	 */
	public static String threadStart(Path workingDirectory, String model)
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(model, "model").isNotNull();
		if (!workingDirectory.isAbsolute())
			throw new IllegalArgumentException("workingDirectory must be absolute");
		String modelParameter = "";
		if (!model.isBlank())
			modelParameter = ",\"model\":\"" + escape(model) + "\"";
		return "{\"id\":2,\"method\":\"thread/start\",\"params\":{\"cwd\":\"" +
			escape(workingDirectory.toString()) + "\",\"sandbox\":\"workspace-write\",\"approvalPolicy\":\"never\"," +
			"\"sessionStartSource\":\"startup\"" +
			modelParameter + "}}\n";
	}

	/**
	 * Frames a request for the effective hook state at one project directory.
	 *
	 * @param requestId the positive JSON-RPC request identifier
	 * @param workingDirectory the absolute project directory
	 * @return the JSON-RPC request line
	 * @throws NullPointerException if {@code workingDirectory} is null
	 * @throws IllegalArgumentException if {@code requestId} is not positive or {@code workingDirectory} is not absolute
	 */
	public static String hooksList(long requestId, Path workingDirectory)
	{
		requireThat(requestId, "requestId").isPositive();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		if (!workingDirectory.isAbsolute())
			throw new IllegalArgumentException("workingDirectory must be absolute");
		return "{\"id\":" + requestId + ",\"method\":\"hooks/list\",\"params\":{\"cwds\":[\"" +
			escape(workingDirectory.toString()) + "\"]}}\n";
	}

	/**
	 * Frames a text turn for an existing app-server thread.
	 *
	 * @param requestId the positive JSON-RPC request identifier
	 * @param threadId the non-blank app-server thread identifier
	 * @param prompt the non-blank user prompt
	 * @return the JSON-RPC request line
	 * @throws NullPointerException if {@code threadId} or {@code prompt} are null
	 * @throws IllegalArgumentException if {@code requestId} is not positive or a string is blank
	 */
	public static String turnStart(long requestId, String threadId, String prompt)
	{
		requireThat(requestId, "requestId").isPositive();
		requireThat(threadId, "threadId").isNotBlank();
		requireThat(prompt, "prompt").isNotBlank();
		return "{\"id\":" + requestId + ",\"method\":\"turn/start\",\"params\":{\"threadId\":\"" +
			escape(threadId) + "\",\"input\":[{\"type\":\"text\",\"text\":\"" + escape(prompt) +
			"\"}]}}\n";
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexAppServerProtocol()
	{
	}

	/**
	 * Escapes a JSON string value.
	 *
	 * @param value a non-null value
	 * @return the escaped value
	 */
	private static String escape(String value)
	{
		assert value != null;
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
	}
}
