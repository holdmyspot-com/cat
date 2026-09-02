package io.github.cowwoc.cat.common;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates, hands off, and removes one workflow-owned directory below CAT work storage.
 */
public final class InvocationDirectory
{
	private static final String USAGE = "Usage: workflow-temp root | create <lowercase-workflow-name> | " +
		"delete <directory> | handoff <directory> <retained-directory-under-cat-work>";
	private static final String OWNER_FILE = ".cat-workflow-temp";

	/**
	 * Runs one workflow-directory operation.
	 *
	 * @param args an operation and its arguments
	 */
	public static void main(String[] args)
	{
		try (ToolScope scope = new ToolScope())
		{
			if (args.length == 0)
				throw new IllegalArgumentException(USAGE);
			switch (args[0])
			{
				case "root" -> rootCommand(args, scope);
				case "create" -> createCommand(args, scope);
				case "delete" -> deleteCommand(args, scope);
				case "handoff" -> handoffCommand(args, scope);
				default -> throw new IllegalArgumentException(USAGE);
			}
		}
		catch (IllegalArgumentException | IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Returns the CAT work root used by workflow commands.
	 *
	 * @param workRoot the resolved CAT work root
	 * @return the normalized work root
	 */
	static Path root(Path workRoot)
	{
		return workRoot.toAbsolutePath().normalize();
	}

	/**
	 * Creates one workflow-owned directory below CAT work's temporary directory.
	 *
	 * @param temporaryRoot the resolved CAT work temporary directory
	 * @param workflowName the lowercase workflow name
	 * @return the initially empty workflow directory
	 * @throws IOException if the directory cannot be created
	 */
	static Path create(Path temporaryRoot, String workflowName) throws IOException
	{
		if (!workflowName.matches("[a-z][a-z0-9-]*"))
		{
			throw new IllegalArgumentException("Workflow name must use lowercase letters, digits, and hyphens: " +
				workflowName);
		}
		Path root = temporaryRoot.toAbsolutePath().normalize();
		Files.createDirectories(root);
		Path directory = Files.createTempDirectory(root, workflowName + "-").toAbsolutePath().normalize();
		try
		{
			Files.writeString(directory.resolve(OWNER_FILE), workflowName + "\n", UTF_8);
			return directory;
		}
		catch (IOException e)
		{
			TemporaryDirectories.deleteRecursively(directory);
			throw e;
		}
	}

	/**
	 * Deletes one completed workflow directory after verifying that this command created it.
	 *
	 * @param temporaryRoot the resolved CAT work temporary directory
	 * @param directory the workflow directory
	 * @throws IOException if the directory is not command-owned or cannot be deleted
	 */
	static void delete(Path temporaryRoot, Path directory) throws IOException
	{
		TemporaryDirectories.deleteRecursively(requireOwnedDirectory(temporaryRoot, directory));
	}

	/**
	 * Moves one completed workflow directory to a caller-selected location beneath CAT work.
	 *
	 * @param workRoot the resolved CAT work root
	 * @param directory the workflow directory
	 * @param durableDirectory the initially absent retained destination below CAT work
	 * @return the normalized retained destination
	 * @throws IOException if a path is unsafe or the directory cannot be moved
	 */
	static Path handoff(Path workRoot, Path directory, Path durableDirectory) throws IOException
	{
		Path work = root(workRoot);
		Path temporaryRoot = work.resolve("temp");
		Path owned = requireOwnedDirectory(temporaryRoot, directory);
		Path destination = durableDirectory.toAbsolutePath().normalize();
		if (!destination.startsWith(work))
			throw new IOException("Workflow evidence must remain beneath the CAT work root: " + destination);
		if (destination.startsWith(temporaryRoot))
		{
			throw new IOException("Retained workflow evidence cannot remain beneath the CAT work temp directory: " +
				destination);
		}
		if (destination.startsWith(owned))
		{
			throw new IOException("Workflow evidence destination cannot be inside its source directory: " + destination);
		}
		if (Files.exists(destination, NOFOLLOW_LINKS))
			throw new IOException("Retained workflow destination already exists: " + destination);
		Path parent = destination.getParent();
		if (parent == null)
			throw new IOException("Retained workflow destination has no parent: " + destination);
		Files.createDirectories(parent);
		return Files.move(owned, destination).toAbsolutePath().normalize();
	}

	/**
	 * Prints the CAT work root.
	 *
	 * @param args the requested command arguments
	 * @param scope the scope that owns the resolved CAT work root
	 */
	private static void rootCommand(String[] args, ToolScope scope)
	{
		if (args.length != 1)
			throw new IllegalArgumentException("Usage: workflow-temp root");
		System.out.println(root(scope.catWork()));
	}

	/**
	 * Creates one workflow directory.
	 *
	 * @param args the requested command arguments
	 * @param scope the scope that owns the resolved CAT work root
	 * @throws IOException if the directory cannot be created
	 */
	private static void createCommand(String[] args, ToolScope scope) throws IOException
	{
		if (args.length != 2)
			throw new IllegalArgumentException("Usage: workflow-temp create <lowercase-workflow-name>");
		System.out.println("directory=" + create(scope.temporaryWorkRoot(), args[1]));
	}

	/**
	 * Deletes one workflow directory.
	 *
	 * @param args the requested command arguments
	 * @param scope the scope that owns the resolved CAT work root
	 * @throws IOException if the directory cannot be deleted
	 */
	private static void deleteCommand(String[] args, ToolScope scope) throws IOException
	{
		if (args.length != 2)
			throw new IllegalArgumentException("Usage: workflow-temp delete <directory>");
		Path directory = Path.of(args[1]).toAbsolutePath().normalize();
		delete(scope.temporaryWorkRoot(), directory);
		System.out.println("deleted=" + directory);
	}

	/**
	 * Hands off one workflow directory.
	 *
	 * @param args the requested command arguments
	 * @param scope the scope that owns the resolved CAT work root
	 * @throws IOException if the directory cannot be handed off
	 */
	private static void handoffCommand(String[] args, ToolScope scope) throws IOException
	{
		if (args.length != 3)
		{
			throw new IllegalArgumentException("Usage: workflow-temp handoff <directory> " +
				"<retained-directory-under-cat-work>");
		}
		Path directory = Path.of(args[1]).toAbsolutePath().normalize();
		Path destination = handoff(scope.catWork(), directory, Path.of(args[2]));
		System.out.println("directory=" + destination);
	}

	/**
	 * Verifies that a directory belongs to one direct workflow invocation.
	 *
	 * @param temporaryRoot the resolved CAT work temporary directory
	 * @param directory the candidate workflow directory
	 * @return the normalized owned directory
	 * @throws IOException if the directory is not command-owned workflow state
	 */
	private static Path requireOwnedDirectory(Path temporaryRoot, Path directory) throws IOException
	{
		Path root = temporaryRoot.toAbsolutePath().normalize();
		Path owned = directory.toAbsolutePath().normalize();
		if (!owned.getParent().equals(root) || !Files.isDirectory(owned, NOFOLLOW_LINKS))
		{
			throw new IOException("Workflow temporary directory is not a direct child of the CAT work temp directory: " +
				owned);
		}
		Path owner = owned.resolve(OWNER_FILE);
		if (!Files.isRegularFile(owner, NOFOLLOW_LINKS) ||
			!Files.readString(owner, UTF_8).strip().matches("[a-z][a-z0-9-]*"))
		{
			throw new IOException("Workflow temporary directory is not owned by workflow-temp: " + owned);
		}
		return owned;
	}

	/**
	 * Prevents construction of this static command owner.
	 */
	private InvocationDirectory()
	{
	}
}
