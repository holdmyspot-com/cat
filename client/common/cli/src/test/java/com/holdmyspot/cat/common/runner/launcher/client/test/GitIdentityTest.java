package com.holdmyspot.cat.common.runner.launcher.client.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitWorkTrees;
import com.holdmyspot.cat.common.runner.launcher.client.GitIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests {@link GitIdentity#fromGit()}.
 */
public final class GitIdentityTest
{
	/**
	 * Creates the test fixture.
	 */
	public GitIdentityTest()
	{
	}

	/**
	 * Verifies that the reader reports the name and email that Git is configured with in the project, on every
	 * platform.
	 *
	 * @throws IOException if the fixture cannot be created or Git cannot be run
	 */
	@Test
	public void readsConfiguredIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = GitWorkTrees.create(cleanup.add(Files.createTempDirectory("cat-identity-")));
			GitIdentity git = GitIdentity.fromGit();

			Optional<String> name = git.name(project);
			Optional<String> email = git.email(project);

			requireThat(name, "name").isEqualTo(Optional.of("Test"));
			requireThat(email, "email").isEqualTo(Optional.of("test@example.com"));
		}
	}
}
