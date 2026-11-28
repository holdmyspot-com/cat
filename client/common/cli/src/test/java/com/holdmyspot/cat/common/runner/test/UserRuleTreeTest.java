package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.UserRuleTree;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;


/**
 * Tests {@link UserRuleTree}.
 */
public final class UserRuleTreeTest
{
	/**
	 * Creates a test instance.
	 */
	public UserRuleTreeTest()
	{
	}

	/**
	 * Verifies a nested user rule is copied into an isolated root.
	 */
	@Test
	public void copiesRegularUserRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path destination = cleanup.add(Files.createTempDirectory("cat-isolated-rules-"));
			Path rule = source.resolve("common/personal.md");
			Files.createDirectories(rule.getParent());
			Files.writeString(rule, "personal guidance");

			UserRuleTree.copy(source, destination);

			requireThat(Files.readString(destination.resolve("common/personal.md")), "copiedRule").
				isEqualTo("personal guidance");
		}
	}

	/**
	 * Verifies that an absent user rule root copies nothing and is not an error.
	 *
	 * @throws IOException if the test's directories cannot be created
	 */
	@Test
	public void copiesNothingFromAbsentRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path destination = cleanup.add(Files.createTempDirectory("cat-isolated-rules-"));

			UserRuleTree.copy(parent.resolve("absent"), destination);

			try (Stream<Path> copied = Files.list(destination))
			{
				requireThat(copied.toList(), "copied").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a user rule root that is a file is refused.
	 *
	 * @throws IOException if the test's files cannot be created
	 */
	@Test
	public void refusesRootThatIsFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path destination = cleanup.add(Files.createTempDirectory("cat-isolated-rules-"));
			Path source = Files.writeString(parent.resolve("rules"), "not a directory");

			IOException e = expectThrows(IOException.class, () -> UserRuleTree.copy(source, destination));

			requireThat(e.getMessage(), "message").contains("not a real directory");
		}
	}

	/**
	 * Verifies that a user rule root that is a link to a directory is refused rather than followed.
	 *
	 * @throws IOException if the test's files cannot be created
	 */
	@Test
	public void refusesRootThatIsLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path destination = cleanup.add(Files.createTempDirectory("cat-isolated-rules-"));
			Path real = Files.createDirectory(parent.resolve("real"));
			Files.writeString(real.resolve("personal.md"), "personal guidance");
			Path source = Files.createSymbolicLink(parent.resolve("rules"), real);

			IOException e = expectThrows(IOException.class, () -> UserRuleTree.copy(source, destination));

			requireThat(e.getMessage(), "message").contains("not a real directory");
			requireThat(Files.exists(destination.resolve("personal.md")), "copied").isFalse();
		}
	}

	/**
	 * Verifies that a user rule that is a link to a file is refused rather than followed.
	 *
	 * @throws IOException if the test's files cannot be created
	 */
	@Test
	public void refusesRuleThatIsLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path destination = cleanup.add(Files.createTempDirectory("cat-isolated-rules-"));
			Path outside = Files.writeString(parent.resolve("outside.md"), "outside guidance");
			Path source = Files.createDirectory(parent.resolve("rules"));
			Files.createSymbolicLink(source.resolve("linked.md"), outside);

			IOException e = expectThrows(IOException.class, () -> UserRuleTree.copy(source, destination));

			requireThat(e.getMessage(), "message").contains("not a regular file").contains("linked.md");
		}
	}

	/**
	 * Verifies that a user rule directory that is a link, which the walk visits as a file, is refused rather than
	 * followed.
	 *
	 * @throws IOException if the test's files cannot be created
	 */
	@Test
	public void refusesDirectoryThatIsLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path destination = cleanup.add(Files.createTempDirectory("cat-isolated-rules-"));
			Path outside = Files.createDirectory(parent.resolve("outside"));
			Files.writeString(outside.resolve("personal.md"), "outside guidance");
			Path source = Files.createDirectory(parent.resolve("rules"));
			Files.createSymbolicLink(source.resolve("common"), outside);

			IOException e = expectThrows(IOException.class, () -> UserRuleTree.copy(source, destination));

			requireThat(e.getMessage(), "message").contains("not a regular file").contains("common");
			requireThat(Files.exists(destination.resolve("common/personal.md")), "copied").isFalse();
		}
	}
}
