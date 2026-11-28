package com.holdmyspot.cat.common.delegation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.delegation.Repositories;
import com.holdmyspot.cat.common.delegation.SourceSnapshot;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.test.ExitedProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests isolated delegate repository wiring through a controlled Git process boundary.
 */
public final class RepositoriesTest
{
	/**
	 * Creates the test fixture.
	 */
	public RepositoriesTest()
	{
	}

	/**
	 * Verifies repository construction sends independent clone requests and preserves snapshot bytes.
	 * The launcher supplies scripted documented Git responses. Assertions cover CAT's outgoing requests, not Git's
	 * reaction.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void cohortsHaveIndependentGitState() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = scope.temporaryDirectory();
			Path project = cleanup.add(Files.createTempDirectory(temporary, "repository-project-"));
			Path cohort = cleanup.add(Files.createTempDirectory(temporary, "repository-cohort-"));
			Files.writeString(project.resolve("unit.txt"), "source");
			SourceSnapshot snapshot = SourceSnapshot.capture(project, cohort, List.of("unit.txt"));
			List<GitRequest> gitRequests = new ArrayList<>();
			Map<String, String> environment = Map.of("PATH", "/controlled/path",
				"SYSTEMROOT", "controlled_system_root", "HOME", "/caller_home", "GIT_DIR", "/caller_git_dir",
				"GIT_CONFIG_GLOBAL", "/caller_gitconfig", "GIT_CONFIG_COUNT", "1",
				"GIT_CONFIG_KEY_0", "core.hooksPath", "GIT_CONFIG_VALUE_0", "/caller_hooks");

			Repositories.RepositorySet repositories = Repositories.create(cohort, snapshot,
				List.of("alpha", "beta"), environment, builder ->
				{
					gitRequests.add(new GitRequest(builder.directory().toPath(), List.copyOf(builder.command()),
						Map.copyOf(builder.environment())));
					String subcommand = builder.command().get(1);
					String output = switch (subcommand)
					{
						case "hash-object" -> "blob-id\n";
						case "write-tree" -> "tree-id\n";
						case "commit-tree" -> "commit-id\n";
						default -> "";
					};
					return new ExitedProcess(builder, 0, output, "");
				});
			Path alpha = repositories.workProjects().get("alpha");
			Path beta = repositories.workProjects().get("beta");
			Files.writeString(alpha.resolve("unit.txt"), "alpha change");

			Map<String, String> isolatedEnvironment = Map.ofEntries(Map.entry("PATH", "/controlled/path"),
				Map.entry("SYSTEMROOT", "controlled_system_root"), Map.entry("HOME", cohort.resolve("git-home").toString()),
				Map.entry("XDG_CONFIG_HOME", cohort.resolve("git-home/.config").toString()),
				Map.entry("GIT_CONFIG_NOSYSTEM", "1"),
				Map.entry("GIT_CONFIG_GLOBAL", cohort.resolve("git-home/gitconfig").toString()),
				Map.entry("GIT_TERMINAL_PROMPT", "0"), Map.entry("GIT_OPTIONAL_LOCKS", "0"),
				Map.entry("GIT_AUTHOR_NAME", "CAT delegation"), Map.entry("GIT_AUTHOR_EMAIL", "delegation@localhost"),
				Map.entry("GIT_COMMITTER_NAME", "CAT delegation"),
				Map.entry("GIT_COMMITTER_EMAIL", "delegation@localhost"));
			List<Map<String, String>> requestEnvironments = gitRequests.stream().map(GitRequest::environment).toList();
			List<GitRequest> expectedClones = List.of(
				new GitRequest(repositories.sourceRepository(), List.of("git", "clone", "--quiet", "--shared", "--no-checkout",
					repositories.sourceRepository().toString(), alpha.toString()), isolatedEnvironment),
				new GitRequest(repositories.sourceRepository(), List.of("git", "clone", "--quiet", "--shared", "--no-checkout",
					repositories.sourceRepository().toString(), beta.toString()), isolatedEnvironment));

			requireThat(alpha, "alpha").isNotEqualTo(beta);
			requireThat(Files.readString(beta.resolve("unit.txt")), "betaBytes").isEqualTo("source");
			requireThat(gitRequests, "alphaCloneRequest").contains(expectedClones.get(0));
			requireThat(gitRequests, "betaCloneRequest").contains(expectedClones.get(1));
			requireThat(requestEnvironments, "gitEnvironments").isEqualTo(
				Collections.nCopies(gitRequests.size(), isolatedEnvironment));
		}
	}

	/**
	 * Captures an immutable request made to the controlled Git process boundary.
	 *
	 * @param directory working directory supplied to Git
	 * @param command command and arguments supplied to Git
	 * @param environment environment supplied to Git
	 */
	private record GitRequest(Path directory, List<String> command, Map<String, String> environment)
	{
	}
}
