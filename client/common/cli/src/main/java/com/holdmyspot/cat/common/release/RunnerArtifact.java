package com.holdmyspot.cat.common.release;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Locale;

/**
 * A harness's CAT artifact that {@link ReleaseBundles} resolved, and the runner launcher in it.
 *
 * @param artifact the artifact's directory
 * @param runner   the runner launcher in the artifact
 * @param source   where the artifact came from
 */
public record RunnerArtifact(Path artifact, Path runner, Source source)
{
	/**
	 * Where a resolved artifact came from.
	 */
	public enum Source
	{
		/**
		 * The harness's installed CAT plugin.
		 */
		INSTALLED,
		/**
		 * A copy that an earlier download left in CAT's per-user bundle directory.
		 */
		BUNDLE,
		/**
		 * A copy that this resolution downloaded into CAT's per-user bundle directory.
		 */
		DOWNLOAD;

		/**
		 * Returns the source's name in a command's output.
		 *
		 * @return the lower-case name
		 */
		public String id()
		{
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * Creates a resolved artifact.
	 *
	 * @param artifact the artifact's directory
	 * @param runner   the runner launcher in the artifact
	 * @param source   where the artifact came from
	 * @throws NullPointerException if any of the arguments are null
	 */
	public RunnerArtifact
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(runner, "runner").isNotNull();
		requireThat(source, "source").isNotNull();
	}

	/**
	 * Returns the result as the {@code key=value} lines that a launcher prints: {@code artifact}, {@code runner} and
	 * {@code source}, each followed by a line feed.
	 *
	 * @return the lines
	 */
	public String format()
	{
		return "artifact=" + artifact + "\nrunner=" + runner + "\nsource=" + source.id() + "\n";
	}
}
