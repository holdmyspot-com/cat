package com.holdmyspot.cat.common.git.replay.test;

import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.commit;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.commitVersions;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.fastImport;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.initializeRepository;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.git.replay.BatchManifest;
import com.holdmyspot.cat.common.git.replay.GitTopicReplayEditor;
import com.holdmyspot.cat.common.git.replay.ManifestStore;
import com.holdmyspot.cat.common.git.replay.Output;
import com.holdmyspot.cat.common.git.replay.ReplayJson;
import com.holdmyspot.cat.common.git.replay.SourceSnapshot;
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
			List<String> commits = commitVersions(git, repository, "Plugin.java", List.of("""
				final class Plugin
				{
					void buildsPlugin()
					{
						build();
					}
				}
				""", """
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
				""", """
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
				"""));
			String target = commits.getFirst();
			String source = commits.getLast();
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = ReplayFixtures.editor(repository);
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
			// The target commit shares the baseline parent with the source commit, and the branch ends at the source
			// commit; "reset refs/target" without "from" keeps the target commit but creates no reference to it
			List<String> commits = fastImport(repository, """
				commit %1$s
				mark :1
				committer CAT Test <cat-test@example.invalid> 0 +0000
				data <<END
				test: baseline
				END
				from %1$s^0
				M 100644 inline Plugin.java
				data <<END
				final class Plugin
				{
					void buildsPlugin()
					{
						build();
					}
				}
				END

				commit refs/target
				mark :2
				committer CAT Test <cat-test@example.invalid> 0 +0000
				data <<END
				test: cleanup
				END
				from :1
				M 100644 inline Plugin.java
				data <<END
				final class Plugin
				{
					void buildsPlugin()
					{
						buildWithCleanup();
					}
				}
				END

				reset refs/target

				commit %1$s
				mark :3
				committer CAT Test <cat-test@example.invalid> 0 +0000
				data <<END
				test: feature
				END
				from :1
				M 100644 inline Plugin.java
				data <<END
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
				END

				get-mark :1
				get-mark :2
				get-mark :3
				""".formatted(git.lines("symbolic-ref", "HEAD").getFirst()));
			String base = commits.getFirst();
			String target = commits.get(1);
			String source = commits.getLast();
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = ReplayFixtures.editor(repository);
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
			GitTopicReplayEditor editor = ReplayFixtures.editor(repository);
			writeSourceManifest(git, manifest, base, source);
			editor.captureSourcePatch(manifest, source, replay.resolve("feature.patch"));
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"output_id":"output_1","disposition":"retained","batches":[{"id":"feature","source":"%s",
				"patch":"feature.patch"}]}]
				""".formatted(source));
			String tree = editor.writeDraftOutputTree(manifest, plan, "output_1");
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
		List<String> trees = git.lines("rev-parse", source + "^^{tree}", source + "^{tree}");
		SourceSnapshot snapshot = new SourceSnapshot(source, trees.getFirst(), trees.getLast());
		BatchManifest manifest = new BatchManifest(ManifestStore.BATCH_MANIFEST_VERSION, base, snapshot.tree(),
			List.of(snapshot),
			List.of(new Output("output_1", source, "test: selected source")), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), null);
		ReplayJson.newMapper().writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
	}
}
