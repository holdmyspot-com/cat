package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.UserRuleTree;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


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
}
