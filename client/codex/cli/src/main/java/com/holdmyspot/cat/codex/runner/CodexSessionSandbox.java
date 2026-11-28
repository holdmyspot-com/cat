package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Decides whether a Codex configuration on native Windows selects a Windows sandbox implementation. Codex confines a
 * session in a sandbox on Linux, macOS and native Windows, where each of its implementations, {@code elevated},
 * {@code unelevated} and {@code mxc}, counts as a sandbox. A configuration that selects none makes Codex run the
 * session read-only and reject every command, so such a session is refused.
 */
public final class CodexSessionSandbox
{
	/**
	 * The most bytes of a Codex configuration or requirements file that CAT reads.
	 */
	private static final int MAXIMUM_FILE_BYTES = 1_048_576;

	/**
	 * Refuses a Codex session on native Windows whose effective configuration selects no Windows sandbox
	 * implementation, under which Codex runs the session read-only and rejects every command. The session's
	 * {@code [windows] sandbox} is the one of the configuration that it takes it from, or if that sets none, the
	 * machine-wide configuration's, unless the machine-wide managed configuration, on a platform that has one, sets it.
	 * The machine-wide requirements select {@code mxc} when they set {@code require_mxc}, and select an implementation
	 * that their {@code allowed_sandbox_implementations} lists when no configuration selects one. Any implementation
	 * that a configuration selects is accepted; Codex refuses a value that it does not know with its own reason.
	 *
	 * @param configuration the Codex configuration that the session takes its {@code [windows] sandbox} from, which
	 *                      may not exist
	 * @param system        the machine-wide folder, which holds the configuration layers and the requirements
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the effective configuration selects no implementation, or if a file cannot be
	 *                                 parsed
	 * @throws IOException             if a file cannot be read, or the registry cannot tell which requirements file
	 *                                 Codex reads
	 */
	public static void requireSelectedImplementation(Path configuration, CodexSystemFolder system) throws IOException
	{
		requireThat(configuration, "configuration").isNotNull();
		requireThat(system, "system").isNotNull();
		JsonNode requirements = windowsTable(system.requirements());
		if (requirements.path("require_mxc").asBoolean(false))
			return;
		// The managed layer's value overrides the session's own, which overrides the machine-wide configuration's
		List<Path> layers = new ArrayList<>();
		system.managedConfig().ifPresent(layers::add);
		layers.add(configuration);
		layers.add(system.config());
		for (Path layer : layers)
		{
			if (!windowsTable(layer).path("sandbox").isMissingNode())
				return;
		}
		JsonNode allowed = requirements.path("allowed_sandbox_implementations");
		if (allowed.isArray() && !allowed.isEmpty())
			return;
		throw new SessionRefusedException("CAT does not start the session: no Codex configuration selects a Windows " +
			"sandbox implementation, so Codex would run the session read-only and reject every command. Add " +
			"[windows] sandbox = \"mxc\", which CAT recommends, \"elevated\" or \"unelevated\" to " + configuration +
			", then start the session again.");
	}

	/**
	 * Returns the {@code [windows]} table of a Codex configuration or requirements file.
	 *
	 * @param file the file, which may not exist
	 * @return the table, or a missing node if the file or the table does not exist
	 * @throws SessionRefusedException if the file cannot be parsed
	 * @throws IOException             if the file cannot be read
	 */
	private static JsonNode windowsTable(Path file) throws IOException
	{
		if (!Files.isRegularFile(file))
			return MissingNode.getInstance();
		String text = BoundedInput.readUtf8(file, MAXIMUM_FILE_BYTES, "Codex configuration");
		try
		{
			return TomlMapper.builder().build().readTree(text).path("windows");
		}
		catch (JacksonException e)
		{
			throw new SessionRefusedException("CAT does not start the session: it cannot parse " + file + ", so it " +
				"cannot tell which Windows sandbox implementation Codex runs the session in. Fix the file, then start " +
				"the session again.", e);
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexSessionSandbox()
	{
	}
}
