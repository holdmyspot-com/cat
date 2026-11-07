package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitWorkTrees;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.runner.HarnessProjectRepository;
import com.holdmyspot.cat.common.runner.HarnessProjectRepository.Identity;
import com.holdmyspot.cat.common.secrets.HarnessProjectRepositoryAccess;
import com.holdmyspot.cat.common.secrets.HarnessProjectRepositorySecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the identity and the environment that {@link HarnessProjectRepository} uses for a copied project's baseline.
 * <p>
 * The decisions are tested by calling the methods that make them with the values that Git gives. Real Git runs only
 * in {@link #commitsBaselineAsHostIdentity()}, the end-to-end check of what Git does with the commands.
 */
public final class HarnessProjectRepositoryTest
{
	private static final Identity FIXTURE = new Identity("CAT fixture", "cat-fixture@example.invalid");
	private static final HarnessProjectRepositoryAccess ACCESS =
		HarnessProjectRepositorySecrets.getHarnessProjectRepositoryAccess();

	/**
	 * Creates the test.
	 */
	public HarnessProjectRepositoryTest()
	{
	}

	/**
	 * Verifies that the copy's commits use the host's name and email when the launch names none.
	 */
	@Test
	public void usesHostIdentity()
	{
		requireThat(ACCESS.identity(null, null, "A User", "user@example.test"), "identity").
			isEqualTo(new Identity("A User", "user@example.test"));
	}

	/**
	 * Verifies that a host that lacks a name or an email, or both, selects the complete fixture identity and
	 * discards the value that the host has.
	 */
	@Test
	public void fallsBackWhenHostIdentityIsIncomplete()
	{
		requireThat(ACCESS.identity(null, null, null, null), "none").isEqualTo(FIXTURE);
		requireThat(ACCESS.identity(null, null, "A User", null), "emailMissing").isEqualTo(FIXTURE);
		requireThat(ACCESS.identity(null, null, null, "user@example.test"), "nameMissing").isEqualTo(FIXTURE);
	}

	/**
	 * Verifies that an identity that the launch names configures the copy instead of the source project's, and that a
	 * named value replaces only its own lookup.
	 */
	@Test
	public void configuresNamedIdentity()
	{
		requireThat(ACCESS.identity("A User", "user@example.test", "Host User", "host@example.test"), "both").
			isEqualTo(new Identity("A User", "user@example.test"));
		requireThat(ACCESS.identity("A User", null, "Host User", "host@example.test"), "onlyName").
			isEqualTo(new Identity("A User", "host@example.test"));
		requireThat(ACCESS.identity("A User", null, null, null), "nameWithoutHost").isEqualTo(FIXTURE);
	}

	/**
	 * Verifies how {@code git config --get} results read, as Git 2.43.0 gives them: status 0 and the value with a final
	 * line feed for a set key, status 1 with no output for an unset key, and status 128 with a message for a failure.
	 *
	 * @throws IOException if a result that denotes a value or a missing value is refused
	 */
	@Test
	public void readsConfigurationResult() throws IOException
	{
		List<String> command = List.of("git", "config", "--get", "user.name");

		requireThat(ACCESS.identityValue(command, 0, "A User\n", ""), "set").isEqualTo("A User");
		requireThat(ACCESS.identityValue(command, 0, "A User", ""), "withoutLineFeed").isEqualTo("A User");
		requireThat(ACCESS.identityValue(command, 1, "", ""), "unset").isNull();
	}

	/**
	 * Verifies that a failed lookup, including status one with output or a message, is a Git failure that names the
	 * command, rather than a missing configuration value.
	 */
	@Test
	public void rejectsFailedConfigurationResult()
	{
		List<String> command = List.of("git", "config", "--get", "user.name");
		Map<String, Object[]> results = Map.of(
			"failure", new Object[]{128, "", "fatal: bad config line 1 in file x"},
			"statusOneWithOutput", new Object[]{1, "unexpected output", ""},
			"statusOneWithMessage", new Object[]{1, "", "error: could not lock config file"});
		for (Map.Entry<String, Object[]> result : results.entrySet())
		{
			try
			{
				ACCESS.identityValue(command, (Integer) result.getValue()[0], (String) result.getValue()[1],
					(String) result.getValue()[2]);
				throw new AssertionError(result.getKey() + " was accepted");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), result.getKey() + ".message").contains("git, config, --get, user.name");
			}
		}
	}

	/**
	 * Verifies Git runs with a run-owned home, without system configuration or prompts, and keeps only the approved
	 * parent variables.
	 */
	@Test
	public void isolatesGitFromUserConfiguration()
	{
		Path gitHome = Path.of("run", "git-home");
		Map<String, String> parent = Map.of("PATH", "/bin", "HOME", "/home/user", "GIT_DIR", "/elsewhere",
			"GIT_CONFIG_GLOBAL", "/home/user/.gitconfig");

		Map<String, String> environment = ACCESS.environment(parent, gitHome);

		requireThat(environment, "environment").isEqualTo(Map.of("PATH", "/bin", "HOME", gitHome.toString(),
			"GIT_CONFIG_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0"));
	}

	/**
	 * Verifies, end to end through real Git, that initialization makes the copy a repository whose one commit holds
	 * the copied files, is authored by the host's identity, and whose configuration disables background maintenance.
	 *
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	@Test
	public void commitsBaselineAsHostIdentity() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "repository-identity-"));
			Path sourceProject = GitWorkTrees.notIgnoring(root.resolve("source"));
			Path project = Files.createDirectory(root.resolve("project"));
			Files.writeString(project.resolve("file.txt"), "content", UTF_8);
			Path hostConfiguration = Files.writeString(root.resolve("host.gitconfig"),
				"[user]\n\tname = A User\n\temail = user@example.test\n", UTF_8);

			HarnessProjectRepository.initialize(sourceProject, project, root, parentEnvironment(root, hostConfiguration),
				Yielding.executableFiles(), null, null);

			Git git = new Git(project);
			List<String> commits = git.lines("log", "--format=%an <%ae> %s");
			requireThat(commits, "commits").isEqualTo(List.of("A User <user@example.test> CAT fixture baseline"));
			requireThat(git.lines("ls-tree", "-r", "--name-only", "HEAD"), "files").isEqualTo(List.of("file.txt"));
			requireThat(git.lines("config", "--local", "maintenance.auto"), "maintenance.auto").
				isEqualTo(List.of("false"));
			requireThat(git.lines("config", "--local", "gc.auto"), "gc.auto").isEqualTo(List.of("0"));
		}
	}

	/**
	 * Returns a parent environment in which Git reads only the given host configuration.
	 *
	 * @param root              the test's directory
	 * @param hostConfiguration the host's global Git configuration
	 * @return the environment
	 */
	private static Map<String, String> parentEnvironment(Path root, Path hostConfiguration)
	{
		return Map.of("PATH", System.getenv("PATH"), "HOME", root.resolve("home").toString(),
			"GIT_CONFIG_GLOBAL", hostConfiguration.toString(), "GIT_CONFIG_NOSYSTEM", "1");
	}
}
