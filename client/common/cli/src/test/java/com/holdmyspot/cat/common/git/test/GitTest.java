package com.holdmyspot.cat.common.git.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link Git}.
 */
public final class GitTest
{
	/**
	 * Creates the test fixture.
	 */
	public GitTest()
	{
	}

	/**
	 * Verifies that {@link Git#status(String...)} returns zero when Git succeeds.
	 *
	 * @throws IOException if the repository fixture cannot be created
	 */
	@Test
	public void returnsZeroWhenGitSucceeds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Git git = repository(cleanup);

			int status = git.status("rev-parse", "--git-dir");

			requireThat(status, "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that {@link Git#status(String...)} returns a nonzero exit status instead of throwing.
	 *
	 * @throws IOException if the repository fixture cannot be created
	 */
	@Test
	public void returnsNonzeroStatusWithoutThrowing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Git git = repository(cleanup);

			int notIgnored = git.status("check-ignore", "--quiet", "file.txt");
			int fatal = git.status("rev-parse", "--verify", "refs/heads/missing");

			requireThat(notIgnored, "notIgnored").isEqualTo(1);
			requireThat(fatal, "fatal").isEqualTo(128);
		}
	}

	/**
	 * Verifies that {@link Git#status(String...)} passes each argument to Git unchanged, including one that holds a
	 * space.
	 *
	 * @throws IOException if the repository fixture cannot be created
	 */
	@Test
	public void passesEachArgumentUnchanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Git git = repository(cleanup);
			Path ignoreFile = Path.of(git.lines("rev-parse", "--absolute-git-dir").getFirst()).resolve("info/exclude");
			Files.writeString(ignoreFile, "a b.txt\n", UTF_8);

			int spaced = git.status("check-ignore", "--quiet", "a b.txt");
			int joined = git.status("check-ignore", "--quiet", "ab.txt");

			requireThat(spaced, "spaced").isEqualTo(0);
			requireThat(joined, "joined").isEqualTo(1);
		}
	}

	/**
	 * Creates an empty repository.
	 *
	 * @param cleanup deletes the repository when the test ends
	 * @return Git for the repository
	 * @throws IOException if the repository cannot be created
	 */
	private static Git repository(TemporaryDirectories cleanup) throws IOException
	{
		Path directory = cleanup.add(Files.createTempDirectory("cat-git-"));
		Git git = new Git(directory);
		git.run("init", "--quiet");
		return git;
	}
}
