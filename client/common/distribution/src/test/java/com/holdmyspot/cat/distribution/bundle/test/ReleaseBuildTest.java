package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.ReleaseBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests how a build finds the release commit that it names in its plugin version.
 */
public final class ReleaseBuildTest
{
	/**
	 * Creates a test instance.
	 */
	public ReleaseBuildTest()
	{
	}

	/**
	 * Verifies that the commit that the release workflow names is used even when the project is not a Git working
	 * tree.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesNamedCommit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-build-"));

			requireThat(ReleaseBuild.commit("0123456789ab", project), "commit").isEqualTo("0123456789ab");
		}
	}

	/**
	 * Verifies that a named commit that is not twelve lower-case hexadecimal digits fails the build with a message that
	 * names the property and the value.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesMalformedNamedCommit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-build-"));
			for (String value : List.of("0123456789AB", "0123456789a", "0123456789abc", "g123456789ab"))
			{
				try
				{
					ReleaseBuild.commit(value, project);
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains("cat.build").contains(value);
					continue;
				}
				throw new AssertionError("The commit was accepted: " + value);
			}
		}
	}

	/**
	 * Verifies that a build that names no commit uses the first twelve digits of the project's {@code HEAD}.
	 *
	 * @throws IOException          if the fixture cannot be written
	 * @throws InterruptedException if the thread is interrupted while Git runs
	 */
	@Test
	public void usesHeadOfWorkingTree() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-build-"));
			git(project, "init", "-q");
			git(project, "-c", "user.name=CAT", "-c", "user.email=cat@example.invalid", "commit", "-q",
				"--allow-empty", "-m", "release");
			String head = git(project, "rev-parse", "HEAD");

			for (String unset : new String[]{null, ""})
				requireThat(ReleaseBuild.commit(unset, project), "commit").isEqualTo(head.substring(0, 12));
		}
	}

	/**
	 * Verifies that a build that names no commit, in a project that is not a Git working tree, fails with a message
	 * that names the property to set, the project and Git's status instead of inventing a commit.
	 * <p>
	 * Git's answer is the one that Git 2.43.0 gives for {@code git rev-parse --verify --quiet HEAD} in a directory
	 * outside any repository: it prints the message and exits with status 128. {@link #usesHeadOfWorkingTree()}
	 * observes the answer in a work tree through real Git.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesProjectWithoutHead() throws IOException
	{
		ReleaseBuild.GitHead outsideRepository = _ -> new ReleaseBuild.GitAnswer(128,
			"fatal: not a git repository (or any of the parent directories): .git");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-build-"));
			try
			{
				ReleaseBuild.commit(null, project, outsideRepository);
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("-Dcat.build=").contains(project.toString()).
					contains("status 128").contains("fatal: not a git repository");
				return;
			}
			throw new AssertionError("A commit was found");
		}
	}

	/**
	 * Runs Git in a directory.
	 *
	 * @param directory the working directory
	 * @param arguments Git's arguments
	 * @return Git's standard output without its line terminator
	 * @throws IOException          if Git cannot run or fails
	 * @throws InterruptedException if the thread is interrupted while Git runs
	 */
	private static String git(Path directory, String... arguments) throws IOException, InterruptedException
	{
		List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().put("GIT_CEILING_DIRECTORIES", directory.toAbsolutePath().getParent().toString());
		try (Process process = builder.start())
		{
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			if (process.waitFor() != 0)
				throw new IOException("git " + command + " failed: " + output);
			return output;
		}
	}
}
