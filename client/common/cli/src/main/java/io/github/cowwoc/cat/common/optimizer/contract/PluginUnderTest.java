package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The harness plugin that holds the target prompt files. Each trial installs the copy of the plugin that belongs to
 * the trial's staged source, so a candidate's edit and an emptied control file reach the installed plugin.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public sealed interface PluginUnderTest
{
	/**
	 * Returns whether trials install CAT beside the plugin. It is ignored when the plugin is CAT itself, which trials
	 * install from its copy alone.
	 *
	 * @return {@code true} if the plugin depends on CAT
	 */
	boolean dependsOnCat();

	/**
	 * Returns the evidence for {@link #dependsOnCat()}.
	 *
	 * @return the evidence that the contract author cites
	 */
	String catEvidence();

	/**
	 * Requires a path that names a location below a root without leaving it.
	 *
	 * @param path the path
	 * @param name the name of the path
	 * @throws NullPointerException if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is absolute, empty, not normalized or leaves its root
	 */
	private static void requireRelative(Path path, String name)
	{
		requireThat(path, name).isNotNull();
		String text = path.toString();
		if (path.isAbsolute() || text.isEmpty() || !path.normalize().equals(path) || path.startsWith(".."))
		{
			throw new IllegalArgumentException(name + " must be a normalized relative path that stays below its " +
				"root: " + path);
		}
	}

	/**
	 * A plugin whose directory is in the project: the directory that holds {@code .codex-plugin/plugin.json} or
	 * {@code .claude-plugin/plugin.json}. A trial's plugin copy is that directory of its staged source.
	 *
	 * @param directory the plugin directory, relative to the project root
	 * @param dependsOnCat {@code true} if trials install CAT beside the plugin
	 * @param catEvidence the evidence for {@code dependsOnCat}
	 */
	record Root(Path directory, boolean dependsOnCat, String catEvidence) implements PluginUnderTest
	{
		/**
		 * Creates a plugin root.
		 *
		 * @param directory the plugin directory, relative to the project root
		 * @param dependsOnCat {@code true} if trials install CAT beside the plugin
		 * @param catEvidence the evidence for {@code dependsOnCat}
		 * @throws NullPointerException if {@code directory} or {@code catEvidence} are null
		 * @throws IllegalArgumentException if {@code directory} is absolute, not normalized or leaves the project,
		 *   or if {@code catEvidence} is blank
		 */
		public Root
		{
			requireRelative(directory, "directory");
			requireThat(catEvidence, "catEvidence").isNotBlank();
		}
	}

	/**
	 * A plugin that a build assembles from project sources: a built plugin directory, the base, and the project
	 * source directories that the build copies into it. CAT copies the base into campaign storage when the user
	 * confirms the contract; a trial's plugin copy is that base copy with the staged source's mapped files written
	 * over it.
	 *
	 * @param base the absolute path of the built plugin directory
	 * @param baseDigest the digest of the base's files that CAT computes, or an empty string before CAT computes it
	 * @param mappings the project source directories and the plugin directories that receive their files
	 * @param dependsOnCat {@code true} if trials install CAT beside the plugin
	 * @param catEvidence the evidence for {@code dependsOnCat}
	 */
	record Built(Path base, String baseDigest, List<Mapping> mappings, boolean dependsOnCat, String catEvidence)
		implements PluginUnderTest
	{
		/**
		 * The form of a computed base digest.
		 */
		private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

		/**
		 * Creates a built plugin.
		 *
		 * @param base the absolute path of the built plugin directory
		 * @param baseDigest the digest of the base's files that CAT computes, or an empty string before CAT computes
		 *   it
		 * @param mappings the project source directories and the plugin directories that receive their files
		 * @param dependsOnCat {@code true} if trials install CAT beside the plugin
		 * @param catEvidence the evidence for {@code dependsOnCat}
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code base} is relative or not normalized, {@code baseDigest} is
		 *   neither empty nor 64 lowercase hexadecimal digits, {@code mappings} is empty or maps one source twice,
		 *   or {@code catEvidence} is blank
		 */
		public Built
		{
			requireThat(base, "base").isNotNull();
			if (!base.isAbsolute() || !base.normalize().equals(base))
				throw new IllegalArgumentException("base must be a normalized absolute path: " + base);
			requireThat(baseDigest, "baseDigest").isNotNull();
			if (!baseDigest.isEmpty() && !DIGEST.matcher(baseDigest).matches())
				throw new IllegalArgumentException("baseDigest must be 64 lowercase hexadecimal digits: " + baseDigest);
			requireThat(mappings, "mappings").isNotEmpty();
			requireThat(catEvidence, "catEvidence").isNotBlank();
			mappings = List.copyOf(mappings);
			Set<Path> sources = new HashSet<>();
			for (Mapping mapping : mappings)
			{
				if (!sources.add(mapping.source()))
					throw new IllegalArgumentException("The source " + mapping.source() + " is mapped twice");
			}
		}

		/**
		 * Returns this plugin with the digest of its base.
		 *
		 * @param digest the digest of the base's files
		 * @return the plugin
		 * @throws NullPointerException if {@code digest} is null
		 * @throws IllegalArgumentException if {@code digest} is not 64 lowercase hexadecimal digits
		 */
		public Built withBaseDigest(String digest)
		{
			requireThat(digest, "digest").isNotEmpty();
			return new Built(base, digest, mappings, dependsOnCat, catEvidence);
		}
	}

	/**
	 * A project source directory whose files a build copies into a plugin directory.
	 *
	 * @param source the source directory, relative to the project root
	 * @param target the directory that receives the files, relative to the plugin directory
	 */
	record Mapping(Path source, Path target)
	{
		/**
		 * Creates a mapping.
		 *
		 * @param source the source directory, relative to the project root
		 * @param target the directory that receives the files, relative to the plugin directory
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if either path is absolute, empty, not normalized or leaves its root
		 */
		public Mapping
		{
			requireRelative(source, "source");
			requireRelative(target, "target");
		}
	}
}
