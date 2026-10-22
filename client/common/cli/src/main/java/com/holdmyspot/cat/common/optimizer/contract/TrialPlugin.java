package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PluginInfo;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A plugin that every trial of a campaign installs, as the prompt test configuration lists it, with the base that CAT
 * copied into campaign storage when the campaign started.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param name the plugin's name: the manifest name of a plugin that the project builds, or
 *   {@code <plugin>@<marketplace>} for one that it does not
 * @param baseDigest the digest of the base's files
 * @param source where the base came from
 * @param mappings the project prompt files and directories whose staged versions trials write over the base, in
 *   project path order; empty unless the project builds the plugin
 * @param repository the {@code repository} URL in the base's manifest, or {@code null} if the manifest states none
 */
public record TrialPlugin(String name, String baseDigest, BaseSource source, List<Mapping> mappings,
	String repository)
{
	/**
	 * The form of a base digest.
	 */
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

	/**
	 * Creates a trial plugin.
	 *
	 * @param name the plugin's name: the manifest name of a plugin that the project builds, or
	 *   {@code <plugin>@<marketplace>} for one that it does not
	 * @param baseDigest the digest of the base's files
	 * @param source where the base came from
	 * @param mappings the project prompt files and directories whose staged versions trials write over the base, in
	 *   project path order; empty unless the project builds the plugin
	 * @param repository the {@code repository} URL in the base's manifest, or {@code null} if the manifest states none
	 * @throws NullPointerException if any argument other than {@code repository} is null
	 * @throws IllegalArgumentException if {@code name} is blank, {@code baseDigest} is not 64 lowercase hexadecimal
	 *   digits, an installed plugin has mappings, or two mappings share a source
	 */
	public TrialPlugin
	{
		requireThat(name, "name").isNotBlank();
		requireThat(baseDigest, "baseDigest").isNotNull();
		if (!DIGEST.matcher(baseDigest).matches())
			throw new IllegalArgumentException("baseDigest must be 64 lowercase hexadecimal digits: " + baseDigest);
		requireThat(source, "source").isNotNull();
		mappings = List.copyOf(mappings);
		if (source instanceof Installed && !mappings.isEmpty())
			throw new IllegalArgumentException("The installed plugin " + name + " has mappings");
		Set<Path> sources = new HashSet<>();
		for (Mapping mapping : mappings)
		{
			if (!sources.add(mapping.source()))
				throw new IllegalArgumentException("The source " + mapping.source() + " is mapped twice into " + name);
		}
	}

	/**
	 * Indicates whether the project builds the plugin, so that trials install it with the staged source's mapped files
	 * written over its base.
	 *
	 * @return {@code true} if the base is a build output of the project
	 */
	public boolean isProjectBuilt()
	{
		return source instanceof Build;
	}

	/**
	 * Indicates whether the plugin is CAT, whose rule loader loads the project rules in trials. The plugin's name and
	 * marketplace take no part, because they vary with how the user installed CAT.
	 *
	 * @return {@code true} if the base's manifest states CAT's repository
	 * @see PluginInfo#isCatRepository(String)
	 */
	public boolean isCat()
	{
		return PluginInfo.isCatRepository(repository);
	}

	/**
	 * Returns the path inside the plugin that receives a project file.
	 *
	 * @param file a project file, relative to the project root
	 * @return the file's path relative to the plugin directory, or empty if no mapping covers it
	 * @throws NullPointerException if {@code file} is null
	 */
	public Optional<Path> mappedPath(Path file)
	{
		requireThat(file, "file").isNotNull();
		for (Mapping mapping : mappings)
		{
			Optional<Path> mapped = mapping.mappedPath(file);
			if (mapped.isPresent())
				return mapped;
		}
		return Optional.empty();
	}

	/**
	 * Requires a path that names a location at or below a root without leaving it.
	 *
	 * @param path the path
	 * @param name the name of the path
	 * @param emptyAllowed {@code true} if the path may name the root itself
	 * @throws NullPointerException if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is absolute, not normalized, leaves its root, or is empty when
	 *   {@code emptyAllowed} is {@code false}
	 */
	private static void requireRelative(Path path, String name, boolean emptyAllowed)
	{
		requireThat(path, name).isNotNull();
		boolean empty = path.toString().isEmpty();
		if (path.isAbsolute() || (empty && !emptyAllowed) || !path.normalize().equals(path) || path.startsWith(".."))
		{
			throw new IllegalArgumentException(name + " must be a normalized relative path that stays below its " +
				"root: " + path);
		}
	}

	/**
	 * Where the base of a trial plugin came from.
	 */
	public sealed interface BaseSource
	{
	}

	/**
	 * A plugin that the project builds: CAT runs the command in the optimization worktree and copies the output
	 * directory as the base.
	 *
	 * @param command the build command's arguments, run from the project root; empty if the output directory is the
	 *   plugin itself and needs no build
	 * @param output the directory that holds the built plugin, relative to the project root
	 */
	public record Build(List<String> command, Path output) implements BaseSource
	{
		/**
		 * Creates a build.
		 *
		 * @param command the build command's arguments, run from the project root; empty if the output directory is
		 *   the plugin itself and needs no build
		 * @param output the directory that holds the built plugin, relative to the project root
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if an argument of {@code command} is null or its program is blank, or if
		 *   {@code output} is absolute, not normalized or leaves the project
		 */
		public Build
		{
			command = List.copyOf(command);
			if (!command.isEmpty())
				requireThat(command.getFirst(), "command.getFirst()").isNotBlank();
			requireRelative(output, "output", true);
		}
	}

	/**
	 * A plugin that the project does not build: CAT copies the user's installed copy, which the harness's install
	 * records locate, as the base. The campaign does not keep the copy's location, which lies outside the project:
	 * trials install the base, and the base digest identifies it.
	 */
	public record Installed() implements BaseSource
	{
	}

	/**
	 * A project directory or file whose staged version a trial writes over a path of the plugin's base.
	 *
	 * @param source the project directory or file, relative to the project root
	 * @param target the base directory or file that receives it, relative to the plugin directory; empty for the
	 *   plugin directory itself
	 */
	public record Mapping(Path source, Path target)
	{
		/**
		 * Creates a mapping.
		 *
		 * @param source the project directory or file, relative to the project root
		 * @param target the base directory or file that receives it, relative to the plugin directory; empty for the
		 *   plugin directory itself
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if either path is absolute, not normalized or leaves its root, or if
		 *   {@code source} is empty
		 */
		public Mapping
		{
			requireRelative(source, "source", false);
			requireRelative(target, "target", true);
		}

		/**
		 * Returns the path inside the plugin that receives a project file.
		 *
		 * @param file a project file, relative to the project root
		 * @return the file's path relative to the plugin directory, or empty if the mapping does not cover it
		 * @throws NullPointerException if {@code file} is null
		 */
		public Optional<Path> mappedPath(Path file)
		{
			requireThat(file, "file").isNotNull();
			if (!file.startsWith(source))
				return Optional.empty();
			return Optional.of(target.resolve(source.relativize(file)));
		}
	}
}
