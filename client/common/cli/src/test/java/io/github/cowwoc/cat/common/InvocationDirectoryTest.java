package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

			Path directory = InvocationDirectory.create(temporaryRoot, "mutation-transaction");

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
			Path directory = InvocationDirectory.create(temporaryRoot, "mutation-transaction");
			Files.writeString(directory.resolve("receipt.txt"), "accepted\n");
			Path retained = temporaryRoot.resolve("retained/mutation-transaction-evidence");

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
			Path directory = InvocationDirectory.create(temporaryRoot, "mutation-transaction");
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

	/**
	 * Verifies {@code create} prints only the created directory's absolute path, so callers can use the output as a path.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void createPrintsBarePath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path temporaryRoot = project.resolve(".cat/work/temp");
			Output output = new Output();

			int status = InvocationDirectory.run(new String[]{"create", "mutation-transaction"}, temporaryRoot,
				output.out, output.err);

			requireThat(status, "status").isEqualTo(0);
			requireThat(output.err(), "err").isEmpty();
			List<String> lines = output.out().lines().toList();
			requireThat(lines, "lines").size().isEqualTo(1);
			Path directory = Path.of(lines.getFirst());
			requireThat(directory.isAbsolute(), "absolute").isTrue();
			requireThat(directory.getParent(), "directoryParent").isEqualTo(temporaryRoot.toAbsolutePath().normalize());
			requireThat(directory, "directory").isDirectory();
		}
	}

	/**
	 * Verifies {@code handoff} prints only the retained directory's absolute path.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void handoffPrintsBarePath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path temporaryRoot = project.resolve(".cat/work/temp");
			Path directory = InvocationDirectory.create(temporaryRoot, "mutation-transaction");
			Path retained = temporaryRoot.resolve("retained/evidence").toAbsolutePath().normalize();
			Output output = new Output();

			int status = InvocationDirectory.run(new String[]{"handoff", directory.toString(), retained.toString()},
				temporaryRoot, output.out, output.err);

			requireThat(status, "status").isEqualTo(0);
			requireThat(output.out(), "out").isEqualTo(retained + System.lineSeparator());
		}
	}

	/**
	 * Verifies {@code delete} prints nothing on success.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void deletePrintsNothing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("workflow-temp-test-"));
			Path temporaryRoot = project.resolve(".cat/work/temp");
			Path directory = InvocationDirectory.create(temporaryRoot, "mutation-transaction");
			Output output = new Output();

			int status = InvocationDirectory.run(new String[]{"delete", directory.toString()}, temporaryRoot,
				output.out, output.err);

			requireThat(status, "status").isEqualTo(0);
			requireThat(output.out(), "out").isEmpty();
			requireThat(Files.exists(directory), "directoryExists").isFalse();
		}
	}

	/**
	 * Verifies {@code --help} succeeds and states the standard output of every command.
	 */
	@Test
	public void helpDescribesEachCommandOutput()
	{
		Path unusedRoot = Path.of("unused");
		Output output = new Output();

		int status = InvocationDirectory.run(new String[]{"--help"}, unusedRoot, output.out, output.err);

		requireThat(status, "status").isEqualTo(0);
		requireThat(output.err(), "err").isEmpty();
		String help = output.out();
		requireThat(help, "help").contains("""
			  root
			      Creates the CAT temporary root if it is missing.
			      Output: the root's absolute path, on one line.
			""");
		requireThat(help, "help").contains("""
			  create <lowercase-workflow-name>
			      Creates a uniquely named directory beneath the CAT temporary root.
			      Output: the new directory's absolute path, on one line.
			""");
		requireThat(help, "help").contains("""
			  delete <directory>
			      Deletes a directory beneath the CAT temporary root, including its contents.
			      Output: none.
			""");
		requireThat(help, "help").contains("""
			  handoff <directory> <retained-directory-under-cat-temp>
			      Moves <directory> to an unoccupied location beneath the CAT temporary root.
			      Output: the retained directory's absolute path, on one line.
			""");
		requireThat(Files.exists(unusedRoot), "unusedRootExists").isFalse();
	}

	/**
	 * Verifies a malformed invocation prints the help to standard error, prints nothing to standard output, and fails.
	 */
	@Test
	public void usageErrorPrintsHelpToStandardError()
	{
		Output output = new Output();

		int status = InvocationDirectory.run(new String[]{"create"}, Path.of("unused"), output.out, output.err);

		requireThat(status, "status").isEqualTo(1);
		requireThat(output.out(), "out").isEmpty();
		requireThat(output.err(), "err").contains("Output: the new directory's absolute path, on one line.");
	}

	/**
	 * Captures a command's standard output and standard error.
	 */
	private static final class Output
	{
		private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		final PrintStream out = new PrintStream(outBytes, true, UTF_8);
		final PrintStream err = new PrintStream(errBytes, true, UTF_8);

		/**
		 * Returns the captured standard output.
		 *
		 * @return the standard output
		 */
		String out()
		{
			return outBytes.toString(UTF_8);
		}

		/**
		 * Returns the captured standard error.
		 *
		 * @return the standard error
		 */
		String err()
		{
			return errBytes.toString(UTF_8);
		}
	}
}
