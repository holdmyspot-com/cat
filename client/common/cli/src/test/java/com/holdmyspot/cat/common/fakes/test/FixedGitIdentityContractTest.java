package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.client.test.GitIdentityContract;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the {@link GitIdentityContract} against {@link FixedGitIdentity}, so that the identity that launch-route tests
 * use in place of Git answers as {@code GitIdentityTest} shows that Git does.
 */
public final class FixedGitIdentityContractTest
{
	/**
	 * Creates the test.
	 */
	public FixedGitIdentityContractTest()
	{
	}

	/**
	 * Verifies that the fixed identity reports the name and email that it stands for as the project's configured
	 * identity.
	 *
	 * @throws IOException if the project directory cannot be created
	 */
	@Test
	public void readsConfiguredIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-identity-"));

			new GitIdentityContract(new FixedGitIdentity("Test", "test@example.com")).readsConfiguredIdentity(project,
				"Test", "test@example.com");
		}
	}
}
