package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.commit;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.git.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests source-patch, adaptation, and draft-tree behavior through {@link GitTopicReplayEditor}.
 */
public final class EditorPatchWorkflowTest
{
	/**
	 * Creates the test fixture.
	 */
	public EditorPatchWorkflowTest()
	{
	}

	/**
	 * Verifies a cleanup patch can be adapted to an earlier target tree.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void capturesPatchForEarlierFileVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String target = commit(git, repository, "Plugin.java", """
				final class Plugin
				{
					void buildsPlugin()
					{
						build();
					}
				}
				""", "distribution");
			commit(git, repository, "Plugin.java", """
				final class Plugin
				{
					void buildsPlugin()
					{
						build();
					}

					void preservesExecutableResource()
					{
						preserve();
					}
				}
				""", "claude");
			String source = commit(git, repository, "Plugin.java", """
				final class Plugin
				{
					void buildsPlugin()
					{
						buildWithCleanup();
					}

					void preservesExecutableResource()
					{
						preserve();
					}
				}
				""", "cleanup");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			writeSourceManifest(git, manifest, base, source);
			Path sourcePatch = replay.resolve("source.patch");
			editor.captureSourcePathPatch(manifest, source, sourcePatch, List.of("Plugin.java"));
			Path index = replay.resolve("inspection.index");
			requireThat(Files.readString(sourcePatch), "sourcePatch").contains(
				"diff --git a/Plugin.java b/Plugin.java").contains("+\t\tbuildWithCleanup();");
			Path candidate = replay.resolve("Plugin.java");
			editor.writeThreeWayMergeCandidate(manifest, source, target, "Plugin.java", candidate);
			requireThat(Files.readString(candidate), "mergeCandidate").contains("buildWithCleanup();").doesNotContain(
				"<<<<<<").doesNotContain("void preservesExecutableResource()");
			Path replacementTree = replay.resolve("replacement-tree.txt");
			editor.writeReplacementTree(manifest, target, "Plugin.java", candidate, replacementTree);
			String replacementTreeId = Files.readString(replacementTree).strip();
			requireThat(String.join("\n", git.lines("show", replacementTreeId + ":Plugin.java")), "replacementTreeFile").
				isEqualTo(Files.readString(candidate).strip());
			Path targetPatch = replay.resolve("target.patch");
			editor.captureTargetAdaptedPathPatch(manifest, source, targetPatch, target, "Plugin.java", candidate);
			git.lines(index, "read-tree", target + "^{tree}");
			git.lines(index, "apply", "--cached", targetPatch.toString());
			requireThat(String.join("\n", git.lines(index, "show", ":Plugin.java")), "adaptedFile").isEqualTo(
				Files.readString(candidate).strip());
		}
	}

	/**
	 * Verifies a selected source hunk can be adapted when a target changed nearby text.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void adaptsSourcePatchThroughThreeWayMerge() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = commit(git, repository, "Plugin.java", """
				final class Plugin
				{
					void buildsPlugin()
					{
						build();
					}
				}
				""", "baseline");
			String source = commit(git, repository, "Plugin.java", """
				final class Plugin
				{
					void buildsPlugin()
					{
						build();
					}

					void preservesExecutableResource()
					{
						preserve();
					}
				}
				""", "feature");
			Files.writeString(repository.resolve("Plugin.java"), """
				final class Plugin
				{
					void buildsPlugin()
					{
						buildWithCleanup();
					}
				}
				""");
			git.run("add", "Plugin.java");
			String target = git.run("commit-tree", git.run("write-tree").trim(), "-p", base, "-m", "cleanup").trim();
			git.run("reset", "--hard", "--quiet", source);
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			writeSourceManifest(git, manifest, base, source);
			Path patch = replay.resolve("feature.patch");
			editor.captureThreeWayAdaptedSourceHunkPatch(manifest, source, patch, target, "Plugin.java", 1);
			Path index = replay.resolve("inspection.index");
			git.lines(index, "read-tree", target + "^{tree}");
			git.lines(index, "apply", "--cached", patch.toString());
			String actual = String.join("\n", git.lines(index, "show", ":Plugin.java"));
			requireThat(actual, "adaptedFile").contains("buildWithCleanup();").contains(
				"void preservesExecutableResource()");
		}
	}

	/**
	 * Verifies a draft plan returns the named output's tree without rewriting the repository.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void writesDraftOutputTree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String source = commit(git, repository, "feature.txt", "feature\n", "feature");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			writeSourceManifest(git, manifest, base, source);
			editor.captureSourcePatch(manifest, source, replay.resolve("feature.patch"));
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":[{"id":"feature","source":"%s",
				"patch":"feature.patch"}]}]
				""".formatted(source));
			String tree = editor.writeDraftOutputTree(manifest, plan, "output-1");
			requireThat(tree, "draftTree").isEqualTo(git.run("rev-parse", source + "^{tree}").trim());
			requireThat(git.run("rev-parse", "HEAD").trim(), "head").isEqualTo(source);
		}
	}

	/**
	 * Writes the captured source facts needed by patch and draft-tree operations without running inventory preparation.
	 *
	 * @param git Git access for the isolated integration repository
	 * @param manifestFile the manifest-local input file
	 * @param base the commit preceding the selected source range
	 * @param source the selected source commit
	 * @throws IOException if Git cannot read the source trees or the manifest cannot be written
	 */
	private void writeSourceManifest(Git git, Path manifestFile, String base, String source) throws IOException
	{
		SourceSnapshot snapshot = new SourceSnapshot(source, git.run("rev-parse", source + "^^{tree}").trim(),
			git.run("rev-parse", source + "^{tree}").trim());
		BatchManifest manifest = new BatchManifest(ManifestStore.BATCH_MANIFEST_VERSION, base, snapshot.tree(),
			List.of(snapshot),
			List.of(new Output("output-1", source, "test: selected source")), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), null);
		try (ToolScope scope = new ToolScope())
		{
			scope.json().writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
		}
	}
}
