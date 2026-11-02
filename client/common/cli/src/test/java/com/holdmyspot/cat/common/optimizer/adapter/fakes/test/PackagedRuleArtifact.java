package com.holdmyspot.cat.common.optimizer.adapter.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Builds a test-owned artifact from CAT's packaged rules and a harness's packaged agent definitions, so that a test can
 * render the rules that a real installation holds.
 */
public final class PackagedRuleArtifact
{
	private static final String PATH_SCOPED_FRONTMATTER = "---\npaths:";

	/**
	 * Prevents instantiation.
	 */
	private PackagedRuleArtifact()
	{
	}

	/**
	 * Copies the packaged rules and agent definitions into an artifact. Each rule in {@code rules/common} that declares
	 * {@code paths} is also placed below {@code rules/include/common}, where packaging moves it so that it can be loaded
	 * by name.
	 *
	 * @param artifact the test-owned artifact directory
	 * @param rules the packaged {@code rules} directory
	 * @param agents the packaged agent definitions of one harness
	 * @param harness the name of the artifact's {@code agents} subdirectory that holds {@code agents}
	 * @return the artifact directory
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if a file cannot be read or written
	 */
	public static Path write(Path artifact, Path rules, Path agents, String harness) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(rules, "rules").isNotNull();
		requireThat(agents, "agents").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		copy(rules, artifact.resolve("rules"));
		copy(agents, artifact.resolve("agents").resolve(harness));
		Path common = rules.resolve("common");
		Path include = Files.createDirectories(artifact.resolve("rules/include/common"));
		try (Stream<Path> files = Files.list(common))
		{
			for (Path file : files.filter(Files::isRegularFile).toList())
			{
				if (Files.readString(file, UTF_8).startsWith(PATH_SCOPED_FRONTMATTER))
					Files.copy(file, include.resolve(file.getFileName().toString()));
			}
		}
		return artifact;
	}

	/**
	 * Copies the regular files below a directory, preserving their relative paths.
	 *
	 * @param source the directory to copy from
	 * @param target the directory to copy into
	 * @throws IOException if a file cannot be read or written
	 */
	private static void copy(Path source, Path target) throws IOException
	{
		try (Stream<Path> files = Files.walk(source))
		{
			for (Path file : files.filter(Files::isRegularFile).toList())
			{
				Path destination = target.resolve(source.relativize(file).toString());
				Files.createDirectories(destination.getParent());
				Files.copy(file, destination);
			}
		}
	}
}
