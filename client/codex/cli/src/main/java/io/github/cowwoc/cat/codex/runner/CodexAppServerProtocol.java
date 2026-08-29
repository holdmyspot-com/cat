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
		// Validator runs supply runtimeWorkspaceRoots so Codex can preserve the executor evidence as read-only. That
		// app-server field is gated behind this explicitly negotiated capability.
		return "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"cat-runner\",\"version\":\"" +
			PluginInfo.CAT_VERSION + "\"},\"capabilities\":{\"experimentalApi\":true}}}\n";
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
		return threadStart(workingDirectory, model, "");
	}

	/**
	 * Frames a request that creates one isolated thread with an optional direct developer contract.
	 *
	 * @param workingDirectory the absolute copied-project directory
	 * @param model the optional model name
	 * @param developerInstructions the optional direct developer contract
	 * @return the JSON-RPC request line
	 */
	public static String threadStart(Path workingDirectory, String model, String developerInstructions)
	{
		return threadStart(workingDirectory, model, developerInstructions, "");
	}

	/**
	 * Frames a request that creates one isolated thread with an optional named permission profile.
	 *
	 * @param workingDirectory the absolute copied-project directory
	 * @param model the optional model name
	 * @param developerInstructions the optional direct developer contract
	 * @param permissionProfile the optional named Codex permission profile
	 * @return the JSON-RPC request line
	 */
	public static String threadStart(Path workingDirectory, String model, String developerInstructions,
		String permissionProfile)
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(model, "model").isNotNull();
		requireThat(developerInstructions, "developerInstructions").isNotNull();
		requireThat(permissionProfile, "permissionProfile").isNotNull();
		if (!workingDirectory.isAbsolute())
			throw new IllegalArgumentException("workingDirectory must be absolute");
		String modelParameter = "";
		if (!model.isBlank())
			modelParameter = ",\"model\":\"" + escape(model) + "\"";
		String developerInstructionsParameter = "";
		if (!developerInstructions.isBlank())
			developerInstructionsParameter = ",\"developerInstructions\":\"" + escape(developerInstructions) + "\"";
		String permissionParameter = "\"sandbox\":\"workspace-write\"";
		if (!permissionProfile.isBlank())
		{
			// Validators must inspect executor evidence without being able to alter the evidence used to justify a verdict.
			permissionParameter = "\"permissions\":\"" + escape(permissionProfile) +
				"\",\"runtimeWorkspaceRoots\":[\"" + escape(workingDirectory.toString()) + "\"]";
		}
		return "{\"id\":2,\"method\":\"thread/start\",\"params\":{\"cwd\":\"" +
			escape(workingDirectory.toString()) + "\"," + permissionParameter + ",\"approvalPolicy\":\"never\"," +
			"\"sessionStartSource\":\"startup\"" +
			modelParameter + developerInstructionsParameter + "}}\n";
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
		return turnStart(requestId, threadId, prompt, "");
	}

	/**
	 * Frames a text turn with an optional reasoning effort.
	 *
	 * @param requestId the positive JSON-RPC request identifier
	 * @param threadId the non-blank app-server thread identifier
	 * @param prompt the non-blank user prompt
	 * @param effort the optional reasoning effort
	 * @return the JSON-RPC request line
	 */
	public static String turnStart(long requestId, String threadId, String prompt, String effort)
	{
		requireThat(requestId, "requestId").isPositive();
		requireThat(threadId, "threadId").isNotBlank();
		requireThat(prompt, "prompt").isNotBlank();
		requireThat(effort, "effort").isNotNull();
		String effortParameter = "";
		if (!effort.isBlank())
			effortParameter = ",\"effort\":\"" + escape(effort) + "\"";
		return "{\"id\":" + requestId + ",\"method\":\"turn/start\",\"params\":{\"threadId\":\"" +
			escape(threadId) + "\",\"input\":[{\"type\":\"text\",\"text\":\"" + escape(prompt) +
			"\"}]" + effortParameter + "}}\n";
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
