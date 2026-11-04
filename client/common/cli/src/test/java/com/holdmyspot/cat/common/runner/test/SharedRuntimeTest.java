package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.SharedRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests whether the shared Java runtime satisfies an application's runtime requirement, as a downloaded runner bundle
 * is checked before it is bound to the runtime.
 */
public final class SharedRuntimeTest
{
	/**
	 * The description of the fixture runtime: Java 27 with two modules.
	 */
	private static final String DESCRIPTION = """
		{"format": 1, "java_version": "27.0.1", "java_feature": 27, "modules": ["java.base", "java.net.http"]}
		""";

	/**
	 * Creates the test.
	 */
	public SharedRuntimeTest()
	{
	}

	/**
	 * A runtime whose feature release and modules cover the requirement satisfies it.
	 *
	 * @throws IOException if the fixture cannot be written or the runtime is refused
	 */
	@Test
	public void acceptsSatisfiedRequirement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("shared-runtime-accepted-"));

			SharedRuntime.requireSatisfies(runtime(root), requirement(root, """
				{"format": 1, "java_feature": 26, "modules": ["java.net.http"]}"""));
		}
	}

	/**
	 * A runtime with a lower feature release than required, or without a required module, does not satisfy the
	 * requirement, and the failure says so.
	 *
	 * @param requirement the requirement's content
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "unsatisfied")
	public void refusesUnsatisfiedRequirement(String requirement) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("shared-runtime-unsatisfied-"));

			IOException failure = expectRefusal(runtime(root), requirement(root, requirement));

			requireThat(failure.getMessage(), "message").contains("does not satisfy");
		}
	}

	/**
	 * Returns requirements that the fixture runtime does not satisfy.
	 *
	 * @return each requirement's content
	 */
	@DataProvider
	public static Object[][] unsatisfied()
	{
		return new Object[][]{
			{"{\"format\": 1, \"java_feature\": 28, \"modules\": [\"java.base\"]}"},
			{"{\"format\": 1, \"java_feature\": 27, \"modules\": [\"java.base\", \"jdk.unavailable\"]}"}};
	}

	/**
	 * A requirement that the updater would refuse before activating a plugin is refused for a runner bundle too: one
	 * that repeats a member, holds content after its object, or lists a module twice.
	 *
	 * @param requirement the requirement's content
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "malformed")
	public void refusesMalformedRequirement(String requirement) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("shared-runtime-malformed-"));
			Path file = requirement(root, requirement);

			IOException failure = expectRefusal(runtime(root), file);

			requireThat(failure.getMessage(), "message").contains(file.toString());
		}
	}

	/**
	 * Returns malformed requirements whose feature release and modules the fixture runtime would satisfy.
	 *
	 * @return each requirement's content
	 */
	@DataProvider
	public static Object[][] malformed()
	{
		return new Object[][]{
			{"{\"format\": 1, \"java_feature\": 27, \"java_feature\": 27, \"modules\": [\"java.base\"]}"},
			{"{\"format\": 1, \"java_feature\": 27, \"modules\": [\"java.base\"]} {}"},
			{"{\"format\": 1, \"java_feature\": 27, \"modules\": [\"java.base\", \"java.base\"]}"}};
	}

	/**
	 * Requires the runtime check to refuse a requirement.
	 *
	 * @param runtime     the runtime directory
	 * @param requirement the requirement file
	 * @return the refusal
	 */
	private static IOException expectRefusal(Path runtime, Path requirement)
	{
		try
		{
			SharedRuntime.requireSatisfies(runtime, requirement);
		}
		catch (IOException e)
		{
			return e;
		}
		throw new AssertionError("The runtime check accepted " + requirement);
	}

	/**
	 * Creates the fixture runtime, which holds only its description.
	 *
	 * @param root the test directory
	 * @return the runtime directory
	 * @throws IOException if the runtime cannot be written
	 */
	private static Path runtime(Path root) throws IOException
	{
		Path runtime = Files.createDirectories(root.resolve("runtime"));
		Files.writeString(runtime.resolve(SharedRuntime.DESCRIPTION), DESCRIPTION);
		return runtime;
	}

	/**
	 * Writes a requirement file.
	 *
	 * @param root    the test directory
	 * @param content the requirement's content
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	private static Path requirement(Path root, String content) throws IOException
	{
		return Files.writeString(root.resolve("runtime-requirement.json"), content);
	}
}
