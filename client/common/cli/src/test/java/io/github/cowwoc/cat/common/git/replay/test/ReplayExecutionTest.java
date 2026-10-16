package io.github.cowwoc.cat.common.git.replay.test;

import static io.github.cowwoc.cat.common.git.replay.test.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.test.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

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
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ReplayExecution}'s creation of reviewed commit trees and advancement of the current branch.
 */
public final class ReplayExecutionTest
{
	private static final String FEATURE_PATH = "feature.txt";
	private static final String INVALID_TEST_PATH = "structural-test.txt";
	private static final String ROUTING_PATH = "runtime-routing.txt";
	private static final String DOCUMENTED_FEATURE = "documented correction\n";
	private static final String ROUTING_CONTENT = "generated launcher runs routing behavior\n";
	private static final String FEATURE_SUBJECT = "feat: complete feature";
	private static final String ROUTING_SUBJECT = "feat: runtime routing";

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
			String base = git.lines("rev-parse", "HEAD").getFirst();
			List<SourceSnapshot> sources = createSources(git, repository, base);
			String branch = git.lines("symbolic-ref", "--short", "HEAD").getFirst();
			git.lines("tag", branch);
			Path manifestFile = invocationDirectory(repository, "replay").resolve("manifest.json");
			PatchArtifacts patches = new PatchArtifacts();
			BatchManifest manifest = createManifest(git, manifestFile, base, sources, patches);
			ManifestStore<BatchManifest> manifests = new ManifestStore<>(manifestFile.getParent(), ReplayJson.newMapper(),
				BatchManifest.class);
			manifests.write(manifestFile, manifest);

			String rewrittenTip = new ReplayExecution(git, new History(repository, git), manifests, patches).
				execute(manifestFile, manifest);

			assertCommittedOutputs(git, base, sources, rewrittenTip, branch);
		}
	}

	/**
	 * Creates three source snapshots containing a feature, its correction and invalid-test removal, and documentation.
	 *
	 * @param git Git access for the isolated repository
	 * @param repository the owned source-file directory
	 * @param base the empty commit before the source range
	 * @return the three captured source commits in chronological order
	 * @throws IOException if source files or Git objects cannot be created
	 */
	private List<SourceSnapshot> createSources(Git git, Path repository, String base) throws IOException
	{
		Files.writeString(repository.resolve(FEATURE_PATH), "original\n");
		Files.writeString(repository.resolve(INVALID_TEST_PATH), "claims runtime behavior but reads a manifest\n");
		SourceSnapshot feature = commitSource(git, base, History.EMPTY_TREE, "test: feature", "Feature Author",
			"2001-02-03T04:05:06+07:00");
		Files.writeString(repository.resolve(FEATURE_PATH), "corrected\n");
		Files.delete(repository.resolve(INVALID_TEST_PATH));
		Files.writeString(repository.resolve(ROUTING_PATH), ROUTING_CONTENT);
		SourceSnapshot correction = commitSource(git, feature.hash(), feature.tree(), "test: correction and routing",
			"Correction Author", "2002-03-04T05:06:07-08:00");
		Files.writeString(repository.resolve(FEATURE_PATH), DOCUMENTED_FEATURE);
		SourceSnapshot documentation = commitSource(git, correction.hash(), correction.tree(), "test: documentation",
			"Documentation Author", "2003-04-05T06:07:08+09:30");
		git.lines("update-ref", "HEAD", documentation.hash());
		return List.of(feature, correction, documentation);
	}

	/**
	 * Captures the current source files as a commit and returns its exact before-and-after tree facts.
	 *
	 * @param git Git access for the isolated repository
	 * @param parent the preceding source commit
	 * @param beforeTree the preceding source tree
	 * @param subject the source commit subject
	 * @param author the name of the source commit's author, which also names the author's email address
	 * @param date when the author wrote the source commit, in ISO 8601 format
	 * @return the created source snapshot without an additional history read
	 * @throws IOException if Git cannot capture the files or create the commit
	 */
	private SourceSnapshot commitSource(Git git, String parent, String beforeTree, String subject, String author,
		String date) throws IOException
	{
		git.lines("add", "--all");
		String tree = git.lines("write-tree").getFirst();
		Map<String, String> identity = Map.of("GIT_AUTHOR_NAME", author,
			"GIT_AUTHOR_EMAIL", author.replace(' ', '.') + "@example.invalid", "GIT_AUTHOR_DATE", date);
		String source = git.lines(identity, "commit-tree", tree, "-p", parent, "-m", subject).getFirst();
		return new SourceSnapshot(source, beforeTree, tree);
	}

	/**
	 * Captures four patch artifacts and their sealed tree transitions without repeating review or planning workflows.
	 *
	 * @param git Git access for the isolated repository
	 * @param manifestFile the manifest that owns the captured patches
	 * @param base the commit preceding the replay range
	 * @param sources the three captured source snapshots
	 * @param patches the patch declaration owner
	 * @return the execution facts for two retained outputs and one discarded logical output
	 * @throws IOException if patches or projected output trees cannot be captured
	 */
	private BatchManifest createManifest(Git git, Path manifestFile, String base, List<SourceSnapshot> sources,
		PatchArtifacts patches) throws IOException
	{
		SourceSnapshot feature = sources.getFirst();
		SourceSnapshot correction = sources.get(1);
		SourceSnapshot documentation = sources.getLast();
		Path directory = manifestFile.getParent();
		Path featurePatch = directory.resolve("feature.patch");
		Files.write(featurePatch, git.bytes("diff", "--binary", feature.parentTree(), feature.tree()));
		Path correctionPatch = directory.resolve("correction.patch");
		Files.write(correctionPatch, git.bytes("diff", "--binary", correction.parentTree(), correction.tree(), "--",
			FEATURE_PATH, INVALID_TEST_PATH));
		Path documentationPatch = directory.resolve("documentation.patch");
		Files.write(documentationPatch, git.bytes("diff", "--binary", documentation.parentTree(), documentation.tree(),
			"--", FEATURE_PATH));
		Path routingPatch = directory.resolve("routing.patch");
		Files.write(routingPatch, git.bytes("diff", "--binary", correction.parentTree(), correction.tree(), "--",
			ROUTING_PATH));

		Path index = directory.resolve("projection.index");
		String correctedTree = projectWithoutRouting(git, index, correction.tree());
		String documentedTree = projectWithoutRouting(git, index, documentation.tree());
		Files.delete(index);

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
		return new BatchManifest(ManifestStore.BATCH_MANIFEST_VERSION, base, documentation.tree(), sources,
			List.of(featureOutput, routingOutput, documentationOutput), List.of(), batches, List.of(),
			List.of(new OutputWitness(featureOutput.id(), History.EMPTY_TREE, documentedTree),
				new OutputWitness(routingOutput.id(), documentedTree, documentation.tree())),
			List.of(documentationOutput.id()), List.of(), List.of(), plan);
	}

	/**
	 * Returns one source tree without its independent routing file, using the shared fixture index.
	 *
	 * @param git Git access for the isolated repository
	 * @param index the manifest-owned projection index
	 * @param tree the captured source tree containing the routing file
	 * @return the feature-only tree
	 * @throws IOException if Git cannot construct the projected tree
	 */
	private String projectWithoutRouting(Git git, Path index, String tree) throws IOException
	{
		git.lines(index, "read-tree", tree);
		git.lines(index, "update-index", "--force-remove", ROUTING_PATH);
		return git.lines(index, "write-tree").getFirst();
	}

	/**
	 * Verifies the actual created commits, their authors, their file bytes, the advanced branch, and the unchanged
	 * same-name tag.
	 *
	 * @param git Git access for the replayed repository
	 * @param base the unchanged parent of the first replacement commit
	 * @param sources the three source snapshots, in chronological order
	 * @param rewrittenTip the final replacement commit returned by replay
	 * @param branch the current branch name shared by the tag
	 * @throws IOException if Git cannot read the resulting commits or references
	 */
	private void assertCommittedOutputs(Git git, String base, List<SourceSnapshot> sources, String rewrittenTip,
		String branch) throws IOException
	{
		String sourceTip = sources.getLast().hash();
		List<String> commits = git.lines("log", "--reverse", "--format=%H%x09%P%x09%s", base + "..HEAD");
		requireThat(commits.size(), "replacementCount").isEqualTo(2);
		String[] feature = commits.getFirst().split("\t", 3);
		String[] routing = commits.getLast().split("\t", 3);
		requireThat(feature[1], "featureParent").isEqualTo(base);
		requireThat(feature[2], "featureSubject").isEqualTo(FEATURE_SUBJECT);
		requireThat(routing[1], "routingParent").isEqualTo(feature[0]);
		requireThat(routing[2], "routingSubject").isEqualTo(ROUTING_SUBJECT);
		requireThat(routing[0], "lastReplacement").isEqualTo(rewrittenTip);
		List<String> tips = git.lines("rev-parse", "HEAD", "refs/tags/" + branch);
		requireThat(tips.getFirst(), "branchTip").isEqualTo(rewrittenTip);
		requireThat(tips.getLast(), "tagTip").isEqualTo(sourceTip);
		List<String> authors = git.lines("show", "-s", "--format=%an%x09%ae%x09%aI", feature[0], routing[0],
			sources.getFirst().hash(), sources.get(1).hash());
		requireThat(authors.get(2), "featureSourceAuthor").
			isEqualTo("Feature Author\tFeature.Author@example.invalid\t2001-02-03T04:05:06+07:00");
		requireThat(authors.getFirst(), "featureAuthor").isEqualTo(authors.get(2));
		requireThat(authors.get(1), "routingAuthor").isEqualTo(authors.get(3));

		requireThat(new String(git.bytes("show", feature[0] + ":" + FEATURE_PATH), UTF_8), "featureOutputBytes").
			isEqualTo(DOCUMENTED_FEATURE);
		requireThat(git.lines("ls-tree", "--name-only", feature[0]), "featureOutputPaths").
			isEqualTo(List.of(FEATURE_PATH));
		requireThat(new String(git.bytes("show", routing[0] + ":" + FEATURE_PATH), UTF_8), "finalFeatureBytes").
			isEqualTo(DOCUMENTED_FEATURE);
		requireThat(new String(git.bytes("show", routing[0] + ":" + ROUTING_PATH), UTF_8), "routingOutputBytes").
			isEqualTo(ROUTING_CONTENT);
		requireThat(git.lines("ls-tree", "--name-only", routing[0]), "routingOutputPaths").
			isEqualTo(List.of(FEATURE_PATH, ROUTING_PATH));
	}
}
