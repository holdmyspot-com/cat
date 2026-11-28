package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Recommends Codex's {@code mxc} Windows sandbox implementation, which has the fewest known weaknesses of Codex's
 * Windows sandboxes, when the implementation that Codex applies on native Windows is another one, none, or one that
 * CAT cannot tell.
 * <p>
 * The implementation is read from the configuration that Codex applies, without probing the machine: the
 * requirements' {@code [windows] require_mxc} gives {@code mxc}; otherwise the {@code [windows] sandbox} of the
 * winning layer among the machine-wide configuration, the user's configuration, the profile file that the launch
 * names, the launch's {@code -c} overrides and the machine-wide managed configuration. CAT cannot tell the
 * implementation when a layer or the requirements set {@code [windows] allow_mxc}, a layer sets
 * {@code [features] prefer_mxc}, the requirements list {@code allowed_sandbox_implementations}, or a file or override
 * cannot be read, because Codex then chooses among implementations by rules that CAT does not model. CAT never
 * selects {@code mxc} itself.
 */
public final class CodexWindowsSandbox
{
	/**
	 * Bounds a configuration file to 1 MiB.
	 */
	private static final int MAX_BYTES = 1024 * 1024;
	/**
	 * The name of a profile whose file Codex reads.
	 */
	private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
	/**
	 * The implementation that CAT recommends.
	 */
	private static final String MXC = "mxc";

	/**
	 * Prevents instantiation.
	 */
	private CodexWindowsSandbox()
	{
	}

	/**
	 * Returns the recommendation of {@code mxc} for a session, when the implementation that Codex applies to it is not
	 * {@code mxc} or CAT cannot tell which it is.
	 *
	 * @param platform  the platform that Codex runs on
	 * @param system    the machine-wide folder, which holds the machine-wide configuration and the requirements
	 * @param codexHome Codex's home, which holds the user's configuration and the profile files
	 * @param launch    the session's command line, or empty if it is not known
	 * @return the recommendation, which names the setting and the Windows builds that run {@code mxc}; empty off
	 *   native Windows or when Codex applies {@code mxc}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Optional<String> recommendation(Platform platform, CodexSystemFolder system, Path codexHome,
		Optional<CodexWriteGrants.Launch> launch)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(system, "system").isNotNull();
		requireThat(codexHome, "codexHome").isNotNull();
		requireThat(launch, "launch").isNotNull();
		if (!platform.isWindows())
			return Optional.empty();
		String cause = cause(system, codexHome, launch).orElse("");
		if (cause.isEmpty())
			return Optional.empty();
		return Optional.of("CAT recommends Codex's \"mxc\" Windows sandbox, which has the fewest known weaknesses " +
			"of Codex's Windows sandboxes, and " + cause + ". To use it, add [windows] sandbox = \"mxc\" to " +
			codexHome.resolve("config.toml") + ". It runs on Windows 11 24H2 build 26100.9278 or later and 25H2 build " +
			"26200.9278 or later, and not on Windows Server. docs/security-model.md in " + PluginInfo.CAT_REPOSITORY +
			" lists the known weaknesses of each Windows sandbox. CAT continues with the sandbox that Codex applies " +
			"now.");
	}

	/**
	 * Returns why Codex may apply an implementation other than {@code mxc}.
	 *
	 * @param system    the machine-wide folder
	 * @param codexHome Codex's home
	 * @param launch    the session's command line, or empty if it is not known
	 * @return the cause, which completes a sentence, or empty if Codex applies {@code mxc}
	 */
	private static Optional<String> cause(CodexSystemFolder system, Path codexHome,
		Optional<CodexWriteGrants.Launch> launch)
	{
		String unsure = "CAT cannot tell which one Codex applies here, because ";
		JsonNode requirements;
		try
		{
			Optional<JsonNode> read = read(system.requirements());
			if (read.isEmpty())
				return Optional.of(unsure + "it cannot read " + system.requirements());
			requirements = read.get();
		}
		catch (IOException e)
		{
			return Optional.of(unsure + "it cannot tell where Codex reads its requirements from: " + e.getMessage());
		}
		JsonNode windowsRequirements = requirements.path("windows");
		if (windowsRequirements.path("require_mxc").asBoolean(false))
			return Optional.empty();
		if (windowsRequirements.has("allow_mxc"))
			return Optional.of(unsure + "the requirements set [windows] allow_mxc");
		if (windowsRequirements.has("allowed_sandbox_implementations") ||
			requirements.has("allowed_sandbox_implementations"))
		{
			return Optional.of(unsure + "the requirements list allowed_sandbox_implementations");
		}
		if (launch.isEmpty())
			return Optional.of(unsure + "it cannot read the session's command line");

		List<Path> files = new ArrayList<>();
		files.add(system.config());
		if (!launch.get().ignoreUserConfig())
			files.add(codexHome.resolve("config.toml"));
		Optional<String> profile = launch.get().profile();
		if (profile.isPresent())
		{
			if (!PROFILE_NAME.matcher(profile.get()).matches())
				return Optional.of(unsure + "it cannot find the file of the profile \"" + profile.get() + "\"");
			files.add(codexHome.resolve(profile.get() + ".config.toml"));
		}
		List<JsonNode> layers = new ArrayList<>();
		for (Path file : files)
		{
			if (!Files.exists(file))
				continue;
			Optional<JsonNode> layer = read(file);
			if (layer.isEmpty())
				return Optional.of(unsure + "it cannot read " + file);
			layers.add(layer.get());
		}
		for (String override : launch.get().overrides())
		{
			Optional<JsonNode> layer = override(override);
			if (layer.isEmpty())
				return Optional.of(unsure + "it cannot parse the -c override \"" + override + "\"");
			layers.add(layer.get());
		}
		Optional<Path> managed = system.managedConfig();
		if (managed.isPresent() && Files.exists(managed.get()))
		{
			Optional<JsonNode> layer = read(managed.get());
			if (layer.isEmpty())
				return Optional.of(unsure + "it cannot read " + managed.get());
			layers.add(layer.get());
		}

		String implementation = "";
		for (JsonNode layer : layers)
		{
			if (layer.path("windows").has("allow_mxc"))
				return Optional.of(unsure + "the configuration sets [windows] allow_mxc");
			if (layer.path("features").has("prefer_mxc"))
				return Optional.of(unsure + "the configuration sets [features] prefer_mxc");
			JsonNode sandbox = layer.path("windows").path("sandbox");
			if (sandbox.isString())
				implementation = sandbox.asString();
		}
		if (implementation.equals(MXC))
			return Optional.empty();
		if (implementation.isEmpty())
			return Optional.of("Codex's configuration selects no Windows sandbox implementation");
		return Optional.of("Codex applies its \"" + implementation + "\" implementation here");
	}

	/**
	 * Reads a TOML file.
	 *
	 * @param file the file
	 * @return the document, an empty table if the file does not exist, or empty if it cannot be read or parsed
	 */
	private static Optional<JsonNode> read(Path file)
	{
		if (!Files.exists(file))
			return Optional.of(TomlText.table());
		try
		{
			if (Files.size(file) > MAX_BYTES)
				return Optional.empty();
			JsonNode document = TomlText.tryParse(Files.readString(file, StandardCharsets.UTF_8));
			if (document == null || !document.isObject())
				return Optional.empty();
			return Optional.of(document);
		}
		catch (IOException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Parses a {@code -c} override, whose value Codex reads as TOML and otherwise as a string.
	 *
	 * @param override the override, as {@code key=value}
	 * @return the layer that it holds, or empty if it cannot be parsed
	 */
	private static Optional<JsonNode> override(String override)
	{
		int equals = override.indexOf('=');
		if (equals <= 0)
			return Optional.empty();
		String key = override.substring(0, equals).strip();
		String value = override.substring(equals + 1).strip();
		JsonNode document = TomlText.tryParse(key + " = " + value);
		if (document == null)
			document = TomlText.tryParse(key + " = " + TomlText.quote(value));
		if (document == null || !document.isObject())
			return Optional.empty();
		return Optional.of(document);
	}
}
