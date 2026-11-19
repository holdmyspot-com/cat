package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PluginInfo;

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
	 * Frames a request that creates one isolated workspace-write thread without interactive approval requests.
	 *
	 * @param workingDirectory the absolute copied-project directory
	 * @param model the optional model name
	 * @param developerInstructions the optional direct developer contract
	 * @param permissionProfile the optional named Codex permission profile
	 * @return the JSON-RPC request line
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code workingDirectory} is not absolute
	 */
	public static String threadStart(Path workingDirectory, String model, String developerInstructions,
		String permissionProfile)
	{
		return thread("thread/start", "", ",\"sessionStartSource\":\"startup\"", workingDirectory, model,
			developerInstructions, permissionProfile);
	}

	/**
	 * Frames the request that continues a recorded thread, in place of {@link #threadStart}, with the confinement that
	 * the resuming runner's bound gives it: a named permission profile and the working directory as its workspace
	 * root, or the workspace-write sandbox.
	 *
	 * @param workingDirectory      the absolute copied-project directory
	 * @param threadId              the recorded thread
	 * @param model                 the model name, or empty to keep the thread's
	 * @param developerInstructions the direct developer contract, or empty for none
	 * @param permissionProfile     the named Codex permission profile, or empty for the workspace-write sandbox
	 * @return the JSON-RPC request line
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code workingDirectory} is not absolute or {@code threadId} is blank
	 */
	public static String threadResume(Path workingDirectory, String threadId, String model,
		String developerInstructions, String permissionProfile)
	{
		requireThat(threadId, "threadId").isNotBlank();
		return thread("thread/resume", "\"threadId\":\"" + escape(threadId) + "\",", "", workingDirectory, model,
			developerInstructions, permissionProfile);
	}

	/**
	 * Frames a request that starts or resumes the session's thread.
	 *
	 * @param method                the request's method
	 * @param leading               the parameters that come first, each followed by a comma, or empty for none
	 * @param trailing              the parameters that follow the confinement, each preceded by a comma, or empty for
	 *                              none
	 * @param workingDirectory      the absolute copied-project directory
	 * @param model                 the model name, or empty for the default
	 * @param developerInstructions the direct developer contract, or empty for none
	 * @param permissionProfile     the named Codex permission profile, or empty for the workspace-write sandbox
	 * @return the JSON-RPC request line
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code workingDirectory} is not absolute
	 */
	private static String thread(String method, String leading, String trailing, Path workingDirectory, String model,
		String developerInstructions, String permissionProfile)
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
		return "{\"id\":2,\"method\":\"" + method + "\",\"params\":{" + leading + "\"cwd\":\"" +
			escape(workingDirectory.toString()) + "\"," + permissionParameter + ",\"approvalPolicy\":\"never\"" + trailing +
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
	 * Frames a request that stops the identified active turn.
	 *
	 * @param requestId the positive JSON-RPC request identifier
	 * @param threadId the non-blank app-server thread identifier
	 * @param turnId the non-blank active turn identifier
	 * @return the JSON-RPC request line
	 */
	public static String turnInterrupt(long requestId, String threadId, String turnId)
	{
		requireThat(requestId, "requestId").isPositive();
		requireThat(threadId, "threadId").isNotBlank();
		requireThat(turnId, "turnId").isNotBlank();
		return "{\"id\":" + requestId + ",\"method\":\"turn/interrupt\",\"params\":{\"threadId\":\"" +
			escape(threadId) + "\",\"turnId\":\"" + escape(turnId) + "\"}}\n";
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
		StringBuilder escaped = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); ++index)
		{
			char character = value.charAt(index);
			switch (character)
			{
				case '\\' -> escaped.append("\\\\");
				case '"' -> escaped.append("\\\"");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> appendEscapedControl(escaped, character);
			}
		}
		return escaped.toString();
	}

	/**
	 * Appends a printable character or its JSON control-character escape.
	 *
	 * @param target the string being written
	 * @param character the character to append
	 */
	private static void appendEscapedControl(StringBuilder target, char character)
	{
		assert target != null;
		if (character >= 0x20)
		{
			target.append(character);
			return;
		}
		target.append("\\u");
		String hexadecimal = Integer.toHexString(character);
		for (int padding = hexadecimal.length(); padding < 4; ++padding)
			target.append('0');
		target.append(hexadecimal);
	}
}
