package io.github.cowwoc.cat.common.git.replay.test;

import static io.github.cowwoc.cat.common.git.replay.test.TopicReplayRepository.fastImport;
import static io.github.cowwoc.cat.common.git.replay.test.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.test.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.git.replay.Batch;
import io.github.cowwoc.cat.common.git.replay.BatchManifest;
import io.github.cowwoc.cat.common.git.replay.DestinationWitness;
import io.github.cowwoc.cat.common.git.replay.History;
import io.github.cowwoc.cat.common.git.replay.ManifestStore;
import io.github.cowwoc.cat.common.git.replay.Output;
import io.github.cowwoc.cat.common.git.replay.OutputPlan;
import io.github.cowwoc.cat.common.git.replay.OutputWitness;
import io.github.cowwoc.cat.common.git.replay.PatchArtifacts;
import io.github.cowwoc.cat.common.git.replay.PlannedBatch;
import io.github.cowwoc.cat.common.git.replay.PlannedOutput;
import io.github.cowwoc.cat.common.git.replay.ReplayExecution;
import io.github.cowwoc.cat.common.git.replay.ReplayJson;
import io.github.cowwoc.cat.common.git.replay.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link ReplayExecution}'s creation of reviewed commit trees and advancement of the current branch.
 */
public final class ReplayExecutionTest
{
	private static final String FEATURE_SUBJECT = "feat: complete feature";
	private static final String ROUTING_SUBJECT = "feat: runtime routing";
	private static final String FEATURE_AUTHOR = "Feature Author <Feature.Author@example.invalid>";
	private static final String FEATURE_DATE = "2001-02-03T04:05:06+07:00";
	private static final String CORRECTION_AUTHOR = "Correction Author <Correction.Author@example.invalid>";
	private static final String CORRECTION_DATE = "2002-03-04T05:06:07-08:00";
	private static final String DOCUMENTATION_AUTHOR = "Documentation Author <Documentation.Author@example.invalid>";
	private static final String DOCUMENTATION_DATE = "2003-04-05T06:07:08+09:30";

	/**
	 * Creates the test fixture.
	 */
	public ReplayExecutionTest()
	{
	}

	/**
	 * Verifies three successive feature batches are folded into the first commit, independent routing stays in the
	 * second, and replay advances the branch without changing a tag with the branch's name.
	 *
	 * @throws IOException if the isolated repository, sealed patch facts, or replay cannot be created
	 */
	@Test
	public void replaysOrderedBatchesWithMatchingTag() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "replay-execution-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			List<String> head = git.lines("rev-parse", "HEAD", "--abbrev-ref", "HEAD");
			String base = head.getFirst();
			String branch = head.getLast();
			SourceHistory history = importSources(repository, base, branch);
			Path manifestFile = invocationDirectory(repository, "replay").resolve("manifest.json");
			PatchArtifacts patches = new PatchArtifacts();
			BatchManifest manifest = createManifest(manifestFile, base, history, patches);
			ManifestStore<BatchManifest> manifests = new ManifestStore<>(manifestFile.getParent(), ReplayJson.newMapper(),
				BatchManifest.class);
			manifests.write(manifestFile, manifest);

			String rewrittenTip = new ReplayExecution(git, new History(repository, git), manifests, patches).
				execute(manifestFile, manifest);

			assertCommittedOutputs(git, base, history, rewrittenTip, branch);
		}
	}

	/**
	 * The source commits that replay rewrites, and the trees that the feature output has after its second and third
	 * batches.
	 *
	 * @param sources the feature, correction-and-routing, and documentation source commits, in chronological order
	 * @param correctedTree the correction source's tree without the routing file
	 * @param documentedTree the documentation source's tree without the routing file
	 */
	private record SourceHistory(List<SourceSnapshot> sources, String correctedTree, String documentedTree)
	{
	}

	/**
	 * Creates three source commits on the current branch, a tag with the branch's name at the last of them, and the
	 * feature output's expected intermediate trees, all in one Git process.
	 * <p>
	 * The first commit adds a feature and a test that a later commit removes as invalid; the second corrects the
	 * feature, removes that test, and adds an independent routing file; the third documents the feature.
	 *
	 * @param repository the repository whose current branch receives the commits
	 * @param base the commit at the tip of the current branch
	 * @param branch the name of the current branch
	 * @return the created commits and trees
	 * @throws IOException if Git cannot create the commits
	 */
	private SourceHistory importSources(Path repository, String base, String branch) throws IOException
	{
		String stream = """
			commit refs/heads/%1$s
			mark :1
			author %3$s
			committer %3$s
			data <<END
			test: feature
			END
			from %2$s
			M 100644 inline feature.txt
			data <<END
			original
			END
			M 100644 inline structural-test.txt
			data <<END
			claims runtime behavior but reads a manifest
			END

			commit refs/heads/%1$s
			mark :2
			author %4$s
			committer %4$s
			data <<END
			test: correction and routing
			END
			M 100644 inline feature.txt
			data <<END
			corrected
			END
			D structural-test.txt
			M 100644 inline runtime-routing.txt
			data <<END
			generated launcher runs routing behavior
			END

			commit refs/heads/%1$s
			mark :3
			author %5$s
			committer %5$s
			data <<END
			test: documentation
			END
			M 100644 inline feature.txt
			data <<END
			documented correction
			END

			reset refs/tags/%1$s
			from :3

			commit refs/projections
			mark :4
			committer %3$s
			data <<END
			corrected feature without routing
			END
			M 100644 inline feature.txt
			data <<END
			corrected
			END

			commit refs/projections
			mark :5
			committer %3$s
			data <<END
			documented feature without routing
			END
			M 100644 inline feature.txt
			data <<END
			documented correction
			END

			reset refs/projections

			get-mark :1
			ls :1 ""
			get-mark :2
			ls :2 ""
			get-mark :3
			ls :3 ""
			ls :4 ""
			ls :5 ""
			""".formatted(branch, base, identity(FEATURE_AUTHOR, FEATURE_DATE),
			identity(CORRECTION_AUTHOR, CORRECTION_DATE), identity(DOCUMENTATION_AUTHOR, DOCUMENTATION_DATE));
		// The stream's last "reset refs/projections" has no "from", so Git never creates that reference and only the two
		// projected trees remain
		List<String> objects = fastImport(repository, stream);
		SourceSnapshot feature = new SourceSnapshot(objects.getFirst(), History.EMPTY_TREE, tree(objects.get(1)));
		SourceSnapshot correction = new SourceSnapshot(objects.get(2), feature.tree(), tree(objects.get(3)));
		SourceSnapshot documentation = new SourceSnapshot(objects.get(4), correction.tree(), tree(objects.get(5)));
		return new SourceHistory(List.of(feature, correction, documentation), tree(objects.get(6)),
			tree(objects.get(7)));
	}

	/**
	 * Returns a Git identity in the raw form that {@code git fast-import} reads.
	 *
	 * @param person the person's name and email address, such as {@code Name <name@example.invalid>}
	 * @param date when the person acted, in ISO 8601 format with a UTC offset
	 * @return the identity followed by the time in seconds since the epoch and the UTC offset as {@code +hhmm}
	 */
	private static String identity(String person, String date)
	{
		OffsetDateTime time = OffsetDateTime.parse(date);
		return person + " " + time.toEpochSecond() + " " + time.getOffset().getId().replace(":", "");
	}

	/**
	 * Returns the tree hash from a line that the fast-import {@code ls} command printed for a commit's root.
	 *
	 * @param line the printed line, such as {@code 040000 tree <hash>} followed by a tab
	 * @return the tree hash
	 */
	private static String tree(String line)
	{
		return line.split("[ \t]")[2];
	}

	/**
	 * Writes the four batch patches and returns the execution facts for two retained outputs and one discarded logical
	 * output.
	 * <p>
	 * The feature output receives the feature, its correction without the routing file, and the documentation; the
	 * routing output receives only the routing file from the correction source.
	 *
	 * @param manifestFile the manifest that owns the patches
	 * @param base the commit preceding the replay range
	 * @param history the source commits and the feature output's intermediate trees
	 * @param patches the patch declaration owner
	 * @return the execution facts
	 * @throws IOException if the patches cannot be written
	 */
	private BatchManifest createManifest(Path manifestFile, String base, SourceHistory history,
		PatchArtifacts patches) throws IOException
	{
		SourceSnapshot feature = history.sources().getFirst();
		SourceSnapshot correction = history.sources().get(1);
		SourceSnapshot documentation = history.sources().getLast();
		String correctedTree = history.correctedTree();
		String documentedTree = history.documentedTree();
		Path directory = manifestFile.getParent();
		Path featurePatch = Files.writeString(directory.resolve("feature.patch"), """
			diff --git a/feature.txt b/feature.txt
			new file mode 100644
			--- /dev/null
			+++ b/feature.txt
			@@ -0,0 +1 @@
			+original
			diff --git a/structural-test.txt b/structural-test.txt
			new file mode 100644
			--- /dev/null
			+++ b/structural-test.txt
			@@ -0,0 +1 @@
			+claims runtime behavior but reads a manifest
			""");
		Path correctionPatch = Files.writeString(directory.resolve("correction.patch"), """
			diff --git a/feature.txt b/feature.txt
			--- a/feature.txt
			+++ b/feature.txt
			@@ -1 +1 @@
			-original
			+corrected
			diff --git a/structural-test.txt b/structural-test.txt
			deleted file mode 100644
			--- a/structural-test.txt
			+++ /dev/null
			@@ -1 +0,0 @@
			-claims runtime behavior but reads a manifest
			""");
		Path documentationPatch = Files.writeString(directory.resolve("documentation.patch"), """
			diff --git a/feature.txt b/feature.txt
			--- a/feature.txt
			+++ b/feature.txt
			@@ -1 +1 @@
			-corrected
			+documented correction
			""");
		Path routingPatch = Files.writeString(directory.resolve("routing.patch"), """
			diff --git a/runtime-routing.txt b/runtime-routing.txt
			new file mode 100644
			--- /dev/null
			+++ b/runtime-routing.txt
			@@ -0,0 +1 @@
			+generated launcher runs routing behavior
			""");

		Output featureOutput = new Output("feature", feature.hash(), FEATURE_SUBJECT);
		Output routingOutput = new Output("routing", null, ROUTING_SUBJECT);
		Output documentationOutput = new Output("documentation", documentation.hash(), "docs: absorbed documentation");
		List<Batch> batches = List.of(
			new Batch("feature", feature.hash(), featureOutput.id(), patches.declare(manifestFile, featurePatch),
				new DestinationWitness(History.EMPTY_TREE, feature.tree())),
			new Batch("correction", correction.hash(), featureOutput.id(), patches.declare(manifestFile, correctionPatch),
				new DestinationWitness(feature.tree(), correctedTree)),
			new Batch("documentation", documentation.hash(), featureOutput.id(),
				patches.declare(manifestFile, documentationPatch), new DestinationWitness(correctedTree, documentedTree)),
			new Batch("routing", correction.hash(), routingOutput.id(), patches.declare(manifestFile, routingPatch),
				new DestinationWitness(documentedTree, documentation.tree())));
		List<PlannedBatch> featureBatches = batches.subList(0, 3).stream().map(batch ->
			new PlannedBatch(batch.id(), batch.source(), batch.patch())).toList();
		Batch routing = batches.getLast();
		OutputPlan plan = new OutputPlan(List.of(new PlannedOutput(featureOutput.id(), "retained", featureBatches),
			new PlannedOutput(routingOutput.id(), "retained",
				List.of(new PlannedBatch(routing.id(), routing.source(), routing.patch()))),
			new PlannedOutput(documentationOutput.id(), "discarded", List.of())));
		return new BatchManifest(ManifestStore.BATCH_MANIFEST_VERSION, base, documentation.tree(), history.sources(),
			List.of(featureOutput, routingOutput, documentationOutput), List.of(), batches, List.of(),
			List.of(new OutputWitness(featureOutput.id(), History.EMPTY_TREE, documentedTree),
				new OutputWitness(routingOutput.id(), documentedTree, documentation.tree())),
			List.of(documentationOutput.id()), List.of(), List.of(), plan);
	}

	/**
	 * Verifies the created commits' parents, subjects, authors, and trees, the advanced branch, and the unchanged
	 * same-name tag.
	 *
	 * @param git Git access for the replayed repository
	 * @param base the unchanged parent of the first replacement commit
	 * @param history the source commits and the feature output's expected tree
	 * @param rewrittenTip the final replacement commit returned by replay
	 * @param branch the current branch name shared by the tag
	 * @throws IOException if Git cannot read the resulting commits or references
	 */
	private void assertCommittedOutputs(Git git, String base, SourceHistory history, String rewrittenTip,
		String branch) throws IOException
	{
		SourceSnapshot documentation = history.sources().getLast();
		List<String> commits = git.lines("log", "--reverse", "--format=%H%x09%P%x09%T%x09%an <%ae>%x09%aI%x09%s",
			base + "..HEAD");
		requireThat(commits.size(), "replacementCount").isEqualTo(2);
		String[] feature = commits.getFirst().split("\t", 6);
		String[] routing = commits.getLast().split("\t", 6);
		requireThat(feature[1], "featureParent").isEqualTo(base);
		requireThat(feature[5], "featureSubject").isEqualTo(FEATURE_SUBJECT);
		requireThat(feature[3] + "\t" + feature[4], "featureAuthor").isEqualTo(FEATURE_AUTHOR + "\t" + FEATURE_DATE);
		// The documented tree holds only feature.txt with the documentation source's bytes
		requireThat(feature[2], "featureTree").isEqualTo(history.documentedTree());
		requireThat(routing[1], "routingParent").isEqualTo(feature[0]);
		requireThat(routing[5], "routingSubject").isEqualTo(ROUTING_SUBJECT);
		requireThat(routing[3] + "\t" + routing[4], "routingAuthor").
			isEqualTo(CORRECTION_AUTHOR + "\t" + CORRECTION_DATE);
		requireThat(routing[2], "routingTree").isEqualTo(documentation.tree());
		requireThat(routing[0], "lastReplacement").isEqualTo(rewrittenTip);
		List<String> tips = git.lines("rev-parse", "HEAD", "refs/tags/" + branch);
		requireThat(tips.getFirst(), "branchTip").isEqualTo(rewrittenTip);
		requireThat(tips.getLast(), "tagTip").isEqualTo(documentation.hash());
	}
}
