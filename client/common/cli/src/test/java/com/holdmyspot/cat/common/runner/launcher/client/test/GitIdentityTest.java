package com.holdmyspot.cat.common.runner.launcher.client.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitWorkTrees;
import com.holdmyspot.cat.common.runner.launcher.client.GitConfigIdentity;
import com.holdmyspot.cat.common.runner.launcher.client.GitIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests {@link GitIdentity#fromGit()}. Real Git runs only in {@link #readsConfiguredIdentity()}, the end-to-end check
 * of the {@code git config --get} reads; what the reader makes of the status and output of a read is tested by calling
 * {@link GitConfigIdentity#interpret(int, String)} with the results that Git gives.
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
	 * Verifies, end to end through real Git, that the reader reports the name and email that Git is configured with in
	 * the project, on every platform.
	 *
	 * @throws IOException if the fixture cannot be created or Git cannot be run
	 */
	@Test
	public void readsConfiguredIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = GitWorkTrees.notIgnoring(cleanup.add(Files.createTempDirectory("cat-identity-")));
			Path configuration = project.resolve(".git/config");
			Files.writeString(configuration, Files.readString(configuration, UTF_8) +
				"[user]\n\tname = Test\n\temail = test@example.com\n", UTF_8);
			GitIdentity git = GitIdentity.fromGit();

			Optional<String> name = git.name(project);
			Optional<String> email = git.email(project);

			requireThat(name, "name").isEqualTo(Optional.of("Test"));
			requireThat(email, "email").isEqualTo(Optional.of("test@example.com"));
		}
	}

	/**
	 * Verifies that a value that Git prints is the identity, without the line feed that ends it or the white space
	 * around it.
	 */
	@Test
	public void interpretsPrintedValue()
	{
		requireThat(GitConfigIdentity.interpret(0, "Alice\n"), "withLineFeed").isEqualTo(Optional.of("Alice"));
		requireThat(GitConfigIdentity.interpret(0, "Alice"), "withoutLineFeed").isEqualTo(Optional.of("Alice"));
		requireThat(GitConfigIdentity.interpret(0, "\tAlice\r\n"), "padded").isEqualTo(Optional.of("Alice"));
	}

	/**
	 * Verifies that a key that is not set (status 1), a directory that is not in a repository or a configuration that
	 * Git cannot read (status 128), and a value that is empty give no identity.
	 */
	@Test
	public void interpretsMissingValue()
	{
		requireThat(GitConfigIdentity.interpret(1, ""), "unset").isEqualTo(Optional.empty());
		requireThat(GitConfigIdentity.interpret(128, ""), "failed").isEqualTo(Optional.empty());
		requireThat(GitConfigIdentity.interpret(128, "Alice\n"), "failedWithOutput").isEqualTo(Optional.empty());
		requireThat(GitConfigIdentity.interpret(0, ""), "emptyOutput").isEqualTo(Optional.empty());
		requireThat(GitConfigIdentity.interpret(0, "\n"), "blankOutput").isEqualTo(Optional.empty());
	}
}
