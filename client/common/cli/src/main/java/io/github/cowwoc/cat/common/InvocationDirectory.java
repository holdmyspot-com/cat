package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns caller-visible temporary directories used by CAT workflows.
 */
public final class InvocationDirectory
{
	private static final String USAGE = """
		Usage: workflow-temp <command> [arguments]

		Manages workflow directories beneath the CAT temporary root, .cat/work/temp in the enclosing CAT project.

		Commands:
		  root
		      Creates the CAT temporary root if it is missing.
		      Output: the root's absolute path, on one line.
		  create <lowercase-workflow-name>
		      Creates a uniquely named directory beneath the CAT temporary root.
		      Output: the new directory's absolute path, on one line.
		  delete <directory>
		      Deletes a directory beneath the CAT temporary root, including its contents.
		      Output: none.
		  handoff <directory> <retained-directory-under-cat-temp>
		      Moves <directory> to an unoccupied location beneath the CAT temporary root.
		      Output: the retained directory's absolute path, on one line.
		  --help
		      Output: this help.

		Each printed path has no prefix, so callers can use the output directly as a path. On failure, a command prints
		a diagnostic to standard error, prints nothing to standard output, and exits with status 1.""";

	/**
	 * Runs one workflow temporary-directory operation and prints its result.
	 *
	 * @param args the operation and its arguments
	 */
	public static void main(String[] args)
	{
		int status;
		try (ToolScope scope = new ToolScope())
		{
			status = run(args, scope.catTemp(), System.out, System.err);
		}
		if (status != 0)
			System.exit(status);
	}

	/**
	 * Runs one workflow temporary-directory operation.
	 *
	 * @param args the operation and its arguments
	 * @param temporaryRoot the CAT temporary root resolved by the invocation scope
	 * @param out receives the operation's result
	 * @param err receives diagnostics
	 * @return the process exit status
	 */
	static int run(String[] args, Path temporaryRoot, PrintStream out, PrintStream err)
	{
		try
		{
			if (args.length == 0)
				throw new IllegalArgumentException(USAGE);
			if (args.length == 1 && args[0].equals("--help"))
			{
				out.println(USAGE);
				return 0;
			}
			Command command = Command.parse(args[0]);
			switch (command)
			{
				case ROOT ->
				{
					requireArgumentCount(args, 1);
					out.println(root(temporaryRoot));
				}
				case CREATE ->
				{
					requireArgumentCount(args, 2);
					out.println(create(temporaryRoot, args[1]));
				}
				case DELETE ->
				{
					requireArgumentCount(args, 2);
					delete(temporaryRoot, Path.of(args[1]));
				}
				case HANDOFF ->
				{
					requireArgumentCount(args, 3);
					out.println(handoff(temporaryRoot, Path.of(args[1]), Path.of(args[2])));
				}
			}
			return 0;
		}
		catch (IllegalArgumentException | IOException e)
		{
			err.println(e.getMessage());
			return 1;
		}
	}

	/**
	 * Returns the normalized caller-visible temporary root, creating it when necessary.
	 *
	 * @param temporaryRoot the CAT temporary root resolved by the invocation scope
	 * @return the normalized temporary root
	 * @throws IOException if the directory cannot be created
	 */
	static Path root(Path temporaryRoot) throws IOException
	{
		Path normalizedRoot = temporaryRoot.toAbsolutePath().normalize();
		// A caller's project path may use an alias (including macOS /var); CAT owns only the .cat/work/temp suffix.
		Path component = normalizedRoot;
		for (int depth = 0; depth < 3 && component != null; ++depth)
		{
			if (Files.isSymbolicLink(component))
				throw new IllegalArgumentException("Temporary root traverses a symbolic link: " + component);
			component = component.getParent();
		}
		Files.createDirectories(normalizedRoot);
		return normalizedRoot;
	}

	/**
	 * Creates a uniquely named workflow directory beneath the caller-visible temporary root.
	 *
	 * @param temporaryRoot the CAT temporary root resolved by the invocation scope
	 * @param workflowName the lowercase workflow name
	 * @return the created directory
	 * @throws IllegalArgumentException if {@code workflowName} is invalid
	 * @throws IOException if the directory cannot be created
	 */
	static Path create(Path temporaryRoot, String workflowName) throws IOException
	{
		if (workflowName == null || !workflowName.matches("[a-z][a-z0-9-]*"))
			throw new IllegalArgumentException("Workflow name must match [a-z][a-z0-9-]*: " + workflowName);
		Path normalizedRoot = root(temporaryRoot);
		return Files.createTempDirectory(normalizedRoot, workflowName + "-").toAbsolutePath().normalize();
	}

	/**
	 * Deletes one workflow directory beneath the caller-visible temporary root.
	 *
	 * @param temporaryRoot the CAT temporary root resolved by the invocation scope
	 * @param directory the workflow directory to delete
	 * @throws IllegalArgumentException if {@code directory} is outside the temporary root or is not a directory
	 * @throws IOException if the directory cannot be inspected or deleted
	 */
	static void delete(Path temporaryRoot, Path directory) throws IOException
	{
		Path normalizedRoot = root(temporaryRoot);
		Path managedDirectory = requireManagedDirectory(normalizedRoot, directory, "directory");
		TemporaryDirectories.deleteRecursively(managedDirectory);
	}

	/**
	 * Moves one workflow directory to a retained location beneath the same caller-visible temporary root.
	 *
	 * @param temporaryRoot the CAT temporary root resolved by the invocation scope
	 * @param directory the workflow directory to retain
	 * @param retainedDirectory the unoccupied destination beneath the CAT temporary root
	 * @return the retained directory
	 * @throws IllegalArgumentException if a path is outside the temporary root, unsafe, or already occupied
	 * @throws IOException if the directory cannot be inspected or moved
	 */
	static Path handoff(Path temporaryRoot, Path directory, Path retainedDirectory) throws IOException
	{
		Path normalizedRoot = root(temporaryRoot);
		Path managedDirectory = requireManagedDirectory(normalizedRoot, directory, "directory");
		Path destination = requireManagedPath(normalizedRoot, retainedDirectory, "retainedDirectory");
		if (Files.exists(destination))
			throw new IllegalArgumentException("Retained directory already exists: " + destination);
		createManagedParents(normalizedRoot, destination.getParent());

		return Files.move(managedDirectory, destination).toAbsolutePath().normalize();
	}

	/**
	 * Validates one existing CAT-managed workflow directory.
	 *
	 * @param temporaryRoot the normalized CAT temporary root
	 * @param directory the candidate directory
	 * @param parameterName the candidate's caller-facing name
	 * @return the normalized directory
	 * @throws IllegalArgumentException if the path is not a safe managed directory
	 */
	private static Path requireManagedDirectory(Path temporaryRoot, Path directory, String parameterName)
	{
		Path normalized = requireManagedPath(temporaryRoot, directory, parameterName);
		if (!Files.isDirectory(normalized))
			throw new IllegalArgumentException(parameterName + " is not a directory: " + normalized);
		return normalized;
	}

	/**
	 * Validates one path beneath the CAT temporary root without symbolic-link traversal.
	 *
	 * @param temporaryRoot the normalized CAT temporary root
	 * @param path the candidate path
	 * @param parameterName the candidate's caller-facing name
	 * @return the normalized path
	 * @throws IllegalArgumentException if the path is outside the root or traverses a symbolic link
	 */
	private static Path requireManagedPath(Path temporaryRoot, Path path, String parameterName)
	{
		if (path == null)
			throw new NullPointerException(parameterName + " may not be null");
		Path normalized = path.toAbsolutePath().normalize();
		if (normalized.equals(temporaryRoot) || !normalized.startsWith(temporaryRoot))
			throw new IllegalArgumentException(parameterName + " must be below " + temporaryRoot + ": " + normalized);
		Path current = temporaryRoot;
		for (Path component : temporaryRoot.relativize(normalized))
		{
			current = current.resolve(component);
			if (Files.isSymbolicLink(current))
				throw new IllegalArgumentException(parameterName + " traverses a symbolic link: " + current);
		}
		return normalized;
	}

	/**
	 * Creates missing destination parents after verifying every existing component is safe.
	 *
	 * @param temporaryRoot the normalized CAT temporary root
	 * @param parent the destination parent
	 * @throws IOException if a parent cannot be created
	 */
	private static void createManagedParents(Path temporaryRoot, Path parent) throws IOException
	{
		Path current = temporaryRoot;
		for (Path component : temporaryRoot.relativize(parent))
		{
			current = current.resolve(component);
			if (Files.exists(current))
			{
				if (Files.isSymbolicLink(current) || !Files.isDirectory(current))
					throw new IllegalArgumentException("Retained directory parent is unsafe: " + current);
				continue;
			}
			Files.createDirectory(current);
		}
	}

	/**
	 * Requires the exact argument count for an operation.
	 *
	 * @param args the supplied command-line arguments
	 * @param expected the required argument count
	 * @throws IllegalArgumentException if the count differs
	 */
	private static void requireArgumentCount(String[] args, int expected)
	{
		if (args.length != expected)
			throw new IllegalArgumentException(USAGE);
	}

	/**
	 * Prevents instantiation.
	 */
	private InvocationDirectory()
	{
	}

	/**
	 * A supported workflow temporary-directory operation.
	 */
	private enum Command
	{
		ROOT,
		CREATE,
		DELETE,
		HANDOFF;

		/**
		 * Parses the untyped command-line operation once.
		 *
		 * @param value the operation name
		 * @return the constrained operation
		 * @throws IllegalArgumentException if {@code value} is unsupported
		 */
		private static Command parse(String value)
		{
			return switch (value)
			{
				case "root" -> ROOT;
				case "create" -> CREATE;
				case "delete" -> DELETE;
				case "handoff" -> HANDOFF;
				default -> throw new IllegalArgumentException(USAGE);
			};
		}
	}
}
