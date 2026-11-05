package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessProjectRepository;
import com.holdmyspot.cat.common.secrets.HarnessProjectRepositorySecrets;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.testng.annotations.Test;

/**
 * Tests the Git commands and environment that {@link HarnessProjectRepository} uses for a copied project's baseline.
 * Git is assumed to behave as documented, so the tests check what CAT asks it to do instead of running it.
 */
public final class HarnessProjectRepositoryTest
{
	/**
	 * Creates the test.
	 */
	public HarnessProjectRepositoryTest()
	{
	}

	/**
	 * Verifies initialization configures the host identity before committing, with isolated baseline commands.
	 *
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	@Test
	public void configuresHostIdentity() throws IOException
	{
		verifyIdentity(Map.of("user.name", new GitResult(0, "A User\n"),
			"user.email", new GitResult(0, "user@example.test\n")), "A User", "user@example.test");
	}

	/**
	 * Verifies two missing host values select the complete fixture identity.
	 *
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	@Test
	public void fallsBackWhenIdentityMissing() throws IOException
	{
		verifyIdentity(Map.of(), "CAT fixture", "cat-fixture@example.invalid");
	}

	/**
	 * Verifies that an identity that the launch names configures the copy instead of the source project's, so that a
	 * copy made from a private copy of the project commits as the requester's project does, and that a named value
	 * replaces only its own lookup.
	 *
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	@Test
	public void configuresNamedIdentity() throws IOException
	{
		Map<String, GitResult> host = Map.of("user.name", new GitResult(0, "Host User\n"),
			"user.email", new GitResult(0, "host@example.test\n"));
		verifyIdentity(host, "A User", "user@example.test", "A User", "user@example.test");
		verifyIdentity(host, "A User", null, "A User", "host@example.test");
	}

	/**
	 * Verifies a missing email discards the host name and selects the complete fixture identity.
	 *
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	@Test
	public void fallsBackWhenEmailMissing() throws IOException
	{
		verifyIdentity(Map.of("user.name", new GitResult(0, "A User\n")),
			"CAT fixture", "cat-fixture@example.invalid");
	}

	/**
	 * Verifies a missing name discards the host email and selects the complete fixture identity.
	 *
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	@Test
	public void fallsBackWhenNameMissing() throws IOException
	{
		verifyIdentity(Map.of("user.email", new GitResult(0, "user@example.test\n")),
			"CAT fixture", "cat-fixture@example.invalid");
	}

	/**
	 * Verifies a failed lookup reports the Git command instead of selecting the fallback identity.
	 *
	 * @throws IOException the expected lookup failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*git, config, --get, user.email.*")
	public void reportsFailedIdentityLookup() throws IOException
	{
		verifyIdentity(Map.of("user.email", new GitResult(128, "")),
			"CAT fixture", "cat-fixture@example.invalid");
	}

	/**
	 * Verifies status one with output is a Git failure rather than a missing configuration value.
	 *
	 * @throws IOException the expected lookup failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*git, config, --get, user.name.*")
	public void rejectsMissingStatusWithOutput() throws IOException
	{
		verifyIdentity(Map.of("user.name", new GitResult(1, "unexpected output")),
			"CAT fixture", "cat-fixture@example.invalid");
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

		Map<String, String> environment = HarnessProjectRepositorySecrets.getHarnessProjectRepositoryAccess().
			environment(parent, gitHome);

		requireThat(environment, "environment").isEqualTo(Map.of("PATH", "/bin", "HOME", gitHome.toString(),
			"GIT_CONFIG_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0"));
	}

	/**
	 * Runs initialization against completed Git results, checking command order, identity and environment boundaries.
	 *
	 * @param identity      the host configuration lookup results; absent keys model Git status one with no output
	 * @param expectedName  the user name that must be configured before the baseline commit
	 * @param expectedEmail the user email that must be configured before the baseline commit
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	private static void verifyIdentity(Map<String, GitResult> identity, String expectedName, String expectedEmail)
		throws IOException
	{
		verifyIdentity(identity, null, null, expectedName, expectedEmail);
	}

	/**
	 * Runs initialization with a named identity against completed Git results, checking command order, identity and
	 * environment boundaries.
	 *
	 * @param identity      the host configuration lookup results; absent keys model Git status one with no output
	 * @param name          the user name that the launch names, or {@code null} to look it up
	 * @param email         the user email that the launch names, or {@code null} to look it up
	 * @param expectedName  the user name that must be configured before the baseline commit
	 * @param expectedEmail the user email that must be configured before the baseline commit
	 * @throws IOException if the fixture cannot be created or initialization fails
	 */
	private static void verifyIdentity(Map<String, GitResult> identity, String name, String email,
		String expectedName, String expectedEmail) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "repository-identity-"));
			Path sourceProject = Files.createDirectory(root.resolve("source"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path programs = Files.createDirectory(root.resolve("programs"));
			String filename = "git";
			if (Platform.current().isWindows())
				filename += ".exe";
			Path git = Files.createFile(programs.resolve(filename));
			requireThat(git.toFile().setExecutable(true, false), "executable").isTrue();
			Map<String, String> parent = Map.of("PATH", programs.toString(), "HOME", root.resolve("home").toString(),
				"GIT_CONFIG_GLOBAL", root.resolve("host.gitconfig").toString(), "GIT_AUTHOR_NAME", "Parent Author");
			Map<String, String> isolated = Map.of("PATH", programs.toString(), "HOME", root.resolve("git-home").toString(),
				"GIT_CONFIG_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0");
			List<List<String>> commands = new ArrayList<>();
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(
				() -> {}, new ManualClock(Instant.EPOCH), builder ->
				{
					List<String> command = new ArrayList<>(builder.command());
					command.set(0, "git");
					commands.add(command);
					if (command.subList(1, 3).equals(List.of("config", "--get")))
					{
						requireThat(builder.directory().toPath(), "lookupDirectory").isEqualTo(sourceProject);
						requireThat(builder.environment(), "lookupEnvironment").isEqualTo(parent);
						return identity.getOrDefault(command.getLast(), new GitResult(1, ""));
					}
					requireThat(builder.directory().toPath(), "baselineDirectory").isEqualTo(project);
					requireThat(builder.environment(), "baselineEnvironment").isEqualTo(isolated);
					return new GitResult(0, "");
				});

			HarnessProjectRepository.initialize(sourceProject, project, root, parent, executables, name, email);

			List<List<String>> expected = new ArrayList<>();
			if (name == null)
				expected.add(List.of("git", "config", "--get", "user.name"));
			if (email == null)
				expected.add(List.of("git", "config", "--get", "user.email"));
			expected.addAll(List.of(
				List.of("git", "init", "--quiet"),
				List.of("git", "config", "maintenance.auto", "false"),
				List.of("git", "config", "gc.auto", "0"),
				List.of("git", "config", "user.name", expectedName),
				List.of("git", "config", "user.email", expectedEmail),
				List.of("git", "add", "--all"),
				List.of("git", "-c", "commit.gpgSign=false", "commit", "--quiet", "--allow-empty", "--no-verify", "-m",
					"CAT fixture baseline")));
			requireThat(commands, "commands").isEqualTo(expected);
		}
	}

	/**
	 * Supplies completed Git output and status without starting Git. Configuration reads follow the brief's Git
	 * contract: status zero returns a value and status one with no output denotes a missing value.
	 */
	private static final class GitResult extends Process
	{
		private final int status;
		private final InputStream stdout;

		/**
		 * Creates a completed command result.
		 *
		 * @param status the Git exit status
		 * @param output the Git stdout text
		 */
		private GitResult(int status, String output)
		{
			this.status = status;
			this.stdout = new ByteArrayInputStream(output.getBytes(UTF_8));
		}

		@Override
		public OutputStream getOutputStream()
		{
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return stdout;
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor()
		{
			return status;
		}

		@Override
		public int exitValue()
		{
			return status;
		}

		@Override
		public void destroy()
		{
		}
	}
}
