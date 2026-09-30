package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/** Tests immutable capture/failure/restore record boundaries. */
public final class LiveGenerationCaptureRecordTest
{
	/** Failure evidence cannot be passed off as an admitted candidate record. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsFailureCheckpoint()
	{
		new LiveCandidateCapture(1, "capture", ref("live-generation-failures"), "call", hash(), hash(),
			ref("git-file-inventories"), ref("git-file-inventories"), ref("generation-tool-call-indexes"),
			ref("native-run-completions"), ref("live-generation-diffs"), List.of());
	}

	/** Failure captures preserve all denial refs and defensively copy path lists. */
	@Test
	public void failureRecordCopiesItsLists()
	{
		List<Path> paths = new ArrayList<>(List.of(Path.of("prompts/rule.md")));
		LiveGenerationFailureCapture capture = new LiveGenerationFailureCapture(1, "failure",
			ref("live-generation-checkpoints"), "call", hash(), ref("generation-tool-call-indexes"),
			ref("native-run-completions"), List.of(ref("generation-edit-receipts")),
			List.of(ref("generation-edit-rejections")), ref("git-file-inventories"),
			ref("git-file-inventories"), ref("live-generation-diffs"), paths);
		paths.clear();
		requireThat(capture.affectedPaths(), "affectedPaths").isEqualTo(List.of(Path.of("prompts/rule.md")));
		requireThat(capture.denialRefs().size(), "denialCount").isEqualTo(1);
	}

	/** Restore receipts require the actual journaled delivery receipt collection. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void restoreRejectsUnjournaledReceipt()
	{
		new LiveGenerationRestore(1, ref("live-generation-checkpoints"), ref("live-generation-captures"),
			hash(), ref("generation-edit-receipts"), List.of());
	}

	/** Changed paths in a diff cannot escape the project. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void diffRejectsAbsolutePath()
	{
		new LiveGenerationDiff(1, "head", hash(), List.of(Path.of("/tmp/escape")), hash(), hash());
	}

	/**
	 * Creates one typed test evidence reference.
	 *
	 * @param collection evidence collection
	 * @return test reference
	 */
	private static EvidenceRef ref(String collection)
	{
		return new EvidenceRef(collection, hash());
	}

	/**
	 * Returns a syntactically valid SHA-256.
	 *
	 * @return test digest
	 */
	private static String hash()
	{
		return "0".repeat(64);
	}
}
