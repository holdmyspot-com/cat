package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles.WorkspaceEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads the {@value PromptTestFiles#WORKSPACE} directory of one prompt test. A file of the directory, or a file of the
 * project that a link reaches, becomes a workspace file at its path below {@value PromptTestFiles#WORKSPACE}. A link of
 * the directory whose target lies inside the directory stays a link; every other link is replaced by its target.
 * <p>
 * The prompt test may lie in a draft directory, which holds files at their paths relative to the project. A link
 * resolves at the path where it lies relative to the draft directory: a target that the draft directory holds is read
 * from it, and any other target from the project.
 */
final class WorkspaceReader
{
	/**
	 * The project, which holds every target that {@link #tree} does not, and which problems name files relative to.
	 */
	private final Path project;
	/**
	 * The directory that holds the prompt test at its path relative to the project: the project or a draft directory.
	 */
	private final Path tree;
	/**
	 * The test root that holds the prompt test, relative to {@link #tree}, or an empty path if the tree is the root.
	 */
	private final Path root;
	/**
	 * The prompt test's {@value PromptTestFiles#WORKSPACE} directory relative to the project, or {@code null} before
	 * {@link #read(Path)}.
	 */
	private Path workspace;
	/**
	 * The names of the prompt test's variables, which a workspace file's content may name.
	 */
	private final Set<String> declared;
	/**
	 * The workspace files read so far, by path.
	 */
	private final Map<String, WorkspaceEntry> workspaceFiles = new TreeMap<>();
	/**
	 * The file that each workspace file is copied from, by the workspace file's path.
	 */
	private final Map<String, Path> sources = new TreeMap<>();
	/**
	 * The files and directories inside a test root that links reach, relative to the project.
	 */
	private final Set<Path> linkedTargets = new LinkedHashSet<>();
	/**
	 * The target of each link of the prompt test's own {@value PromptTestFiles#WORKSPACE} whose target lies outside
	 * it, by the link's path below {@value PromptTestFiles#WORKSPACE}, with {@code /} separators.
	 */
	private final Map<String, String> copiedLinks = new TreeMap<>();

	/**
	 * Creates a reader.
	 *
	 * @param project  the project, which holds every target that {@code tree} does not, and which problems name files
	 *                 relative to
	 * @param tree     the directory that holds the prompt test at its path relative to the project: the project or a
	 *                 draft directory
	 * @param root     the test root that holds the prompt test, at or below {@code tree}
	 * @param declared the names of the prompt test's variables
	 */
	WorkspaceReader(Path project, Path tree, Path root, Set<String> declared)
	{
		assert root.startsWith(tree) : "root: " + root + ", tree: " + tree;
		this.project = project;
		this.tree = tree;
		this.root = tree.relativize(root);
		this.declared = declared;
	}

	/**
	 * Reads the {@value PromptTestFiles#WORKSPACE} directory of a prompt test.
	 *
	 * @param directory the prompt test directory, below the tree
	 * @throws IOException             if a file cannot be read
	 * @throws ScenarioFormatException if {@value PromptTestFiles#WORKSPACE} is not a directory, holds a special file,
	 *                                 or holds a link that is absolute, whose target is missing or lies outside the
	 *                                 project, whose target directory holds the link, that forms a cycle, or that
	 *                                 reaches a prompt test directory
	 */
	void read(Path directory) throws IOException, ScenarioFormatException
	{
		Path workspace = directory.resolve(PromptTestFiles.WORKSPACE);
		if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS))
		{
			throw new ScenarioFormatException(PromptTestFiles.display(project, workspace) + ": " +
				PromptTestFiles.WORKSPACE + " must be a directory");
		}
		this.workspace = tree.relativize(workspace);
		walk(workspace, this.workspace, "", new ArrayDeque<>(), null);
	}

	/**
	 * Returns the workspace files.
	 *
	 * @return the workspace files, sorted by path
	 */
	List<WorkspaceEntry> workspaceFiles()
	{
		return List.copyOf(workspaceFiles.values());
	}

	/**
	 * Returns the file that each workspace file is copied from.
	 *
	 * @return each file, by the workspace file's path
	 */
	Map<String, Path> sources()
	{
		return Map.copyOf(sources);
	}

	/**
	 * Returns the target of each link of the prompt test's own {@value PromptTestFiles#WORKSPACE} whose target lies
	 * outside it, so that the link's workspace files are copies of what it reaches.
	 *
	 * @return each target, relative to the link's directory, by the link's path below
	 *   {@value PromptTestFiles#WORKSPACE}; paths and targets have {@code /} separators
	 */
	Map<String, String> copiedLinks()
	{
		return Map.copyOf(copiedLinks);
	}

	/**
	 * Returns the files and directories inside a test root that links reach.
	 *
	 * @return the targets, relative to the project, in the order that they were reached
	 */
	Set<Path> linkedTargets()
	{
		return Set.copyOf(linkedTargets);
	}

	/**
	 * Reads the entries of a directory.
	 *
	 * @param directory     the directory
	 * @param relative      the directory's path relative to the project
	 * @param workspacePath the directory's path below {@value PromptTestFiles#WORKSPACE}, or an empty string for
	 *                      {@value PromptTestFiles#WORKSPACE} itself
	 * @param expanding     the linked directories whose entries are being read, relative to the project
	 * @param origin        the link whose target the directory is or lies in, or {@code null} for the prompt test's own
	 *                      {@value PromptTestFiles#WORKSPACE}
	 * @throws IOException             if a file cannot be read
	 * @throws ScenarioFormatException if an entry is refused
	 */
	private void walk(Path directory, Path relative, String workspacePath, Deque<Path> expanding, Path origin)
		throws IOException, ScenarioFormatException
	{
		List<Path> entries;
		try (Stream<Path> list = Files.list(directory))
		{
			entries = new ArrayList<>(list.toList());
		}
		entries.sort(null);
		for (Path entry : entries)
		{
			String name = entry.getFileName().toString();
			String path = name;
			if (!workspacePath.isEmpty())
				path = workspacePath + "/" + name;
			Path entryRelative = relative.resolve(name);
			BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
			if (attributes.isDirectory())
				walk(entry, entryRelative, path, expanding, origin);
			else if (attributes.isSymbolicLink())
			{
				if (origin != null)
					resolve(entry, entryRelative, path, expanding);
				else if (!addInternalLink(entry, entryRelative, path))
				{
					resolve(entry, entryRelative, path, expanding);
					copiedLinks.put(path, PortablePaths.text(Files.readSymbolicLink(entry)));
				}
			}
			else if (!attributes.isRegularFile())
				throw problem(entry, "a workspace file must be a regular file, a directory or a symbolic link");
			else if (origin != null && name.equals(PromptTestFiles.SCENARIO))
			{
				throw problem(origin, "the link reaches the prompt test directory " +
					PromptTestFiles.display(project, directory) + "; a trial may not receive a prompt test's files");
			}
			else
				addWorkspaceFile(path, entry);
		}
	}

	/**
	 * Adds a link of the prompt test's {@value PromptTestFiles#WORKSPACE} as a workspace file if its target lies inside
	 * {@value PromptTestFiles#WORKSPACE}.
	 *
	 * @param link          the link
	 * @param relative      the link's path relative to the project
	 * @param workspacePath the link's path below {@value PromptTestFiles#WORKSPACE}
	 * @return {@code false} if the link's target lies outside {@value PromptTestFiles#WORKSPACE} or is
	 *   {@value PromptTestFiles#WORKSPACE} itself, so that the link is replaced by its target
	 * @throws IOException             if a file cannot be read
	 * @throws ScenarioFormatException if the link's target is missing, or the link leads through links inside
	 *                                 {@value PromptTestFiles#WORKSPACE} back to one of them
	 */
	private boolean addInternalLink(Path link, Path relative, String workspacePath)
		throws IOException, ScenarioFormatException
	{
		Path first = internalTarget(relative);
		if (first == null)
			return false;
		Set<Path> chain = new HashSet<>();
		for (Path target = first; target != null; target = internalTarget(target))
		{
			Path file = locate(target);
			if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
				throw problem(link, "the link's target " + display(target) + " does not exist");
			if (!chain.add(target))
				throw problem(link, "the link's target " + display(target) + " forms a cycle of links");
			if (!Files.isSymbolicLink(file))
				break;
		}
		String text = PortablePaths.text(relative.getParent().relativize(first));
		workspaceFiles.put(workspacePath, new WorkspaceEntry(workspacePath, false, Set.of(), Optional.of(text)));
		sources.put(workspacePath, link);
		return true;
	}

	/**
	 * Returns the target of a link if it lies inside the prompt test's {@value PromptTestFiles#WORKSPACE}.
	 *
	 * @param relative the link's path relative to the project
	 * @return the target relative to the project, or {@code null} if the target is absolute, lies outside
	 *   {@value PromptTestFiles#WORKSPACE} or is {@value PromptTestFiles#WORKSPACE} itself
	 * @throws IOException if the link cannot be read
	 */
	private Path internalTarget(Path relative) throws IOException
	{
		Path text = Files.readSymbolicLink(locate(relative));
		if (text.isAbsolute() || text.getRoot() != null)
			return null;
		Path target = relative.getParent().resolve(text).normalize();
		if (!target.startsWith(workspace) || target.equals(workspace))
			return null;
		return target;
	}

	/**
	 * Replaces a symbolic link by its target, following a chain of links to the first target that is not one.
	 *
	 * @param link          the link
	 * @param relative      the link's path relative to the project
	 * @param workspacePath the link's path below {@value PromptTestFiles#WORKSPACE}
	 * @param expanding     the linked directories whose entries are being read, relative to the project
	 * @throws IOException             if a file cannot be read
	 * @throws ScenarioFormatException if the link is refused
	 */
	private void resolve(Path link, Path relative, String workspacePath, Deque<Path> expanding)
		throws IOException, ScenarioFormatException
	{
		Path location = link;
		Path locationRelative = relative;
		Set<Path> chain = new HashSet<>();
		while (true)
		{
			Path text = Files.readSymbolicLink(location);
			if (text.isAbsolute() || text.getRoot() != null)
			{
				throw problem(location, "the link's target " + text + " is absolute; write it relative to the link's " +
					"directory");
			}
			Path parent = locationRelative.getParent();
			Path target;
			if (parent == null)
				target = text.normalize();
			else
				target = parent.resolve(text).normalize();
			if (target.startsWith(".."))
				throw problem(location, "the link's target " + text + " lies outside the project");
			if (target.toString().isEmpty() || locationRelative.startsWith(target))
			{
				throw problem(location, "the link's target directory " + display(target) + " holds the link, so its " +
					"copy would hold itself");
			}
			Path file = locate(target);
			if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
				throw problem(location, "the link's target " + display(target) + " does not exist");
			if (Files.isSymbolicLink(file))
			{
				if (!chain.add(target))
					throw problem(location, "the link's target " + display(target) + " forms a cycle of links");
				location = file;
				locationRelative = target;
				continue;
			}
			if (expanding.contains(target))
			{
				throw problem(location, "the link's target " + display(target) + " forms a cycle: it holds a link " +
					"that leads back to it");
			}
			requireOutsidePromptTests(location, target);
			if (isInTestRoot(target))
				linkedTargets.add(target);
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
			if (attributes.isDirectory())
			{
				expanding.push(target);
				walk(file, target, workspacePath, expanding, link);
				expanding.pop();
			}
			else if (attributes.isRegularFile())
				addWorkspaceFile(workspacePath, file);
			else
				throw problem(location, "the link's target " + display(target) + " is a special file");
			return;
		}
	}

	/**
	 * Returns the file at a path relative to the project: the file of the tree if it holds one there, and otherwise the
	 * file of the project.
	 *
	 * @param relative a path relative to the project
	 * @return the file, which need not exist
	 */
	private Path locate(Path relative)
	{
		Path inTree = tree.resolve(relative);
		if (Files.exists(inTree, LinkOption.NOFOLLOW_LINKS))
			return inTree;
		return project.resolve(relative);
	}

	/**
	 * Refuses a link whose target lies at or below a prompt test directory.
	 *
	 * @param link   the link
	 * @param target the link's target, relative to the project
	 * @throws ScenarioFormatException if the target or a directory that holds it is a prompt test directory
	 */
	private void requireOutsidePromptTests(Path link, Path target) throws ScenarioFormatException
	{
		for (Path directory = target; directory != null; directory = directory.getParent())
		{
			if (Files.isRegularFile(locate(directory.resolve(PromptTestFiles.SCENARIO)), LinkOption.NOFOLLOW_LINKS))
			{
				throw problem(link, "the link reaches the prompt test directory " + display(directory) + "; a trial " +
					"may not receive a prompt test's files");
			}
		}
	}

	/**
	 * Indicates whether a path lies inside a test root.
	 *
	 * @param target a path relative to the project
	 * @return {@code true} if the path lies in the prompt test's test root or another test root
	 */
	private boolean isInTestRoot(Path target)
	{
		return root.toString().isEmpty() || target.startsWith(root) || PromptTestLocations.rootOf(target).isPresent();
	}

	/**
	 * Adds a workspace file.
	 *
	 * @param path the workspace file's path below {@value PromptTestFiles#WORKSPACE}
	 * @param file the file that the workspace file is copied from
	 * @throws IOException if the file cannot be read
	 */
	private void addWorkspaceFile(String path, Path file) throws IOException
	{
		String content = PromptTestFiles.utf8(Files.readAllBytes(file));
		WorkspaceEntry workspaceFile;
		if (content == null)
			workspaceFile = new WorkspaceEntry(path, false, Set.of());
		else
		{
			Set<String> names = new HashSet<>(Placeholders.scenarioNames(content));
			names.retainAll(declared);
			workspaceFile = new WorkspaceEntry(path, true, names);
		}
		workspaceFiles.put(path, workspaceFile);
		sources.put(path, file);
	}

	/**
	 * Returns a path relative to the project as a problem names it.
	 *
	 * @param relative the path
	 * @return the path with {@code /} between names
	 */
	private static String display(Path relative)
	{
		return relative.toString().replace('\\', '/');
	}

	/**
	 * Returns a problem with a file.
	 *
	 * @param file    the file
	 * @param message the problem
	 * @return the exception
	 */
	private ScenarioFormatException problem(Path file, String message)
	{
		requireThat(message, "message").isNotBlank();
		return new ScenarioFormatException(PromptTestFiles.display(project, file) + ": " + message);
	}
}
