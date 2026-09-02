package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link InvocationDirectory} workflow-directory behavior.
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
	 * Creates and deletes only a command-owned workflow directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void createsAndDeletesOwnedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = cleanup.add(Files.createTempDirectory("workflow-temp-"));

			Path directory = InvocationDirectory.create(temporaryRoot, "feedback");
			InvocationDirectory.delete(temporaryRoot, directory);

			requireThat(Files.exists(directory), "directoryExists").isFalse();
		}
	}

	/**
	 * Moves a completed workflow directory from temporary to retained CAT work storage.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void handsOffOwnedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("workflow-handoff-"));
			Path work = Files.createDirectory(parent.resolve("work"));
			Path directory = InvocationDirectory.create(Files.createDirectories(work.resolve("temp")), "feedback");
			Files.writeString(directory.resolve("draft.json"), "draft");
			Path retained = work.resolve("feedback/draft");

			Path result = InvocationDirectory.handoff(work, directory, retained);

			requireThat(result, "retainedDirectory").isEqualTo(retained);
			requireThat(Files.readString(retained.resolve("draft.json")), "retainedDraft").isEqualTo("draft");
		}
	}

	/**
	 * Rejects a direct temporary child that the workflow command did not create.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsUnownedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = cleanup.add(Files.createTempDirectory("workflow-temp-"));
			Path unowned = Files.createDirectory(temporaryRoot.resolve("unowned"));

			InvocationDirectory.delete(temporaryRoot, unowned);
		}
	}
}
