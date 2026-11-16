package com.holdmyspot.cat.common.git.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.delegation.Repositories;
import com.holdmyspot.cat.common.delegation.SourceSnapshot;
import com.holdmyspot.cat.common.git.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
	 * Verifies that an explicit environment replaces inherited process variables.
	 *
	 * @throws IOException if the repository fixture cannot be created or Git fails
	 */
	@Test
	public void explicitEnvironmentDoesNotInherit()	throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-git-"));
			AtomicReference<Map<String, String>> observed = new AtomicReference<>();
			Git git = new Git(directory, Map.of("GIT_CONFIG_NOSYSTEM", "1"), builder ->
			{
				observed.set(Map.copyOf(builder.environment()));
				return builder.start();
			});

			git.run("init", "--quiet");

			requireThat(observed.get(), "environment").isEqualTo(Map.of("GIT_CONFIG_NOSYSTEM", "1"));
		}
	}

	/**
	 * Verifies real delegate clones have separate repository state and exact snapshot bytes.
	 *
	 * @throws IOException if the repository fixture cannot be created or Git fails
	 */
	@Test
	public void delegateRepositoriesAreIndependent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-delegation-source-"));
			Path cohort = cleanup.add(Files.createTempDirectory("cat-delegation-cohort-"));
			Files.writeString(project.resolve("source.txt"), "dirty source");
			SourceSnapshot snapshot = SourceSnapshot.capture(project, cohort, List.of("source.txt"));
			Map<String, String> environment = Map.of("PATH", System.getenv("PATH"));
			Repositories.RepositorySet repositories = Repositories.create(cohort, snapshot, List.of("alpha", "beta"),
				environment, com.holdmyspot.cat.common.CodeLoadingVariables::start);
			Path alpha = repositories.workProjects().get("alpha");
			Path beta = repositories.workProjects().get("beta");
			Git alphaGit = new Git(alpha, environment, com.holdmyspot.cat.common.CodeLoadingVariables::start);
			Git betaGit = new Git(beta, environment, com.holdmyspot.cat.common.CodeLoadingVariables::start);
			alphaGit.run("config", "user.name", "Alpha only");
			Files.writeString(alpha.resolve("source.txt"), "alpha edit");

			int betaAlphaConfig = betaGit.status("config", "--local", "--get", "user.name");
			requireThat(Files.readString(beta.resolve("source.txt")), "betaBytes").isEqualTo("dirty source");
			requireThat(betaAlphaConfig, "betaDoesNotHaveAlphaConfig").isEqualTo(1);
			requireThat(Files.readString(project.resolve("source.txt")), "parentBytes").isEqualTo("dirty source");
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
