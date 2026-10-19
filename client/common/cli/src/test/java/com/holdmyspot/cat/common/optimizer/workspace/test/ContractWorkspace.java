package com.holdmyspot.cat.common.optimizer.workspace.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.common.PortableFiles;
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
	private final WorkspacePort port;
	private final FileInventory fork;
	private final Path root;
	private final Path worktree;
	private final boolean stagedFiles;

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
		this.root = root;
		this.stagedFiles = stagedFiles;
		Path project = Files.createDirectories(root.resolve("project"));
		write(project.resolve("prompt.md"), "original");
		write(project.resolve("deleted.md"), "delete");
		write(project.resolve("keep.md"), "keep");
		Path storage = root.resolve("campaign");
		ForkTree tree = ForkTree.record(project, storage,
			Set.of(Path.of("prompt.md"), Path.of("deleted.md"), Path.of("keep.md")));
		this.fork = tree.inventory();
		this.worktree = root.resolve("worktree");
		if (fake)
		{
			if (stagedFiles)
				this.port = new FakeWorkspacePort(root.resolve("fake"), fork, project);
			else
				this.port = new FakeWorkspacePort(root.resolve("fake"), fork);
		}
		else
		{
			this.port = new Workspace(tree, storage, worktree);
			port.materialize(Overlay.of(fork));
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
	public boolean stagedExists(Sha256 candidate, Path directory)
	{
		if (port instanceof FakeWorkspacePort fake && !stagedFiles)
			return fake.staged().contains(candidate);
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
				Files.copy(copy.resolve(file), effects.resolve(file), REPLACE_EXISTING);
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
		Files.writeString(file, content, UTF_8);
		PortableFiles.setMode(file, PosixFilePermissions.fromString("rw-r--r--"));
	}
}
