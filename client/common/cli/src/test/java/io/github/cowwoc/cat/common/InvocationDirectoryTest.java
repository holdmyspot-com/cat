package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link InvocationDirectory}.
 */
public final class InvocationDirectoryTest
{
	/**
	 * Creates the test fixture.
	 */
	public InvocationDirectoryTest()
	{
	}

	/**
	 * Verifies a workflow directory remains in the caller-visible CAT temporary root.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void createsDirectoryInCallerVisibleRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path temporaryRoot = project.resolve(".cat/work/temp");

			Path directory = InvocationDirectory.create(temporaryRoot, "prompt-optimizer");

			requireThat(directory.getParent(), "directoryParent").isEqualTo(temporaryRoot.toAbsolutePath().normalize());
			requireThat(directory, "directory").isDirectory();
		}
	}

	/**
	 * Verifies handoff preserves workflow contents at the requested retained location.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void handsOffDirectoryWithinTemporaryRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path temporaryRoot = project.resolve(".cat/work/temp");
			Path directory = InvocationDirectory.create(temporaryRoot, "prompt-optimizer");
			Files.writeString(directory.resolve("receipt.txt"), "accepted\n");
			Path retained = temporaryRoot.resolve("retained/prompt-optimizer-evidence");

			Path result = InvocationDirectory.handoff(temporaryRoot, directory, retained);

			requireThat(result, "result").isEqualTo(retained.toAbsolutePath().normalize());
			requireThat(Files.exists(directory), "sourceExists").isFalse();
			requireThat(Files.readString(retained.resolve("receipt.txt")), "retainedReceipt").isEqualTo("accepted\n");
		}
	}

	/**
	 * Verifies deletion removes only the selected managed workflow directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void deletesManagedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path temporaryRoot = project.resolve(".cat/work/temp");
			Path directory = InvocationDirectory.create(temporaryRoot, "prompt-optimizer");
			Files.writeString(directory.resolve("receipt.txt"), "temporary\n");

			InvocationDirectory.delete(temporaryRoot, directory);

			requireThat(Files.exists(directory), "directoryExists").isFalse();
			requireThat(temporaryRoot, "temporaryRoot").isDirectory();
		}
	}

	/**
	 * Verifies deletion rejects a directory outside the CAT temporary root.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*must be below.*")
	public void rejectsDeletionOutsideTemporaryRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path outside = Files.createDirectory(project.resolve("outside"));

			InvocationDirectory.delete(project.resolve(".cat/work/temp"), outside);
		}
	}

	/**
	 * Allows a caller-visible project path to use a symbolic-link ancestor outside CAT's owned root.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void permitsSymbolicAncestorAboveProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path fixture = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path project = Files.createDirectory(fixture.resolve("project"));
			Path alias = Files.createSymbolicLink(fixture.resolve("alias"), project);

			Path temporaryRoot = InvocationDirectory.root(alias.resolve(".cat/work/temp"));

			requireThat(temporaryRoot, "temporaryRoot").isDirectory();
		}
	}

	/**
	 * Rejects a temporary root when CAT's own directory redirects through a symbolic link.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void rejectsSymbolicTemporaryRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path real = Files.createDirectory(project.resolve("real"));
			Path link = Files.createSymbolicLink(project.resolve(".cat"), real);

			InvocationDirectory.root(link.resolve("work/temp"));
		}
	}
}
