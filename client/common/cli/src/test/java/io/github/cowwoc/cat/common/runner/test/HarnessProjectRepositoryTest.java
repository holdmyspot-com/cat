package io.github.cowwoc.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessProjectRepository;
import io.github.cowwoc.cat.common.secrets.HarnessProjectRepositorySecrets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
	 * Verifies the baseline commits every copied file without hooks or signing, after disabling background maintenance.
	 */
	@Test
	public void commitsAllAfterDisablingMaintenance()
	{
		requireThat(HarnessProjectRepositorySecrets.access().commands(), "commands").isEqualTo(List.of(
			List.of("git", "init", "--quiet"),
			List.of("git", "config", "maintenance.auto", "false"),
			List.of("git", "config", "gc.auto", "0"),
			List.of("git", "add", "--all"),
			List.of("git", "-c", "user.name=CAT fixture", "-c", "user.email=cat-fixture@example.invalid", "-c",
				"commit.gpgSign=false", "commit", "--quiet", "--allow-empty", "--no-verify", "-m",
				"CAT fixture baseline")));
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

		Map<String, String> environment = HarnessProjectRepositorySecrets.access().environment(parent, gitHome);

		requireThat(environment, "environment").isEqualTo(Map.of("PATH", "/bin", "HOME", gitHome.toString(),
			"GIT_CONFIG_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0"));
	}
}
