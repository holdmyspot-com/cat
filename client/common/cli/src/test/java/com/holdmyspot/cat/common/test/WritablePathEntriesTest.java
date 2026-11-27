package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.WritablePathEntries;
import com.holdmyspot.cat.common.WritablePathEntries.Writable;
import com.holdmyspot.cat.common.WriteGrant;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Verifies that {@link WritablePathEntries} finds the folders of a search path that a sandbox lets its commands write.
 */
public final class WritablePathEntriesTest
{
	/**
	 * Creates the test.
	 */
	public WritablePathEntriesTest()
	{
	}

	/**
	 * A folder that a grant names is writable, and the finding names the grant's setting.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void findsFolderThatGrantNames() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("writable-path-")).toRealPath();
			Path bin = Files.createDirectories(root.resolve("tools/bin"));
			WriteGrant grant = new WriteGrant(bin, "sandbox.filesystem.allowWrite in settings.json");

			List<Writable> found = WritablePathEntries.find(bin.toString(), root,
				new SandboxWrites(List.of(grant), List.of()));

			requireThat(found, "found").isEqualTo(List.of(new Writable(bin, grant)));
		}
	}

	/**
	 * A folder below a granted folder is writable, a folder that no grant covers is not, and a granted folder above a
	 * folder is not enough when the folder lies beside the grant.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void findsFolderBelowGrantOnly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("writable-path-")).toRealPath();
			Path below = Files.createDirectories(root.resolve("granted/tools/bin"));
			Path beside = Files.createDirectories(root.resolve("elsewhere/bin"));
			WriteGrant grant = new WriteGrant(root.resolve("granted"), "permissions.additionalDirectories");

			List<Writable> found = WritablePathEntries.find(below + File.pathSeparator + beside, root,
				new SandboxWrites(List.of(grant), List.of()));

			requireThat(found, "found").isEqualTo(List.of(new Writable(below, grant)));
		}
	}

	/**
	 * A denial that covers a folder keeps it from being reported, even when a grant covers it.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void leavesOutDeniedFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("writable-path-")).toRealPath();
			Path denied = Files.createDirectories(root.resolve("granted/denied/bin"));
			Path allowed = Files.createDirectories(root.resolve("granted/allowed"));
			WriteGrant grant = new WriteGrant(root.resolve("granted"), "sandbox.filesystem.allowWrite");

			List<Writable> found = WritablePathEntries.find(denied + File.pathSeparator + allowed, root,
				new SandboxWrites(List.of(grant), List.of(root.resolve("granted/denied"))));

			requireThat(found, "found").isEqualTo(List.of(new Writable(allowed, grant)));
		}
	}

	/**
	 * Paths compare by the files that they reach: a search path entry that is a symbolic link into a granted folder is
	 * writable, and a granted symbolic link covers the folder that it reaches.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void resolvesSymbolicLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("writable-path-")).toRealPath();
			Path real = Files.createDirectories(root.resolve("real/bin"));
			Path link = Files.createSymbolicLink(root.resolve("link"), real);
			Path other = Files.createDirectories(root.resolve("other"));
			WriteGrant grant = new WriteGrant(real, "sandbox.filesystem.allowWrite");

			List<Writable> found = WritablePathEntries.find(link + File.pathSeparator + other, root,
				new SandboxWrites(List.of(grant), List.of()));

			requireThat(found, "found").isEqualTo(List.of(new Writable(link, grant)));
		}
	}

	/**
	 * An empty or relative entry names the project folder or a folder below it, because the session's commands run
	 * there, so a grant that covers the project folder makes it writable; a folder that appears twice is reported once.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void resolvesRelativeEntriesAgainstProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("writable-path-")).toRealPath();
			Path project = Files.createDirectories(root.resolve("project"));
			Path node = Files.createDirectories(project.resolve("node_modules/.bin"));
			WriteGrant grant = new WriteGrant(project, "the session's project folder");
			String searchPath = "node_modules/.bin" + File.pathSeparator + File.pathSeparator + node;

			List<Writable> found = WritablePathEntries.find(searchPath, project,
				new SandboxWrites(List.of(grant), List.of()));

			requireThat(found, "found").isEqualTo(List.of(new Writable(node, grant), new Writable(project, grant)));
		}
	}

	/**
	 * A sandbox that grants nothing makes no folder writable.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void findsNothingWithoutGrants() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("writable-path-")).toRealPath();

			List<Writable> found = WritablePathEntries.find(root.toString(), root, SandboxWrites.NONE);

			requireThat(found, "found").isEmpty();
		}
	}
}
