package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessCredentials;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessCredentials}.
 */
public final class HarnessCredentialsTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessCredentialsTest()
	{
	}

	/**
	 * Verifies only allow-listed credentials are copied into the isolated home.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void copiesOnlyAllowedCredentials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-credential-source-"));
			Path destination = cleanup.add(Files.createTempDirectory("cat-credential-destination-"));
			Files.writeString(source.resolve("auth.json"), "credential");
			Files.writeString(source.resolve("config.toml"), "host configuration");

			HarnessCredentials.copyAllowedFiles(source, destination, Set.of("auth.json"));

			requireThat(Files.readString(destination.resolve("auth.json")), "credential").isEqualTo("credential");
			requireThat(Files.exists(destination.resolve("config.toml")), "hostConfiguration").isFalse();
		}
	}
}
