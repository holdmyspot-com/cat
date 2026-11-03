package com.holdmyspot.cat.common.updater.activation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests the arguments that the activation steps accept. A step that names a relative or unnormalized path, or that
 * stages inside the directory it replaces, could write outside the harness home or destroy its own undo.
 */
public final class ActivationStepTest
{
	/**
	 * An absolute, normalized directory on the current platform. Nothing is written there.
	 */
	private static final Path HOME = Path.of(System.getProperty("cat.updater.test.directory")).
		resolve("harness-homes/harness-a");

	/**
	 * Creates a test instance.
	 */
	public ActivationStepTest()
	{
	}

	/**
	 * Runs an action that must refuse its arguments.
	 *
	 * @param name   the case's name
	 * @param action the action
	 */
	private static void requireRefused(String name, Runnable action)
	{
		try
		{
			action.run();
		}
		catch (IllegalArgumentException _)
		{
			return;
		}
		throw new AssertionError(name + " was accepted");
	}

	/**
	 * Steps with absolute, normalized paths are accepted, including a slot parent that is the live directory's parent.
	 */
	@Test
	public void acceptsValidSteps()
	{
		Path live = HOME.resolve("plugins/cat");
		ReplaceDirectory replace = new ReplaceDirectory(live, live.getParent());
		requireThat(replace.slotParent(), "slotParent").isEqualTo(live.getParent());
		new ReplaceDirectory(live, HOME.resolve("slots"));
		new AddDirectory(HOME.resolve("plugins/cat"), HOME.resolve("plugins"));
		new ReplaceOwnedFiles(HOME.resolve("agents"), HOME.resolve("cat-owned.txt"), "cat-", "agents/sub", "*.toml");
		requireThat(new InstalledState(Optional.of("1.0"), true).version(), "version").isEqualTo(Optional.of("1.0"));
		new InstalledState(Optional.empty(), false);
	}

	/**
	 * Relative or unnormalized paths, and staging inside the target, are refused.
	 */
	@Test
	public void refusesUnsafePaths()
	{
		Path live = HOME.resolve("plugins/cat");
		requireRefused("relative live", () -> new ReplaceDirectory(Path.of("plugins/cat"), HOME));
		requireRefused("relative slot parent", () -> new ReplaceDirectory(live, Path.of("slots")));
		requireRefused("unnormalized live", () -> new ReplaceDirectory(HOME.resolve("x/../plugins/cat"), HOME));
		requireRefused("slot parent inside live", () -> new ReplaceDirectory(live, live.resolve("slots")));
		requireRefused("slot parent equal to live", () -> new ReplaceDirectory(live, live));
		requireRefused("root live", () -> new ReplaceDirectory(HOME.getRoot(), HOME));
		requireRefused("staging inside target", () -> new AddDirectory(live, live.resolve("staging")));
		requireRefused("relative target", () -> new AddDirectory(Path.of("cat"), HOME));
	}

	/**
	 * Owned-file steps refuse a prefix, source directory or glob that could reach outside their directories.
	 */
	@Test
	public void refusesUnsafeOwnedFiles()
	{
		Path directory = HOME.resolve("agents");
		Path manifest = HOME.resolve("cat-owned.txt");
		List<String> prefixes = List.of("", " ", "a/b", "a\\b");
		for (String prefix : prefixes)
		{
			requireRefused("prefix " + prefix,
				() -> new ReplaceOwnedFiles(directory, manifest, prefix, "agents", "*.toml"));
		}
		List<String> subdirectories = List.of("", "/agents", "agents/", "a//b", "../agents", "a/./b", "a\\b");
		for (String subdirectory : subdirectories)
		{
			requireRefused("subdirectory " + subdirectory,
				() -> new ReplaceOwnedFiles(directory, manifest, "cat-", subdirectory, "*.toml"));
		}
		List<String> globs = List.of("", "a/*.toml", "a\\*.toml", "[");
		for (String glob : globs)
		{
			requireRefused("glob " + glob,
				() -> new ReplaceOwnedFiles(directory, manifest, "cat-", "agents", glob));
		}
		requireRefused("relative directory",
			() -> new ReplaceOwnedFiles(Path.of("agents"), manifest, "cat-", "agents", "*.toml"));
		requireRefused("blank version", () -> new InstalledState(Optional.of(" "), true));
	}
}
