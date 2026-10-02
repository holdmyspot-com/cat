package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.file;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.LinkedProject;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies that the runner's project copy accepts a staged source that is built from a project with links, and
 * refuses the project itself.
 * <p>
 * Both harness runners copy their project through {@link HarnessRunManager#copyProject(Path, HarnessRun)}, so the
 * test registers a run for each harness and copies into it. It starts no model and no harness process.
 */
public final class StagedSourceRunnerCopyTest
{
	/**
	 * Creates the test.
	 */
	public StagedSourceRunnerCopyTest()
	{
	}

	/**
	 * Returns the harnesses whose runs the test copies into.
	 *
	 * @return one row per harness
	 */
	@DataProvider
	public static Object[][] harnesses()
	{
		return new Object[][]
		{
			{"claude"},
			{"codex"}
		};
	}

	/**
	 * Verifies that the runner refuses a project that contains links, accepts the staged source of the same project,
	 * and copies it with every link replaced by a regular copy of its target.
	 *
	 * @param harness the harness whose run receives the copy
	 * @throws IOException if the fixture cannot be created or copied
	 */
	@Test(dataProvider = "harnesses")
	public void runnerAcceptsStagedSourceWithLinks(String harness) throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path fixture = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.work.temp")).normalize()),
				"staged-runner-copy-"));
			LinkedProject project = ProjectFixtures.linkedProject(fixture);
			Path staged = new StagedSources(project.tree(), fixture.resolve("staged")).
				stage(Overlay.of(project.tree().inventory()));
			HarnessRunManager manager = new HarnessRunManager(fixture.resolve("runs"), Clock.systemUTC());

			HarnessRun refused = manager.create(harness);
			try
			{
				manager.copyProject(project.base(), refused);
				throw new AssertionError("The runner must refuse a project that contains links");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("symbolic-link");
			}

			HarnessRun accepted = manager.create(harness);
			manager.copyProject(staged, accepted);

			Path copy = accepted.directory().resolve("project");
			Map<Path, FileEntry> entries = InventoryScanner.scan(copy, Set.of()).entries();
			requireThat(entries.values().stream().filter(SymbolicLink.class::isInstance).toList(), "links").isEmpty();
			requireThat(entries.get(Path.of("alias.md")), "fileLink").isEqualTo(file("prompt"));
			requireThat(entries.get(Path.of("docs/a.md")), "directoryLink").isEqualTo(file("rule a"));
			requireThat(entries.get(Path.of("shared.md")), "externalLink").isEqualTo(file("shared"));
			requireThat(entries.get(Path.of("ext-dir/x.md")), "externalDirectoryLink").isEqualTo(file("external x"));
			requireThat(entries.get(Path.of("fixtures/data.txt")), "fixture").isEqualTo(file("fixture data"));
			requireThat(Files.exists(copy.resolve(".cat/work")), "workDirectory").isFalse();
		}
	}
}
