package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessSandbox;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Decides on which platforms CAT starts a Codex session, as {@link HarnessSandbox} describes, and in which of Codex's
 * Windows sandbox implementations. Codex confines a session in a sandbox on Linux and macOS. It has a sandbox on
 * native Windows too, but CAT starts no Codex session there, because that sandbox failed CAT's checks that it denies
 * the session the reads that CAT denies, keeps its writes inside the paths that CAT allows, and lets it reach no host
 * other than its model's. CAT's checks covered the implementations {@code elevated} and {@code unelevated} only, so a
 * session that would run in another one, such as {@code mxc}, or in none, is refused as well.
 */
public final class CodexSessionSandbox
{
	/**
	 * The Windows sandbox implementations that CAT has verified confine a session within its policy.
	 */
	private static final Set<String> VERIFIED_IMPLEMENTATIONS = Set.of("elevated", "unelevated");
	/**
	 * The most bytes of a Codex configuration or requirements file that CAT reads.
	 */
	private static final int MAXIMUM_FILE_BYTES = 1_048_576;

	/**
	 * Returns why CAT refuses to start a Codex session on a platform.
	 *
	 * @param platform the platform that the session would run on
	 * @return the reason, or empty if CAT starts Codex sessions on {@code platform}
	 * @throws NullPointerException if {@code platform} is null
	 */
	public static Optional<String> refusal(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		if (platform.isWindows())
		{
			return Optional.of(HarnessSandbox.nativeWindowsRefusal("Codex", "Codex's sandbox on native Windows failed " +
				"CAT's checks of a session's writes, reads and network access with Codex 0.172.0: with the unelevated " +
				"implementation, Codex refuses to start a session whose policy denies reads, as the policy of every " +
				"session that CAT starts does; with the elevated implementation, Codex rejected every command of such a " +
				"session, a command wrote a file outside its writable folders through a hard link, and commands " +
				"reached public hosts that the policy did not allow"));
		}
		return Optional.empty();
	}

	/**
	 * Refuses a Codex session on native Windows whose effective configuration selects a Windows sandbox
	 * implementation that CAT has not verified, or selects none, under which Codex runs the session read-only and
	 * rejects every command. The session's {@code [windows] sandbox} is the one of the configuration that it takes it
	 * from, or if that sets none, the machine-wide configuration's, unless the machine-wide managed configuration, on a
	 * platform that has one, sets it. The machine-wide requirements select {@code mxc} when they set
	 * {@code require_mxc}, and select an implementation that their {@code allowed_sandbox_implementations} lists when
	 * no configuration selects one.
	 *
	 * @param configuration the Codex configuration that the session takes its {@code [windows] sandbox} from, which
	 *                      may not exist
	 * @param system        the machine-wide folder, which holds the configuration layers and the requirements
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the effective configuration selects an implementation that CAT has not
	 *                                 verified, or none, or if a file cannot be parsed
	 * @throws IOException             if a file cannot be read, or the registry cannot tell which requirements file
	 *                                 Codex reads
	 */
	public static void requireVerifiedImplementation(Path configuration, CodexSystemFolder system) throws IOException
	{
		requireThat(configuration, "configuration").isNotNull();
		requireThat(system, "system").isNotNull();
		Path requirementsFile = system.requirements();
		JsonNode requirements = windowsTable(requirementsFile);
		if (requirements.path("require_mxc").asBoolean(false))
		{
			throw new SessionRefusedException("CAT does not start the session: " + requirementsFile + " sets " +
				"[windows] require_mxc, which makes Codex run the session in the Windows sandbox implementation " +
				"\"mxc\", and CAT has not verified that it confines a session. Remove the setting, then start the " +
				"session again.");
		}
		// The managed layer's value overrides the session's own, which overrides the machine-wide configuration's
		List<Path> layers = new ArrayList<>();
		system.managedConfig().ifPresent(layers::add);
		layers.add(configuration);
		layers.add(system.config());
		for (Path layer : layers)
		{
			JsonNode selected = windowsTable(layer).path("sandbox");
			if (selected.isMissingNode())
				continue;
			if (selected.isString() && VERIFIED_IMPLEMENTATIONS.contains(selected.asString()))
				return;
			throw new SessionRefusedException("CAT does not start the session: " + layer + " selects the Windows " +
				"sandbox implementation " + selected + " in [windows] sandbox, and CAT has not verified that it " +
				"confines a session. Set it to \"elevated\" or \"unelevated\", then start the session again.");
		}
		JsonNode allowed = requirements.path("allowed_sandbox_implementations");
		if (allowed.isArray() && !allowed.isEmpty())
			return;
		throw new SessionRefusedException("CAT does not start the session: no Codex configuration selects a Windows " +
			"sandbox implementation, so Codex would run the session read-only and reject every command. Add " +
			"[windows] sandbox = \"elevated\" or \"unelevated\" to " + configuration + ", then start the session " +
			"again.");
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
