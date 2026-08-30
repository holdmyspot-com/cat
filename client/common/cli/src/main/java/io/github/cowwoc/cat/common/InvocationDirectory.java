package io.github.cowwoc.cat.common;

import java.io.IOException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Creates, hands off, and removes one owned invocation directory below CAT work storage.
 */
public final class InvocationDirectory
{
	private static final String USAGE = "Usage: workflow-temp root | create <lowercase-workflow-name> | " +
		"delete <directory> | handoff <directory> <retained-directory-under-cat-work> | " +
			"retain <direct-entry> <workflow-name> <last-consumer> | inventory";
	private static final String OWNER_FILE = ".cat-workflow-temp";
	private static final String ACTIVE_PROMPT_EVALUATION = ".cat-prompt-evaluation-active";
	private static final String PROMPT_EVALUATION_EVIDENCE = ".cat-prompt-evaluation-evidence";

	/**
	 * Runs one invocation-directory lifecycle operation.
	 *
	 * @param args a lifecycle operation and its arguments
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
				case "retain" -> retainCommand(args, scope);
				case "inventory" -> inventoryCommand(args, scope);
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
	 * Returns the CAT work root used by the workflow commands.
	 *
	 * @param workRoot the resolved CAT work root
	 * @return the normalized work root
	 */
	static Path root(Path workRoot)
	{
		return workRoot.toAbsolutePath().normalize();
	}

	/**
	 * Prints the temporary root for an ordinary shell caller.
	 *
	 * @param args the requested command arguments
	 * @param scope the current tool scope
	 */
	private static void rootCommand(String[] args, ToolScope scope)
	{
		if (args.length != 1)
			throw new IllegalArgumentException("Usage: workflow-temp root");
		System.out.println(root(scope.catWork()));
	}

	/**
	 * Creates one workflow-owned invocation directory beneath the supplied CAT work temp directory.
	 *
	 * @param temporaryRoot the resolved CAT work temp directory
	 * @param workflowName the lowercase workflow name
	 * @return the initially empty invocation directory
	 * @throws IOException if the temporary directory cannot be created
	 */
	public static Path create(Path temporaryRoot, String workflowName) throws IOException
	{
		assert temporaryRoot != null;
		assert workflowName != null;
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
	 * Deletes one completed workflow directory after verifying that this CLI created it below the supplied CAT root.
	 *
	 * @param temporaryRoot the resolved CAT work temp directory
	 * @param directory the owned invocation directory
	 * @throws IOException if the directory is not owned by this CLI or cannot be deleted
	 */
	public static void delete(Path temporaryRoot, Path directory) throws IOException
	{
		TemporaryDirectories.deleteRecursively(requireOwnedDirectory(temporaryRoot, directory));
	}

	/**
	 * Moves one completed workflow directory to a retained caller-selected location beneath the CAT work root.
	 *
	 * @param workRoot the resolved CAT work root
	 * @param directory the owned invocation directory
	 * @param durableDirectory the initially absent retained destination beneath the CAT work root
	 * @return the normalized retained destination
	 * @throws IOException if either path is unsafe or the directory cannot be moved
	 */
	static Path handoff(Path workRoot, Path directory, Path durableDirectory) throws IOException
	{
		Path temporaryRoot = temporaryRoot(workRoot);
		Path owned = requireOwnedDirectory(temporaryRoot, directory);
		if (containsActivePromptEvaluation(owned))
			throw new IOException("Cannot hand off a prompt evaluation before it reaches a terminal decision: " + owned);
		if (containsPromptEvaluationEvidence(owned))
		{
			throw new IOException("Prompt-evaluation evidence uses paths inside this directory, so moving it here would " +
				"make its retained record point to the old location. Use prompt-evaluation finalize to retain or delete the " +
				"terminal workflow instead: " + owned);
		}
		return moveDirectory(workRoot, owned, durableDirectory);
	}

	/**
	 * Moves one owned directory after its owning workflow has completed any workflow-specific validation and record
	 * updates. Uncommitted workflow evidence remains below the CAT work root even when a later workflow consumes it.
	 *
	 * @param workRoot the resolved CAT work root
	 * @param directory the owned invocation directory
	 * @param durableDirectory the initially absent retained destination beneath the CAT work root
	 * @return the normalized retained destination
	 * @throws IOException if either path is unsafe or the directory cannot be moved
	 */
	static Path moveOwned(Path workRoot, Path directory, Path durableDirectory) throws IOException
	{
		Path temporaryRoot = temporaryRoot(workRoot);
		Path owned = requireOwnedDirectory(temporaryRoot, directory);
		return moveDirectory(workRoot, owned, durableDirectory);
	}

	/**
	 * Moves a verified workflow directory to its retained destination.
	 *
	 * @param workRoot the resolved CAT work root
	 * @param owned the verified workflow directory
	 * @param durableDirectory the initially absent retained destination beneath the CAT work root, but outside its
	 *                         {@code temp} directory
	 * @return the normalized retained destination
	 * @throws IOException if either path is unsafe or the directory cannot be moved
	 */
	private static Path moveDirectory(Path workRoot, Path owned, Path durableDirectory) throws IOException
	{
		Path work = root(workRoot);
		Path temporaryRoot = temporaryRoot(work);
		Path destination = durableDirectory.toAbsolutePath().normalize();
		if (!destination.startsWith(work))
		{
			throw new IOException("Uncommitted workflow evidence must remain beneath the CAT work root: " +
				destination);
		}
		if (destination.startsWith(temporaryRoot))
		{
			throw new IOException("Retained workflow evidence cannot remain beneath the CAT work temp directory: " +
				destination);
		}
		if (destination.startsWith(owned))
			throw new IOException("Workflow evidence destination cannot be inside its source directory: " + destination);
		if (Files.exists(destination, NOFOLLOW_LINKS))
			throw new IOException("Retained workflow destination already exists: " + destination);
		Path parent = destination.getParent();
		if (parent == null)
			throw new IOException("Retained workflow destination has no parent: " + destination);
		Files.createDirectories(parent);
		return Files.move(owned, destination).toAbsolutePath().normalize();
	}

	/**
	 * Returns the child of CAT work storage that owns invocation-local temporary resources.
	 *
	 * @param workRoot the resolved CAT work root
	 * @return the normalized CAT work temp directory
	 */
	private static Path temporaryRoot(Path workRoot)
	{
		return root(workRoot).resolve("temp");
	}

	/**
	 * Detects an evaluator-owned marker that prohibits moving evidence while its record or native work remains live.
	 *
	 * @param directory the owned invocation directory
	 * @return whether the directory contains a live prompt-evaluation marker
	 * @throws IOException if the directory cannot be inspected
	 */
	private static boolean containsActivePromptEvaluation(Path directory) throws IOException
	{
		try (java.util.stream.Stream<Path> files = Files.walk(directory))
		{
			return files.anyMatch(path -> path.getFileName().toString().equals(ACTIVE_PROMPT_EVALUATION));
		}
	}

	/**
	 * Detects evaluator evidence that requires its specialized handoff command to rewrite retained paths.
	 *
	 * @param directory the owned invocation directory
	 * @return whether the directory contains prompt-evaluation evidence
	 * @throws IOException if the directory cannot be inspected
	 */
	private static boolean containsPromptEvaluationEvidence(Path directory) throws IOException
	{
		try (java.util.stream.Stream<Path> files = Files.walk(directory))
		{
			return files.anyMatch(path -> path.getFileName().toString().equals(PROMPT_EVALUATION_EVIDENCE));
		}
	}

	/**
	 * Runs the create operation.
	 *
	 * @param args command-line arguments
	 * @param scope the scope that owns the resolved CAT work root
	 * @throws IOException if the directory cannot be created
	 */
	private static void createCommand(String[] args, ToolScope scope) throws IOException
	{
		if (args.length != 2)
			throw new IllegalArgumentException("Usage: workflow-temp create <lowercase-workflow-name>");
		Path directory = create(scope.temporaryWorkRoot(), args[1]);
		System.out.print("directory=" + directory + "\n");
	}

	/**
	 * Runs the delete operation.
	 *
	 * @param args command-line arguments
	 * @param scope the scope that owns the resolved CAT work root
	 * @throws IOException if the directory cannot be deleted
	 */
	private static void deleteCommand(String[] args, ToolScope scope) throws IOException
	{
		if (args.length != 2)
			throw new IllegalArgumentException("Usage: workflow-temp delete <directory>");
		Path directory = Path.of(args[1]).toAbsolutePath().normalize();
		delete(scope.temporaryWorkRoot(), directory);
		System.out.print("deleted=" + directory + "\n");
	}

	/**
	 * Runs the handoff operation.
	 *
	 * @param args command-line arguments
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
		System.out.print("directory=" + destination + "\n");
	}

	/**
	 * Records why one direct durable CAT work entry remains after its creating invocation exits.
	 *
	 * @param args the retained entry, creator, and last consumer
	 * @param scope the scope that owns the CAT work root and JSON mapper
	 * @throws IOException if the requested entry cannot be retained safely
	 */
	private static void retainCommand(String[] args, ToolScope scope) throws IOException
	{
		if (args.length != 4)
			throw new IllegalArgumentException("Usage: workflow-temp retain <direct-entry> <workflow-name> <last-consumer>");
		Path retained = WorkflowWorkOwnership.retain(scope.catWork(), Path.of(args[1]), args[2], args[3], scope.json());
		System.out.print("retained=" + retained + "\n");
	}

	/**
	 * Prints recorded ownership and cleanup safety for CAT work entries without changing them.
	 *
	 * @param args the requested command arguments
	 * @param scope the scope that resolves CAT work and owns the JSON mapper
	 * @throws IOException if a recorded work entry cannot be inspected
	 */
	private static void inventoryCommand(String[] args, ToolScope scope) throws IOException
	{
		if (args.length != 1)
			throw new IllegalArgumentException("Usage: workflow-temp inventory");
		System.out.println(scope.json().writeValueAsString(WorkflowWorkInventory.inventory(scope.catWork(),
			scope.json())));
	}

	/**
	 * Verifies that a directory belongs to one workflow-temp invocation below the resolved CAT root.
	 *
	 * @param temporaryRoot the resolved CAT work temp directory
	 * @param directory the candidate invocation directory
	 * @return the normalized owned directory
	 * @throws IOException if the directory is not CLI-owned invocation state
	 */
	private static Path requireOwnedDirectory(Path temporaryRoot, Path directory) throws IOException
	{
		assert temporaryRoot != null;
		assert directory != null;
		Path root = temporaryRoot.toAbsolutePath().normalize();
		Path owned = directory.toAbsolutePath().normalize();
		if (!owned.getParent().equals(root) || !Files.isDirectory(owned, NOFOLLOW_LINKS))
			throw new IOException("Workflow temporary directory is not a direct child of the CAT work temp directory: " +
				owned);
		Path owner = owned.resolve(OWNER_FILE);
		if (!Files.isRegularFile(owner, NOFOLLOW_LINKS) ||
			!Files.readString(owner, UTF_8).strip().matches("[a-z][a-z0-9-]*"))
		{
			throw new IOException("Workflow temporary directory is not owned by workflow-temp: " + owned);
		}
		return owned;
	}

	/**
	 * Locates the workflow-temp invocation that owns a descendant without crossing a symbolic-link parent.
	 *
	 * @param temporaryRoot the resolved CAT work temp directory
	 * @param directory the candidate descendant
	 * @return the normalized owning workflow invocation
	 * @throws IOException if the directory is not inside an owned workflow invocation
	 */
	public static Path owningDirectory(Path temporaryRoot, Path directory) throws IOException
	{
		return ownedInvocation(temporaryRoot, directory).orElseThrow(() -> new IOException(
			"Workflow temporary directory is not owned by workflow-temp: " + directory.toAbsolutePath().normalize()));
	}

	/**
	 * Finds the workflow-temp invocation that owns a descendant when it has one.
	 *
	 * @param temporaryRoot the resolved CAT work temp directory
	 * @param directory the candidate descendant
	 * @return the owning invocation, or an empty result when no workflow-temp marker owns the direct child
	 * @throws IOException if the candidate is outside the temporary root or has an unsafe parent
	 */
	public static Optional<Path> ownedInvocation(Path temporaryRoot, Path directory) throws IOException
	{
		assert temporaryRoot != null;
		assert directory != null;
		Path root = temporaryRoot.toAbsolutePath().normalize();
		Path descendant = directory.toAbsolutePath().normalize();
		if (!descendant.startsWith(root) || descendant.equals(root) || !Files.isDirectory(descendant, NOFOLLOW_LINKS))
		{
			throw new IOException("Workflow temporary directory is not beneath the CAT work temp directory: " +
				descendant);
		}
		Path invocation = descendant;
		while (!invocation.getParent().equals(root))
		{
			if (Files.isSymbolicLink(invocation) || !Files.isDirectory(invocation, NOFOLLOW_LINKS))
				throw new IOException("Workflow temporary directory has an unsafe parent: " + invocation);
			invocation = invocation.getParent();
		}
		Path owner = invocation.resolve(OWNER_FILE);
		if (!Files.isRegularFile(owner, NOFOLLOW_LINKS) ||
			!Files.readString(owner, UTF_8).strip().matches("[a-z][a-z0-9-]*"))
		{
			return Optional.empty();
		}
		return Optional.of(invocation);
	}

	/**
	 * Prevents instantiation.
	 */
	private InvocationDirectory()
	{
	}
}
