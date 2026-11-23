package com.holdmyspot.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.port.contract.test.WorkspaceContract;
import com.holdmyspot.cat.common.optimizer.port.test.FakeWorkspacePort;
import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;
import com.holdmyspot.cat.common.optimizer.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Supplies owned files and fake inputs for {@link WorkspaceContract} without creating a Git repository.
 */
public final class ContractWorkspace implements WorkspaceContract.Subject
{
	/**
	 * The recorded regular file targeted by the file aliases.
	 */
	public static final Path LINK_SOURCE = Path.of("links/source.txt");
	/**
	 * The first symbolic-link destination used by the staging fixture.
	 */
	public static final Path FILE_ALIAS = Path.of("links/file-alias.txt");
	/**
	 * The second symbolic-link destination, which resolves through {@link #FILE_ALIAS}.
	 */
	public static final Path CHAINED_ALIAS = Path.of("links/chained-alias.txt");
	/**
	 * The symbolic-link destination of the recorded directory.
	 */
	public static final Path DIRECTORY_ALIAS = Path.of("links/directory-alias");
	/**
	 * The recorded regular file beneath the directory alias.
	 */
	public static final Path DIRECTORY_TARGET = Path.of("links/dir/target.txt");
	/**
	 * One link in the cyclic-link refusal fixture.
	 */
	public static final Path CYCLE_ONE = Path.of("links/cycle-one");
	/**
	 * The other link in the cyclic-link refusal fixture.
	 */
	public static final Path CYCLE_TWO = Path.of("links/cycle-two");
	/**
	 * The link whose target is absent in the missing-link refusal fixture.
	 */
	public static final Path MISSING_ALIAS = Path.of("links/missing-alias");

	/**
	 * The recorded link arrangement used by staging checks.
	 */
	public enum LinkFixture
	{
		/**
		 * The original fixture with no symbolic links.
		 */
		ORDINARY,
		/**
		 * A regular file, chained file and directory alias.
		 */
		STAGING,
		/**
		 * A two-link cycle.
		 */
		CYCLE,
		/**
		 * A link whose target does not exist.
		 */
		MISSING
	}

	private final WorkspacePort port;
	private final FileInventory fork;
	private final Path root;
	private final Path projectDirectory;
	private final Path worktree;

	/**
	 * Creates a workspace with owned project files and a recorded fork.
	 *
	 * @param root the owned fixture directory
	 * @param fake whether to use the fake
	 * @param stagedFiles whether the fake should write staged files
	 * @throws IOException if the fixture cannot be created
	 */
	public ContractWorkspace(Path root, boolean fake, boolean stagedFiles) throws IOException
	{
		this(root, fake, stagedFiles, LinkFixture.ORDINARY);
	}

	/**
	 * Creates a workspace with a selected recorded link arrangement.
	 *
	 * @param root the owned fixture directory
	 * @param fake whether to use the fake
	 * @param stagedFiles whether the fake should write staged files
	 * @param linkFixture the recorded links to include, or null for the ordinary fixture
	 * @throws IOException if the fixture cannot be created
	 */
	public ContractWorkspace(Path root, boolean fake, boolean stagedFiles, LinkFixture linkFixture) throws IOException
	{
		requireThat(linkFixture, "linkFixture").isNotNull();
		this.root = root;
		this.projectDirectory = Files.createDirectories(root.resolve("project"));
		Path project = projectDirectory;
		write(project.resolve("prompt.md"), "original");
		write(project.resolve("deleted.md"), "delete");
		write(project.resolve("keep.md"), "keep");
		Set<Path> recordedPaths = new java.util.LinkedHashSet<>(
			Set.of(Path.of("prompt.md"), Path.of("deleted.md"), Path.of("keep.md")));
		addLinkFixture(project, linkFixture, recordedPaths);
		Path storage = root.resolve("campaign");
		ForkTree tree = ForkTree.record(project, storage, recordedPaths);
		this.fork = tree.inventory();
		this.worktree = root.resolve("worktree");
		if (fake)
		{
			if (stagedFiles)
			{
				FakeWorkspacePort fakePort = new FakeWorkspacePort(root.resolve("fake"), fork, project);
				if (linkFixture == LinkFixture.STAGING)
				{
					fakePort.supplyFile(LINK_SOURCE, new FileContent(Files.readAllBytes(project.resolve(LINK_SOURCE))));
					fakePort.supplyFile(DIRECTORY_TARGET,
						new FileContent(Files.readAllBytes(project.resolve(DIRECTORY_TARGET))));
				}
				this.port = fakePort;
			}
			else
				this.port = new FakeWorkspacePort(root.resolve("fake"), fork);
		}
		else
		{
			this.port = new Workspace(tree, storage, worktree, PlainWorktreeLinks.INSTANCE);
			port.materialize(Overlay.of(fork));
		}
	}

	/**
	 * Creates the link paths and records the selected fixture's files in the fork.
	 *
	 * @param project the project directory
	 * @param fixture the recorded link arrangement
	 * @param recordedPaths the paths included in the fork
	 * @throws IOException if a link cannot be created
	 */
	private static void addLinkFixture(Path project, LinkFixture fixture, Set<Path> recordedPaths) throws IOException
	{
		if (fixture == LinkFixture.ORDINARY)
			return;
		write(project.resolve(LINK_SOURCE), "recorded source");
		Files.createSymbolicLink(project.resolve(FILE_ALIAS), Path.of("source.txt"));
		Files.createSymbolicLink(project.resolve(CHAINED_ALIAS), Path.of("file-alias.txt"));
		write(project.resolve(DIRECTORY_TARGET), "recorded directory target");
		Files.createSymbolicLink(project.resolve(DIRECTORY_ALIAS), Path.of("dir"));
		recordedPaths.addAll(Set.of(LINK_SOURCE, FILE_ALIAS, CHAINED_ALIAS, DIRECTORY_ALIAS, DIRECTORY_TARGET));
		if (fixture == LinkFixture.CYCLE)
		{
			Files.createSymbolicLink(project.resolve(CYCLE_ONE), Path.of("cycle-two"));
			Files.createSymbolicLink(project.resolve(CYCLE_TWO), Path.of("cycle-one"));
			recordedPaths.addAll(Set.of(CYCLE_ONE, CYCLE_TWO));
		}
		else if (fixture == LinkFixture.MISSING)
		{
			Files.createSymbolicLink(project.resolve(MISSING_ALIAS), Path.of("absent.txt"));
			recordedPaths.add(MISSING_ALIAS);
		}
	}

	@Override
	public WorkspacePort port()
	{
		return port;
	}

	@Override
	public FileInventory fork()
	{
		return fork;
	}

	@Override
	public Overlay linkCandidate()
	{
		RegularFile target = (RegularFile) fork.entries().get(DIRECTORY_TARGET);
		return Overlay.of(fork).write(DIRECTORY_TARGET, new FileContent("candidate directory target".getBytes(UTF_8)),
			target.permissions());
	}

	@Override
	public void changeLiveLinkTargets() throws IOException
	{
		write(projectDirectory.resolve(LINK_SOURCE), "changed live source");
		write(projectDirectory.resolve(DIRECTORY_TARGET), "changed live directory target");
	}

	@Override
	public boolean stagedExists(Sha256 candidate, Path directory)
	{
		return Files.isDirectory(directory);
	}

	@Override
	public boolean copyExists(Path copy)
	{
		if (port instanceof FakeWorkspacePort fake)
			return fake.authoringCopies().contains(copy);
		return Files.isDirectory(copy);
	}

	@Override
	public boolean pluginCopyExists(Path copy)
	{
		if (port instanceof FakeWorkspacePort fake)
			return fake.pluginCopies().contains(copy);
		return Files.isDirectory(copy);
	}

	@Override
	public String stagedContent(Path directory, Path file) throws IOException
	{
		Path path = directory.resolve(file);
		if (!Files.exists(path))
			return null;
		return Files.readString(path, UTF_8);
	}

	@Override
	public void changeStaged(Path directory) throws IOException
	{
		write(directory.resolve("prompt.md"), "cached");
	}

	@Override
	public void editCopy(Path copy) throws IOException
	{
		if (port instanceof FakeWorkspacePort fake)
			fake.editAuthoringCopy(copy, edited(copy, port.scanAuthoringCopy(copy), "copy edit"));
		else
			write(copy.resolve("prompt.md"), "copy edit");
	}

	@Override
	public Path promptAuthorEffects(Path copy) throws IOException
	{
		Path effects = unchangedPromptAuthorEffects(copy);
		if (port instanceof FakeWorkspacePort fake)
			fake.editAuthoringCopy(copy, edited(effects, port.scanAuthoringCopy(copy), "effects edit"));
		else
			write(effects.resolve("prompt.md"), "effects edit");
		return effects;
	}

	@Override
	public Path unchangedPromptAuthorEffects(Path copy) throws IOException
	{
		Path effects = root.resolve("effects");
		if (!(port instanceof FakeWorkspacePort))
		{
			Files.createDirectories(effects);
			for (Path file : port.scanAuthoringCopy(copy).entries().keySet())
				Files.copy(copy.resolve(file), effects.resolve(file), REPLACE_EXISTING, COPY_ATTRIBUTES);
		}
		return effects;
	}

	@Override
	public void changeWorktree() throws IOException
	{
		if (port instanceof FakeWorkspacePort fake)
			fake.changeWorktree(edited(fork.root(), fork, "outside edit"));
		else
			write(worktree.resolve("prompt.md"), "outside edit");
	}

	@Override
	public void supplyCapturedFile(Path file, FileContent content)
	{
		if (port instanceof FakeWorkspacePort fake)
			fake.supplyFile(file, content);
	}

	@Override
	public void changeAndDeleteProjectFile(Path file) throws IOException
	{
		Path live = projectDirectory.resolve(file);
		write(live, "changed live file");
		Files.delete(live);
	}

	@Override
	public Path pluginDirectory() throws IOException
	{
		Path plugin = Files.createDirectories(root.resolve("plugin"));
		write(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\": \"cat\"}");
		write(plugin.resolve("bin/read-part"), "#!/bin/sh\n");
		write(plugin.resolve("bin/another-read-part"), "#!/bin/sh\n");
		write(plugin.resolve("bin/unmapped"), "unmapped base");
		return plugin;
	}

	/**
	 * Builds an inventory with a changed prompt.
	 *
	 * @param root the inventory root
	 * @param before the initial entries
	 * @param content the changed prompt's content
	 * @return the edited inventory
	 */
	private static FileInventory edited(Path root, FileInventory before, String content)
	{
		Map<Path, FileEntry> entries = new HashMap<>(before.entries());
		entries.put(Path.of("prompt.md"), new RegularFile(Sha256.of(content.getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--")));
		return new FileInventory(root.toAbsolutePath().normalize(), entries);
	}

	/**
	 * Writes a fixture file with a known permission mode.
	 *
	 * @param file the destination
	 * @param content UTF-8 content
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, UTF_8);
		PortableFiles.setMode(file, PosixFilePermissions.fromString("rw-r--r--"));
	}
}
