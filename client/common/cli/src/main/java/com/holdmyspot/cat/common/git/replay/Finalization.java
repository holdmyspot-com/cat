package com.holdmyspot.cat.common.git.replay;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes and verifies the immutable finalization receipt for a topic replay.
 *
 * <p>
 * The editor supplies only sealed replay facts after it has applied manifest and semantic-plan policy. This owner
 * then binds those facts, the replacement chain, and retained evidence to the terminal receipt.
 */
public final class Finalization
{
	private static final int RECEIPT_VERSION = 1;
	private final Git git;
	private final History history;
	private final JsonMapper json;

	/**
	 * Creates the finalization owner for one Git repository.
	 *
	 * @param git the repository Git boundary
	 * @param history the immutable replacement-chain reader
	 * @param json the process JSON mapper
	 */
	public Finalization(Git git, History history, JsonMapper json)
	{
		requireThat(git, "git").isNotNull();
		requireThat(history, "history").isNotNull();
		requireThat(json, "json").isNotNull();
		this.git = git;
		this.history = history;
		this.json = json;
	}

	/**
	 * Derives the immutable receipt facts from one sealed replay manifest.
	 *
	 * @param manifest the sealed manifest whose finalization receipt is being published or verified
	 * @return the replay facts that the receipt must bind
	 */
	static Replay replay(BatchManifest manifest)
	{
		Set<String> destinations = manifest.batches().stream().map(Batch::destination).collect(Collectors.toSet());
		List<Output> outputs = manifest.outputs().stream().map(output ->
			new Output(output.id(), destinations.contains(output.id()))).toList();
		List<OutputWitness> witnesses = manifest.outputWitnesses().stream().map(witness ->
			new OutputWitness(witness.outputId(), witness.beforeTree(), witness.afterTree())).toList();
		SourceSnapshot sourceTip = manifest.sources().getLast();
		return new Replay(manifest.base(), sourceTip.hash(), sourceTip.tree(), manifest.tipTree(), outputs, witnesses);
	}

	/**
	 * Publishes a receipt, or verifies the existing receipt before retaining the replay backup.
	 *
	 * @param manifestFile the manifest that owns the receipt and evidence
	 * @param replay the sealed replay facts
	 * @param rewrittenTip the final replacement commit
	 * @throws IOException if finalization evidence is incomplete or publication fails
	 */
	public void publish(Path manifestFile, Replay replay, String rewrittenTip) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(replay, "replay").isNotNull();
		requireThat(rewrittenTip, "rewrittenTip").isNotBlank();
		Path receiptFile = receiptFile(manifestFile);
		if (Files.exists(receiptFile))
		{
			verify(manifestFile, replay, replay.base(), rewrittenTip);
			return;
		}
		requirePerOutputGateEvidence(manifestFile, replay.outputs());
		List<History.RangeCommit> chain = history.rangeWithParents(replay.base(), rewrittenTip);
		List<FinalizedOutput> outputs = finalizedOutputs(replay, chain);
		String manifestDigest = sha256(manifestFile);
		String identityDigest = replayIdentityDigest(chain);
		publishReceipt(manifestFile, captureReceipt(manifestFile, replay, rewrittenTip, outputs, manifestDigest,
			identityDigest));
	}

	/**
	 * Verifies that a receipt binds one sealed replay to its final replacement chain and evidence.
	 *
	 * @param manifestFile the manifest that owns the receipt and evidence
	 * @param replay the sealed replay facts
	 * @param rebaseBase the expected commit immediately before the replay range
	 * @param rewrittenTip the expected final replacement commit
	 * @throws IOException if the receipt is absent, stale, or does not bind the requested replay
	 */
	public void verify(Path manifestFile, Replay replay, String rebaseBase, String rewrittenTip) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(replay, "replay").isNotNull();
		requireThat(rebaseBase, "rebaseBase").isNotBlank();
		requireThat(rewrittenTip, "rewrittenTip").isNotBlank();
		List<String> resolved = git.lines("rev-parse", rewrittenTip, "HEAD");
		String expectedRewrittenTip = resolved.getFirst();
		String head = resolved.get(1);
		Path receiptFile = requireReceipt(manifestFile);
		FinalizationReceipt receipt = json.readValue(receiptFile.toFile(), FinalizationReceipt.class);
		if (!matchesSelection(receipt, rebaseBase, expectedRewrittenTip) || !head.equals(expectedRewrittenTip))
		{
			throw new IOException("Topic-replay finalization receipt does not bind the requested base and HEAD; " +
				"prepare a fresh whole-range replay");
		}
		verifyManifestDigest(manifestFile, receipt);
		if (!matchesSourceFacts(receipt, replay) ||
			!git.lines("rev-parse", replay.sourceTip() + "^{tree}").getFirst().equals(replay.sourceTipTree()))
		{
			throw new IOException("Topic-replay finalization receipt no longer matches captured source facts: " +
				receiptFile);
		}
		List<History.RangeCommit> chain = history.rangeWithParents(rebaseBase, expectedRewrittenTip);
		if (!matchesChainOutputs(receipt, replay, chain) ||
			!receipt.identityDigest().equals(replayIdentityDigest(chain)))
		{
			throw new IOException("Topic-replay finalization receipt does not match the ordered replacement chain: " +
				receiptFile);
		}
		verifyFinalizationEvidence(manifestFile, receipt.evidence());
		System.out.println("verifiedFinalization=" + receiptFile);
	}

	/**
	 * Requires the manifest-local receipt that marks a replay history as reviewed.
	 *
	 * @param manifestFile the replay manifest
	 * @return the existing receipt path
	 * @throws IOException if the receipt is absent and the history is therefore unreviewed
	 */
	public Path requireReceipt(Path manifestFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		Path receiptFile = receiptFile(manifestFile);
		if (!Files.isRegularFile(receiptFile))
		{
			throw new IOException("Topic-replay finalization receipt is missing: " + receiptFile +
				"; this history is unreviewed, so prepare a fresh whole-range replay");
		}
		return receiptFile;
	}

	/**
	 * Verifies that every replacement commit uses one approved author and committer identity.
	 *
	 * @param rebaseBase the commit immediately before the replacement range
	 * @param rewrittenTip the replacement range's final commit
	 * @param name the approved author and committer name
	 * @param email the approved author and committer email
	 * @throws IOException if Git cannot read the range or any identity differs from the approved policy
	 */
	public void verifyIdentity(String rebaseBase, String rewrittenTip, String name, String email) throws IOException
	{
		requireThat(rebaseBase, "rebaseBase").isNotBlank();
		requireThat(rewrittenTip, "rewrittenTip").isNotBlank();
		requireThat(name, "name").isNotBlank();
		requireThat(email, "email").isNotBlank();
		for (String commit : history.range(history.resolveBase(rebaseBase), rewrittenTip))
		{
			verifyCommitIdentity(commit, git.bytes("show", "-s", "--format=%an%x00%ae%x00%cn%x00%ce", commit), name,
				email);
		}
	}

	/**
	 * Captures canonical receipt facts and current filesystem evidence after their repository facts are verified.
	 *
	 * @param manifestFile the manifest owning the evidence
	 * @param replay the captured replay facts
	 * @param rewrittenTip the verified replacement tip
	 * @param outputs the ordered verified replacement transitions
	 * @param manifestDigest the manifest digest acquired before the identity digest
	 * @param identityDigest the replacement identity digest
	 * @return the receipt ready for atomic publication
	 * @throws IOException if retained evidence cannot be captured
	 */
	public FinalizationReceipt captureReceipt(Path manifestFile, Replay replay, String rewrittenTip,
		List<FinalizedOutput> outputs, String manifestDigest, String identityDigest) throws IOException
	{
		return new FinalizationReceipt(RECEIPT_VERSION, manifestDigest, replay.base(), replay.sourceTip(),
			replay.sourceTipTree(), replay.finalTree(), rewrittenTip, outputs, identityDigest,
			finalizationEvidence(manifestFile, receiptFile(manifestFile)));
	}

	/**
	 * Atomically publishes receipt facts acquired by the finalization owner.
	 *
	 * @param manifestFile the manifest owning the receipt
	 * @param receipt the captured facts and evidence
	 * @throws IOException if atomic receipt publication fails
	 */
	public void publishReceipt(Path manifestFile, FinalizationReceipt receipt) throws IOException
	{
		Path receiptFile = receiptFile(manifestFile);
		writeAtomically(receiptFile, receipt);
		System.out.println("finalizationReceipt=" + receiptFile);
	}

	/**
	 * Indicates whether a decoded receipt binds the selected base and resolved replacement tip.
	 *
	 * @param receipt the decoded receipt
	 * @param base the selected base
	 * @param tip the resolved selected tip
	 * @return whether the recorded selection matches
	 */
	public static boolean matchesSelection(FinalizationReceipt receipt, String base, String tip)
	{
		return receipt.version() == RECEIPT_VERSION && receipt.base().equals(base) && receipt.rewrittenTip().equals(tip);
	}

	/**
	 * Indicates whether a decoded receipt retains the captured source and final-tree facts.
	 *
	 * @param receipt the decoded receipt
	 * @param replay the captured replay facts
	 * @return whether all captured facts match
	 */
	public static boolean matchesSourceFacts(FinalizationReceipt receipt, Replay replay)
	{
		return receipt.base().equals(replay.base()) && receipt.sourceTip().equals(replay.sourceTip()) &&
			receipt.sourceTipTree().equals(replay.sourceTipTree()) && receipt.finalTree().equals(replay.finalTree());
	}

	/**
	 * Indicates whether a receipt binds the ordered replacement transitions.
	 *
	 * @param receipt the decoded receipt
	 * @param replay the sealed output decisions
	 * @param chain the captured replacement commits and parents
	 * @return whether the ordered output facts match
	 * @throws IOException if the replacement chain cannot be bound to the sealed outputs
	 */
	public static boolean matchesChainOutputs(FinalizationReceipt receipt, Replay replay, List<History.RangeCommit> chain)
		throws IOException
	{
		return receipt.outputs().equals(finalizedOutputs(replay, chain));
	}

	/**
	 * Verifies a receipt's manifest content using the actual filesystem digest.
	 *
	 * @param manifestFile the manifest bound by the receipt
	 * @param receipt the decoded receipt
	 * @throws IOException if the manifest changed or cannot be read
	 */
	public void verifyManifestDigest(Path manifestFile, FinalizationReceipt receipt) throws IOException
	{
		if (!receipt.manifestSha256().equals(sha256(manifestFile)))
		{
			throw new IOException("Topic-replay finalization receipt does not match its manifest: " +
				receiptFile(manifestFile));
		}
	}

	/**
	 * Interprets Git's four identity fields and requires the approved author and committer.
	 *
	 * @param commit the inspected replacement commit
	 * @param bytes the NUL-delimited author and committer name/email fields
	 * @param name the approved name
	 * @param email the approved email
	 * @throws IOException if any decoded identity differs or a field is missing
	 */
	public static void verifyCommitIdentity(String commit, byte[] bytes, String name, String email) throws IOException
	{
		String[] identity = new String(bytes, UTF_8).strip().split("\\x00", -1);
		if (identity.length != 4 || !identity[0].equals(name) || !identity[1].equals(email) ||
			!identity[2].equals(name) || !identity[3].equals(email))
		{
			throw new IOException("Topic-replay commit " + commit + " does not use the approved author and committer " +
				"identity " + name + " <" + email + ">; correct Git identity, restart from the recorded backup, and " +
				"replay the manifest");
		}
	}

	/**
	 * Returns the receipt path owned by one replay manifest.
	 *
	 * @param manifestFile the replay manifest
	 * @return the manifest-local receipt path
	 */
	private Path receiptFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".finalization-receipt.json");
	}

	/**
	 * Resolves the ordered replacement chain and binds each retained output to its witnessed tree transition.
	 *
	 * @param replay the sealed replay facts
	 * @param chain the ordered replacement commits with their parents
	 * @return the receipt-ready ordered outputs
	 * @throws IOException if the replacement chain is incomplete or a witness is absent
	 */
	public static List<FinalizedOutput> finalizedOutputs(Replay replay, List<History.RangeCommit> chain)
		throws IOException
	{
		List<Output> expected = replay.outputs().stream().filter(Output::retained).toList();
		if (chain.size() != expected.size())
			throw new IOException("Topic-replay finalization cannot bind an incomplete replacement chain");
		List<FinalizedOutput> result = new ArrayList<>();
		for (int index = 0; index < chain.size(); ++index)
		{
			History.RangeCommit commit = chain.get(index);
			OutputWitness witness = replay.witnessFor(expected.get(index).id());
			result.add(new FinalizedOutput(expected.get(index).id(), commit.hash(), commit.parents(),
				witness.beforeTree(), witness.afterTree()));
		}
		return result;
	}

	/**
	 * Captures each retained manifest-local artifact except the receipt being published.
	 *
	 * @param manifestFile the manifest that owns the evidence directory
	 * @param receiptFile the receipt excluded from its own evidence
	 * @return evidence digests in stable relative-path order
	 * @throws IOException if an artifact cannot be read
	 */
	private List<ArtifactDigest> finalizationEvidence(Path manifestFile, Path receiptFile) throws IOException
	{
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		try (Stream<Path> paths = Files.walk(directory))
		{
			List<Path> files = paths.filter(Files::isRegularFile).map(Path::toAbsolutePath).map(Path::normalize).
				filter(path -> !path.equals(receiptFile.toAbsolutePath().normalize())).
				sorted(Comparator.comparing(Path::toString)).toList();
			List<ArtifactDigest> result = new ArrayList<>();
			for (Path file : files)
				result.add(new ArtifactDigest(PortablePaths.text(directory.relativize(file)), sha256(file)));
			return result;
		}
	}

	/**
	 * Verifies that receipt-bound evidence still has its recorded content below the manifest directory.
	 *
	 * @param manifestFile the manifest that owns the evidence directory
	 * @param evidence the digests read from the receipt
	 * @throws IOException if evidence is absent, escapes the directory, is missing, or changed
	 */
	public void verifyFinalizationEvidence(Path manifestFile, List<ArtifactDigest> evidence) throws IOException
	{
		if (evidence == null || evidence.isEmpty())
			throw new IOException("Topic-replay finalization receipt has no evidence artifact digests");
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		for (ArtifactDigest artifact : evidence)
		{
			Path file = directory.resolve(artifact.path()).normalize();
			if (!file.startsWith(directory) || !Files.isRegularFile(file) || !artifact.sha256().equals(sha256(file)))
				throw new IOException("Topic-replay finalization evidence changed or is missing: " + artifact.path());
		}
	}

	/**
	 * Computes the author and committer identity digest for one replacement chain.
	 *
	 * @param chain the ordered replacement commits
	 * @return the chain's identity digest
	 * @throws IOException if Git cannot read the replacement commits
	 */
	private String replayIdentityDigest(List<History.RangeCommit> chain) throws IOException
	{
		List<String> arguments = new ArrayList<>(List.of("show", "-s",
			"--format=%H%x00%an%x00%ae%x00%cn%x00%ce"));
		for (History.RangeCommit commit : chain)
			arguments.add(commit.hash());
		return sha256(git.bytes(arguments.toArray(String[]::new)));
	}

	/**
	 * Publishes one JSON value atomically, removing its temporary file after every outcome.
	 *
	 * @param target the receipt path to publish
	 * @param value the JSON-serializable receipt
	 * @throws IOException if JSON cannot be written or atomic publication is unavailable
	 */
	private void writeAtomically(Path target, Object value) throws IOException
	{
		Path directory = target.toAbsolutePath().normalize().getParent();
		Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
		try
		{
			json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
			Files.move(temporary, target, ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			throw new IOException("Topic-replay finalization requires atomic receipt publication: " + target, e);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Requires successful build and behavior-acceptance evidence for each retained output.
	 *
	 * @param manifestFile the manifest that owns the gate logs
	 * @param replay the sealed replay facts whose retained outputs require evidence
	 * @throws IOException if a retained output lacks required gate evidence
	 */
	public void requirePerOutputGateEvidence(Path manifestFile, Replay replay) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(replay, "replay").isNotNull();
		requirePerOutputGateEvidence(manifestFile, replay.outputs());
	}

	/**
	 * Checks the required build and acceptance logs for each retained output.
	 *
	 * @param manifestFile the manifest that owns the gate logs
	 * @param outputs the logical outputs to inspect
	 * @throws IOException if a retained output lacks either required log
	 */
	private void requirePerOutputGateEvidence(Path manifestFile, List<Output> outputs) throws IOException
	{
		Path gateDirectory = manifestFile.toAbsolutePath().normalize().getParent().resolve("gates");
		for (Output output : outputs)
		{
			if (!output.retained())
				continue;
			for (String gateName : List.of("per-output-build", "per-output-acceptance"))
			{
				Path gateLog = gateDirectory.resolve(gateName + "-" + output.id() + ".log");
				if (!Files.isRegularFile(gateLog))
				{
					throw new IOException("Topic-replay finalization requires " + gateName + " evidence for output " +
						output.id() + "; run git-topic-replay run-output-gate " + manifestFile + " " +
						gateName + " COMMAND...");
				}
			}
		}
	}

	/**
	 * Computes a file's SHA-256 digest without loading its complete content into memory.
	 *
	 * @param input the file to hash
	 * @return the lowercase hexadecimal digest
	 * @throws IOException if the artifact cannot be read or SHA-256 is unavailable
	 */
	public String sha256(Path input) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream stream = new DigestInputStream(Files.newInputStream(input), digest))
			{
				byte[] buffer = new byte[8192];
				int read = stream.read(buffer);
				while (read != -1)
					read = stream.read(buffer);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("The JDK does not provide SHA-256", e);
		}
	}

	/**
	 * Computes an in-memory value's SHA-256 digest.
	 *
	 * @param input the value to hash
	 * @return the lowercase hexadecimal digest
	 * @throws IOException if SHA-256 is unavailable
	 */
	private String sha256(byte[] input) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("The JDK does not provide SHA-256", e);
		}
	}

	/**
	 * Immutable finalization facts selected by the editor's sealed replay policy.
	 *
	 * @param base the replay base
	 * @param sourceTip the captured source tip
	 * @param sourceTipTree the source-tip tree
	 * @param finalTree the required final tree
	 * @param outputs each logical output and whether it remains in the final chain
	 * @param witnesses the sealed tree transitions for retained outputs
	 */
	public record Replay(String base, String sourceTip, String sourceTipTree, String finalTree, List<Output> outputs,
		List<OutputWitness> witnesses)
	{
		/**
		 * Finds the sealed tree transition for a retained output.
		 *
		 * @param outputId the retained output identifier
		 * @return the matching transition
		 * @throws IOException if the sealed replay does not contain the output witness
		 */
		OutputWitness witnessFor(String outputId) throws IOException
		{
			return witnesses.stream().filter(witness -> witness.outputId().equals(outputId)).findFirst().
				orElseThrow(() -> new IOException("Topic-replay manifest does not contain an output witness: " + outputId));
		}
	}

	/**
	 * One logical output and whether finalization retains it as a replacement commit.
	 *
	 * @param id the output identifier
	 * @param retained indicates whether the output remains in the final replacement chain
	 */
	public record Output(String id, boolean retained)
	{
	}

	/**
	 * One retained output's sealed tree transition.
	 *
	 * @param outputId the output identifier
	 * @param beforeTree the tree immediately before the output
	 * @param afterTree the tree produced by the output
	 */
	public record OutputWitness(String outputId, String beforeTree, String afterTree)
	{
	}

	/**
	 * Binds the finalized replay to its manifest, replacement chain, identities, and retained evidence.
	 *
	 * @param version the receipt schema version
	 * @param manifestSha256 the finalized manifest digest
	 * @param base the commit preceding the replay
	 * @param sourceTip the captured original tip
	 * @param sourceTipTree the original tip tree
	 * @param finalTree the required final replacement tree
	 * @param rewrittenTip the final replacement commit
	 * @param outputs the ordered replacement transitions
	 * @param identityDigest the ordered replacement identity digest
	 * @param evidence the retained artifact digests
	 */
	public record FinalizationReceipt(int version, String manifestSha256, String base, String sourceTip,
		String sourceTipTree, String finalTree, String rewrittenTip, List<FinalizedOutput> outputs,
		String identityDigest, List<ArtifactDigest> evidence)
	{
	}

	/**
	 * Captures one replacement commit and the sealed tree transition it implements.
	 *
	 * @param outputId the reviewed output identifier
	 * @param commit the replacement commit
	 * @param parent its preceding commit
	 * @param beforeTree the sealed starting tree
	 * @param afterTree the sealed result tree
	 */
	public record FinalizedOutput(String outputId, String commit, String parent, String beforeTree, String afterTree)
	{
	}

	/**
	 * Identifies retained receipt evidence by manifest-relative path and content digest.
	 *
	 * @param path the manifest-relative artifact path
	 * @param sha256 the captured artifact digest
	 */
	public record ArtifactDigest(String path, String sha256)
	{
	}
}
