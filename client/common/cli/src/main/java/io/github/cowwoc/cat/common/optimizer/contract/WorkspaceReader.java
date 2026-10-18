package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles.Fixture;

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
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads the {@value PromptTestFiles#WORKSPACE} directory of one prompt test, replacing each symbolic link by its
 * target. A file of the directory, or a file of the project that a link reaches, becomes a fixture at its path below
 * {@value PromptTestFiles#WORKSPACE}.
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
	 * The names of the prompt test's variables, which a fixture's content may name.
	 */
	private final Set<String> declared;
	/**
	 * The fixtures read so far, by path.
	 */
	private final Map<String, Fixture> fixtures = new TreeMap<>();
	/**
	 * The file that each fixture is copied from, by the fixture's path.
	 */
	private final Map<String, Path> sources = new TreeMap<>();
	/**
	 * The files and directories inside a test root that links reach, relative to the project.
	 */
	private final Set<Path> linkedTargets = new LinkedHashSet<>();

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
		walk(workspace, tree.relativize(workspace), "", new ArrayDeque<>(), null);
	}

	/**
	 * Returns the fixtures.
	 *
	 * @return the fixtures, sorted by path
	 */
	List<Fixture> fixtures()
	{
		return List.copyOf(fixtures.values());
	}

	/**
	 * Returns the file that each fixture is copied from.
	 *
	 * @return each file, by the fixture's path
	 */
	Map<String, Path> sources()
	{
		return Map.copyOf(sources);
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
				resolve(entry, entryRelative, path, expanding);
			else if (!attributes.isRegularFile())
				throw problem(entry, "a workspace file must be a regular file, a directory or a symbolic link");
			else if (origin != null && name.equals(PromptTestFiles.SCENARIO))
			{
				throw problem(origin, "the link reaches the prompt test directory " +
					PromptTestFiles.display(project, directory) + "; a trial may not receive a prompt test's files");
			}
			else
				addFixture(path, entry);
		}
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
				addFixture(workspacePath, file);
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
	 * Adds a fixture.
	 *
	 * @param path the fixture's path below {@value PromptTestFiles#WORKSPACE}
	 * @param file the file that the fixture is copied from
	 * @throws IOException if the file cannot be read
	 */
	private void addFixture(String path, Path file) throws IOException
	{
		String content = PromptTestFiles.utf8(Files.readAllBytes(file));
		Fixture fixture;
		if (content == null)
			fixture = new Fixture(path, false, Set.of());
		else
		{
			Set<String> names = new HashSet<>(Placeholders.scenarioNames(content));
			names.retainAll(declared);
			fixture = new Fixture(path, true, names);
		}
		fixtures.put(path, fixture);
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
