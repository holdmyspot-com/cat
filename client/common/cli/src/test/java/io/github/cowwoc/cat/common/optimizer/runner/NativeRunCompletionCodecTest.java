package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.testng.annotations.Test;

/**
 * Tests the durable representation of native completion evidence.
 */
public final class NativeRunCompletionCodecTest
{
	/**
	 * Creates a test instance.
	 */
	public NativeRunCompletionCodecTest()
	{
	}

	/**
	 * Encodes a failed call with every absent optional field explicitly present as JSON null.
	 *
	 * @throws IOException if an unexpected evidence read occurs
	 */
	@Test
	public void encodesFailureWithExplicitNulls() throws IOException
	{
		EvidenceRef request = new EvidenceRef("run-requests", "a".repeat(64));
		EvidenceRef slot = new EvidenceRef("slot-claims", "b".repeat(64));
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.START_FAILED, "call-1",
			Optional.empty(), "start failed", List.of(), Optional.empty(), Optional.empty());
		NativeRunCompletionEvidence evidence = new NativeRunCompletionEvidence("campaign-1", "call-1", request,
			slot, Optional.empty(), NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(),
			Optional.of(failure), List.of(), Optional.empty(), Optional.empty());

		String actual = new String(NativeRunCompletionCodec.encode(evidence,
			reference ->
			{
				throw new AssertionError("no proof should be read");
			}), StandardCharsets.UTF_8);
		String expected = "{\"schemaVersion\":1,\"campaignId\":\"campaign-1\",\"logicalCallId\":\"call-1\"," +
			"\"runRequestRef\":{\"collection\":\"run-requests\",\"evidenceId\":\"" + "a".repeat(64) +
			"\"},\"slotClaimRef\":{\"collection\":\"slot-claims\",\"evidenceId\":\"" + "b".repeat(64) +
			"\"},\"runId\":null,\"outcome\":\"FAILED\",\"result\":null,\"failure\":{" +
			"\"kind\":\"START_FAILED\",\"logicalCallId\":\"call-1\",\"runId\":null," +
			"\"reason\":\"start failed\",\"nativeEvidence\":[],\"terminalResponseEvidence\":null," +
			"\"noNativeTurnDispatchProof\":null},\"orderedNativeEvidenceRefs\":[]," +
			"\"terminalResponseEvidence\":null,\"noDispatchProof\":null}";
		requireThat(actual, "canonicalJson").isEqualTo(expected);
	}

	/**
	 * Refuses an address from a collection that cannot contain native completions.
	 *
	 * @throws IOException when the address has the wrong collection
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsWrongCollection() throws IOException
	{
		NativeRunCompletionCodec.decode(new EvidenceRef("other", "a".repeat(64)), reference ->
		{
			throw new AssertionError("foreign evidence should not be read");
		});
	}

	/**
	 * Reads the addressed canonical bytes and preserves the typed failure.
	 *
	 * @throws IOException if decoding fails
	 */
	@Test
	public void decodesCanonicalFailure() throws IOException
	{
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.START_FAILED, "call-1",
			Optional.empty(), "start failed", List.of(), Optional.empty(), Optional.empty());
		NativeRunCompletionEvidence value = new NativeRunCompletionEvidence("campaign-1", "call-1",
			new EvidenceRef("run-requests", "a".repeat(64)), new EvidenceRef("slot-claims", "b".repeat(64)),
			Optional.empty(), NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure),
			List.of(), Optional.empty(), Optional.empty());
		byte[] bytes = NativeRunCompletionCodec.encode(value, ignored ->
		{
			throw new AssertionError();
		});
		EvidenceRef address = new EvidenceRef("native-run-completions", sha256(bytes));
		NativeRunCompletionEvidence decoded = NativeRunCompletionCodec.decode(address, ignored -> bytes);
		requireThat(decoded.failure().orElseThrow().reason(), "reason").isEqualTo("start failed");
		requireThat(NativeRunCompletionCodec.encode(decoded, ignored ->
		{
			throw new AssertionError();
		}), "bytes").isEqualTo(bytes);
	}

	/**
	 * Exercises every public C0R failure kind, including present run, native event and terminal fields.
	 *
	 * @throws IOException if an unexpected codec failure occurs
	 */
	@Test
	public void roundTripsEveryFailureKind() throws IOException
	{
		EvidenceRef nativeRef = new EvidenceRef("native-events", "c".repeat(64));
		for (NestedRunFailureKind kind : NestedRunFailureKind.values())
		{
			NestedRunFailure failure = new NestedRunFailure(kind, "call-1", Optional.of("run-1"),
				"native failure", List.of(nativeRef, nativeRef), Optional.of(nativeRef), Optional.empty());
			NativeRunCompletionEvidence value = completion(failure);
			byte[] bytes = NativeRunCompletionCodec.encode(value, ignored ->
			{
				throw new AssertionError("no proof should be read");
			});
			NativeRunCompletionEvidence decoded = read(bytes);
			requireThat(decoded.failure().orElseThrow().kind(), "kind").isEqualTo(kind);
			requireThat(decoded.failure().orElseThrow().retrySafety(), "retrySafety").
				isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
			requireThat(NativeRunCompletionCodec.encode(decoded, ignored ->
			{
				throw new AssertionError("no proof should be read");
			}), "bytes").isEqualTo(bytes);
		}
	}

	/**
	 * Preserves typed C0R result fields, relative paths, ordered maps and nanosecond timestamps.
	 *
	 * @throws IOException if an unexpected codec failure occurs
	 */
	@Test
	public void roundTripsSuccessWithNestedObservations() throws IOException
	{
		NativeRunCompletionEvidence value = success();
		byte[] bytes = NativeRunCompletionCodec.encode(value, ignored ->
		{
			throw new AssertionError("no proof should be read");
		});
		String json = new String(bytes, StandardCharsets.UTF_8);
		String eventRef = "{\"collection\":\"native-events\",\"evidenceId\":\"" + "c".repeat(64) + "\"}";
		String nativeEvent = "{\"conversationId\":\"conversation-1\",\"eventOrdinal\":1," +
			"\"evidence\":" + eventRef + "}";
		String expected = "{\"schemaVersion\":1,\"campaignId\":\"campaign-1\",\"logicalCallId\":" +
			"\"call-1\",\"runRequestRef\":{\"collection\":\"run-requests\",\"evidenceId\":\"" +
			"a".repeat(64) + "\"},\"slotClaimRef\":{\"collection\":\"slot-claims\",\"evidenceId\":\"" +
			"b".repeat(64) + "\"},\"runId\":\"run-1\",\"outcome\":\"SUCCEEDED\",\"result\":{" +
			"\"harness\":\"codex\",\"runId\":\"run-1\",\"executionProject\":\"/tmp/project\"," +
			"\"turnResults\":[{\"turnOrdinal\":1,\"conversationId\":\"conversation-1\"," +
			"\"responseText\":\"response\",\"nativeTurnEvent\":" + nativeEvent + "}]," +
			"\"nativeEventEvidence\":[" + nativeEvent + "],\"runnerIdentity\":{" +
			"\"harness\":\"codex\",\"runnerVersion\":\"1.0\",\"runtimeManifestSha256\":\"" +
			"d".repeat(64) + "\",\"providerIdentity\":\"provider\",\"harnessConfiguration\":{" +
			"\"a\":\"1\",\"z\":\"2\"},\"limitations\":[]},\"observedModelProfile\":{" +
			"\"modelId\":\"model\",\"reasoningProfile\":\"high\",\"exposedSettings\":{" +
			"\"a\":\"1\",\"z\":\"2\"}},\"promptSources\":[{\"conversationId\":\"conversation-1\"," +
			"\"eventOrdinal\":1,\"sourceId\":\"source-1\",\"normalizedRelativePath\":" +
			"\"rules/target.md\",\"origin\":\"TARGET\",\"loadMode\":\"LAZY\",\"parentPath\":" +
			"\"rules\",\"loadReason\":\"loaded\",\"triggerPath\":\"rules/trigger.md\"," +
			"\"nativeEventEvidence\":" + eventRef + "}],\"directToolExecutions\":[{" +
			"\"conversationId\":\"conversation-1\",\"eventOrdinal\":1,\"toolId\":\"tool-1\"," +
			"\"executablePath\":\"/usr/bin/tool\",\"exposedVersion\":\"1.0\"," +
			"\"invocationEvidence\":" + eventRef + "}],\"externalSourceExecutions\":[{" +
			"\"sourceId\":\"external-1\",\"kind\":\"mcp\",\"observableIdentity\":\"source\"," +
			"\"materialResponseEvidence\":" + eventRef + ",\"nativeEventEvidence\":" + eventRef +
			",\"runId\":\"run-1\",\"conversationId\":\"conversation-1\",\"eventOrdinal\":1," +
			"\"observedAt\":\"2026-09-24T10:11:12.123456789Z\",\"limitation\":null}]," +
			"\"coverage\":[{\"domain\":\"PROMPT_SOURCE\",\"status\":\"COMPLETE\"," +
			"\"limitation\":null,\"evidence\":[" + eventRef + "]},{\"domain\":\"DIRECT_TOOL\"," +
			"\"status\":\"COMPLETE\",\"limitation\":null,\"evidence\":[" + eventRef + "]},{" +
			"\"domain\":\"EXTERNAL_SOURCE\",\"status\":\"COMPLETE\",\"limitation\":null," +
			"\"evidence\":[" + eventRef + "]}]},\"failure\":null," +
			"\"orderedNativeEvidenceRefs\":[" + eventRef + "],\"terminalResponseEvidence\":" + eventRef +
			",\"noDispatchProof\":null}";
		requireThat(json, "schemaOneSuccessFixture").isEqualTo(expected);
		requireThat(json.contains("\"executionProject\":\"/tmp/project\""), "absolutePath").isTrue();
		requireThat(json.contains("\"normalizedRelativePath\":\"rules/target.md\""), "relativePath").isTrue();
		requireThat(json.contains("\"observedAt\":\"2026-09-24T10:11:12.123456789Z\""), "time").isTrue();
		requireThat(json.contains("\"exposedSettings\":{\"a\":\"1\",\"z\":\"2\"}"), "modelMap").
			isTrue();
		requireThat(json.contains("\"harnessConfiguration\":{\"a\":\"1\",\"z\":\"2\"}"),
			"runnerMap").isTrue();
		NativeRunCompletionEvidence decoded = read(bytes);
		requireThat(decoded.result().orElseThrow().promptSources().getFirst().normalizedRelativePath(), "sourcePath").
			isEqualTo(Path.of("rules/target.md"));
		requireThat(decoded.result().orElseThrow().externalSourceExecutions().getFirst().observedAt(), "time").
			isEqualTo(Instant.parse("2026-09-24T10:11:12.123456789Z"));
		requireThat(NativeRunCompletionCodec.encode(decoded, ignored ->
		{
			throw new AssertionError("no proof should be read");
		}), "bytes").isEqualTo(bytes);
	}

	/**
	 * Rejects alternate path, time, map, and numeric spellings inside a valid result tree.
	 *
	 * @throws IOException if setup unexpectedly fails
	 */
	@Test
	public void rejectsNoncanonicalNestedSuccessFields() throws IOException
	{
		byte[] bytes = NativeRunCompletionCodec.encode(success(), ignored ->
		{
			throw new AssertionError();
		});
		String json = new String(bytes, StandardCharsets.UTF_8);
		List<String> malformed = List.of(
			json.replace("\"executionProject\":\"/tmp/project\"",
				"\"executionProject\":\"file:///tmp/project\""),
			json.replace("\"executionProject\":\"/tmp/project\"",
				"\"executionProject\":\"/tmp//project\""),
			json.replace("\"normalizedRelativePath\":\"rules/target.md\"",
				"\"normalizedRelativePath\":\"rules//target.md\""),
			json.replace("\"parentPath\":\"rules\"", "\"parentPath\":\"rules/.\""),
			json.replace("\"triggerPath\":\"rules/trigger.md\"",
				"\"triggerPath\":\"rules/../trigger.md\""),
			json.replace("\"observedAt\":\"2026-09-24T10:11:12.123456789Z\"",
				"\"observedAt\":\"2026-09-24T06:11:12.123456789-04:00\""),
			json.replace("\"eventOrdinal\":1", "\"eventOrdinal\":1.0"),
			json.replace("\"turnOrdinal\":1", "\"turnOrdinal\":2147483648"),
			json.replace("\"eventOrdinal\":1", "\"eventOrdinal\":9223372036854775808"),
			json.replace("\"exposedSettings\":{\"a\":\"1\",\"z\":\"2\"}",
				"\"exposedSettings\":{\"z\":\"2\",\"a\":\"1\"}"),
			json.replace("\"harnessConfiguration\":{\"a\":\"1\",\"z\":\"2\"}",
				"\"harnessConfiguration\":{\"z\":\"2\",\"a\":\"1\"}"),
			json.replace("\"responseText\":\"response\"", "\"responseText\":null"));
		for (String candidate : malformed)
			expectIOException(() -> read(candidate.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * A successful completion cannot replace the run identity in its C0R result.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsSuccessRunMismatch()
	{
		NativeRunCompletionEvidence value = success();
		new NativeRunCompletionEvidence(value.campaignId(), value.logicalCallId(), value.runRequestRef(),
			value.slotClaimRef(), Optional.of("other-run"), value.outcome(), value.result(), value.failure(),
			value.orderedNativeEvidenceRefs(), value.terminalResponseEvidence(), value.noDispatchProof());
	}

	/**
	 * A successful completion cannot reorder or drop its native result events.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsSuccessEventMismatch()
	{
		NativeRunCompletionEvidence value = success();
		new NativeRunCompletionEvidence(value.campaignId(), value.logicalCallId(), value.runRequestRef(),
			value.slotClaimRef(), value.runId(), value.outcome(), value.result(), value.failure(),
			List.of(), value.terminalResponseEvidence(), value.noDispatchProof());
	}

	/**
	 * Reads the same addressed success in a fresh JVM whose working directory is different.
	 *
	 * @throws Exception if the child JVM cannot be launched or verified
	 */
	@Test
	public void restartPreservesRelativePaths() throws Exception
	{
		byte[] bytes = NativeRunCompletionCodec.encode(success(), ignored ->
		{
			throw new AssertionError();
		});
		Path otherWorkingDirectory = Files.createTempDirectory("native-completion-restart-");
		try
		{
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			Path tests = Path.of("target/test-classes").toAbsolutePath().normalize();
			String modulePath = String.join(File.pathSeparator, ModuleLayer.boot().configuration().modules().stream().
				map(module -> module.reference().location().orElseThrow()).filter(uri -> uri.getScheme().equals("file")).
				map(uri -> Path.of(uri).toString()).toList());
			try (Process process = new ProcessBuilder(java.toString(), "--module-path", modulePath,
				"--patch-module", "io.github.cowwoc.cat.common.cli=" + tests, "--module",
				"io.github.cowwoc.cat.common.cli/io.github.cowwoc.cat.common.optimizer.runner." +
					"NativeRunCompletionCodecTest", "--restart-probe", Base64.getEncoder().encodeToString(bytes)).
				directory(otherWorkingDirectory.toFile()).redirectErrorStream(true).start())
			{
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				if (process.waitFor() != 0)
					throw new AssertionError("child restart read failed: " + output);
				requireThat(output.strip(), "relativePath").isEqualTo("rules/target.md");
			}
		}
		finally
		{
			Files.delete(otherWorkingDirectory);
		}
	}

	/**
	 * Runs the read half of the restart test in a fresh process.
	 *
	 * @param args marker and Base64-encoded evidence bytes
	 * @throws IOException if the retained bytes are invalid
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 2 || !args[0].equals("--restart-probe"))
			throw new IllegalArgumentException("expected restart probe and evidence bytes");
		NativeRunCompletionEvidence decoded = read(Base64.getDecoder().decode(args[1]));
		System.out.println(decoded.result().orElseThrow().promptSources().getFirst().normalizedRelativePath());
	}

	/**
	 * Grants retry safety only after reading the exact matching immutable non-dispatch receipt.
	 *
	 * @throws IOException if an unexpected codec failure occurs
	 */
	@Test
	public void verifiesNoDispatchProofOnBothPaths() throws IOException
	{
		NativeDispatchCompletion receipt = new NativeDispatchCompletion(1, "call-1", Optional.of("run-1"),
			Instant.parse("2026-09-24T10:11:12Z"));
		byte[] proofBytes = receipt.bytes();
		EvidenceRef proofRef = new EvidenceRef("native-dispatch-completions", sha256(proofBytes));
		NoNativeTurnDispatchProof proof = new NoNativeTurnDispatchProof("call-1", Optional.of("run-1"), proofRef);
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call-1",
			Optional.of("run-1"), "never dispatched", List.of(), Optional.empty(), Optional.of(proof));
		NativeRunCompletionEvidence unverified = completion(failure);
		try
		{
			NativeRunCompletionCodec.encode(unverified, ignored -> proofBytes);
			throw new AssertionError("unverified object was encoded");
		}
		catch (IllegalArgumentException _)
		{
			// An untrusted in-memory claim is never promoted by the encoder.
		}
		byte[] bytes = NativeRunCompletionCodec.encode(completion(failure.verified()), ignored -> proofBytes);
		String proofJson = new String(bytes, StandardCharsets.UTF_8);
		requireThat(proofJson.contains("retrySafety"), "serializedRetrySafety").isFalse();
		requireThat(proofJson.contains("verifiedNoDispatch"), "serializedVerificationFlag").isFalse();
		NativeRunCompletionEvidence decoded = NativeRunCompletionCodec.decode(
			new EvidenceRef("native-run-completions", sha256(bytes)), proofReader(bytes, proofBytes));
		requireThat(decoded.failure().orElseThrow().retrySafety(), "retrySafety").
			isEqualTo(RetrySafety.PROVEN_OUTCOME_FREE);
		expectIOException(() -> NativeRunCompletionCodec.decode(
			new EvidenceRef("native-run-completions", sha256(bytes)), proofReader(bytes, new byte[0])));
		byte[] wrongCall = new NativeDispatchCompletion(1, "other-call", Optional.of("run-1"),
			Instant.parse("2026-09-24T10:11:12Z")).bytes();
		String forged = new String(bytes, StandardCharsets.UTF_8).replace(proofRef.evidenceId(), sha256(wrongCall));
		byte[] forgedBytes = forged.getBytes(StandardCharsets.UTF_8);
		expectIOException(() -> NativeRunCompletionCodec.decode(
			new EvidenceRef("native-run-completions", sha256(forgedBytes)), proofReader(forgedBytes, wrongCall)));
		NoNativeTurnDispatchProof wrongProof = new NoNativeTurnDispatchProof("call-1", Optional.of("run-1"),
			new EvidenceRef("native-dispatch-completions", sha256(wrongCall)));
		NestedRunFailure wrongFailure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call-1",
			Optional.of("run-1"), "never dispatched", List.of(), Optional.empty(), Optional.of(wrongProof));
		expectIOException(() -> NativeRunCompletionCodec.encode(completion(wrongFailure.verified()),
			ignored -> wrongCall));
		byte[] wrongRun = new NativeDispatchCompletion(1, "call-1", Optional.of("run-other"),
			Instant.parse("2026-09-24T10:11:12Z")).bytes();
		NoNativeTurnDispatchProof wrongRunProof = new NoNativeTurnDispatchProof("call-1", Optional.of("run-1"),
			new EvidenceRef("native-dispatch-completions", sha256(wrongRun)));
		NestedRunFailure wrongRunFailure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call-1",
			Optional.of("run-1"), "never dispatched", List.of(), Optional.empty(), Optional.of(wrongRunProof));
		expectIOException(() -> NativeRunCompletionCodec.encode(completion(wrongRunFailure.verified()),
			ignored -> wrongRun));
		expectIOException(() -> NativeRunCompletionCodec.encode(completion(failure.verified()), ignored ->
		{
			throw new IOException("unreadable proof");
		}));
	}

	/**
	 * Rejects canonical-hash-matching but structurally malformed evidence as I/O failure.
	 *
	 * @throws IOException if an unexpected setup failure occurs
	 */
	@Test
	public void rejectsMalformedCanonicalForms() throws IOException
	{
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.START_FAILED, "call-1",
			Optional.empty(), "start failed", List.of(), Optional.empty(), Optional.empty());
		byte[] original = NativeRunCompletionCodec.encode(completion(failure), ignored ->
		{
			throw new AssertionError();
		});
		String json = new String(original, StandardCharsets.UTF_8);
		List<String> malformed = List.of(
			json.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
			json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
			json.replace("\"campaignId\":\"campaign-1\"", "\"campaignId\":\"campaign\\u002d1\""),
			json.replace("\"runId\":null,\"outcome\"", "\"outcome\""),
			json.replace("\"failure\":{", "\"failure\":{} ,\"unused\":{"),
			json.replace("\"failure\":{", "\"failure\":{}"),
			json.replace("\"reason\":\"start failed\"", "\"reason\":42"),
			json.replace("\"reason\":\"start failed\"", "\"reason\":\"start failed\",\"alien\":true"),
			json.replace("\"reason\":\"start failed\"", "\"reason\":\"start failed\",\"reason\":\"again\""),
			json.replace("\"terminalResponseEvidence\":null,\"noNativeTurnDispatchProof\":null",
				"\"noNativeTurnDispatchProof\":null"),
			json.replace("\"runRequestRef\":{\"collection\"", "\"runRequestRef\":{\"alien\":true,\"collection\""),
			json.replace("\"outcome\":\"FAILED\"", "\"outcome\":\"SUCCEEDED\""),
			json.replace("\"logicalCallId\":\"call-1\",\"runRequestRef\"",
				"\"logicalCallId\":\"other\",\"runRequestRef\""),
			json.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,"),
			json.replace("\"campaignId\":\"campaign-1\",\"logicalCallId\":\"call-1\"",
				"\"logicalCallId\":\"call-1\",\"campaignId\":\"campaign-1\""),
			json + " ");
		for (String candidate : malformed)
			expectIOException(() -> read(candidate.getBytes(StandardCharsets.UTF_8)));
		expectIOException(() -> read("{}".getBytes(StandardCharsets.UTF_8)));
		byte[] badUtf8 = Arrays.copyOf(original, original.length + 1);
		badUtf8[original.length] = (byte) 0xff;
		expectIOException(() -> read(badUtf8));
		expectIOException(() -> NativeRunCompletionCodec.decode(
			new EvidenceRef("native-run-completions", "a".repeat(64)), ignored -> original));
		expectIOException(() -> NativeRunCompletionCodec.decode(
			new EvidenceRef("native-run-completions", sha256(original)), ignored ->
			{
				throw new IOException("unreadable");
			}));
	}

	/**
	 * Creates a representative failed completion with matching top-level fields.
	 *
	 * @param failure the retained failure
	 * @return the typed completion
	 */
	private static NativeRunCompletionEvidence completion(NestedRunFailure failure)
	{
		return new NativeRunCompletionEvidence("campaign-1", "call-1",
			new EvidenceRef("run-requests", "a".repeat(64)), new EvidenceRef("slot-claims", "b".repeat(64)),
			failure.runId(), NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure),
			failure.nativeEvidence(), failure.terminalResponseEvidence(), failure.noNativeTurnDispatchProof());
	}

	/**
	 * Creates a success with all C0R nested observation types.
	 *
	 * @return the typed completion
	 */
	private static NativeRunCompletionEvidence success()
	{
		EvidenceRef eventRef = new EvidenceRef("native-events", "c".repeat(64));
		NativeEventEvidence event = new NativeEventEvidence("conversation-1", 1, eventRef);
		NestedRunResult result = new NestedRunResult("codex", "run-1", Path.of("/tmp/project"),
			List.of(new TurnResult(1, "conversation-1", "response", event)), List.of(event),
			new RunnerIdentity("codex", "1.0", "d".repeat(64), Optional.of("provider"),
				Map.of("z", "2", "a", "1"), List.of()),
			new ModelProfile("model", Optional.of("high"), Map.of("z", "2", "a", "1")),
			List.of(new RuntimePromptSource("conversation-1", 1, "source-1", Path.of("rules/target.md"),
				PromptSourceOrigin.TARGET, Optional.of(PromptLoadMode.LAZY), Optional.of(Path.of("rules")),
				Optional.of("loaded"), Optional.of(Path.of("rules/trigger.md")), eventRef)),
			List.of(new DirectToolExecution("conversation-1", 1, "tool-1", Path.of("/usr/bin/tool"),
				Optional.of("1.0"), eventRef)),
			List.of(new ExternalSourceExecution("external-1", "mcp", Optional.of("source"),
				Optional.of(eventRef), eventRef, "run-1", "conversation-1", 1,
				Instant.parse("2026-09-24T10:11:12.123456789Z"), Optional.empty())),
			List.of(new ObservationCoverage(ObservationDomain.PROMPT_SOURCE, ObservationCoverageStatus.COMPLETE,
				Optional.empty(), List.of(eventRef)),
				new ObservationCoverage(ObservationDomain.DIRECT_TOOL, ObservationCoverageStatus.COMPLETE,
					Optional.empty(), List.of(eventRef)),
				new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE, ObservationCoverageStatus.COMPLETE,
					Optional.empty(), List.of(eventRef))));
		return new NativeRunCompletionEvidence("campaign-1", "call-1",
			new EvidenceRef("run-requests", "a".repeat(64)), new EvidenceRef("slot-claims", "b".repeat(64)),
			Optional.of("run-1"), NativeRunCompletionEvidence.Outcome.SUCCEEDED, Optional.of(result),
			Optional.empty(), List.of(eventRef), Optional.of(eventRef), Optional.empty());
	}

	/**
	 * Reads bytes through their own content address.
	 *
	 * @param bytes the addressed bytes
	 * @return the typed completion
	 * @throws IOException if the evidence is invalid
	 */
	private static NativeRunCompletionEvidence read(byte[] bytes) throws IOException
	{
		return NativeRunCompletionCodec.decode(new EvidenceRef("native-run-completions", sha256(bytes)),
			ignored -> bytes);
	}

	/**
	 * Requires that an action reject evidence with an I/O failure.
	 *
	 * @param action the read to exercise
	 */
	private static void expectIOException(IOAction action)
	{
		try
		{
			action.run();
			throw new AssertionError("malformed evidence was accepted");
		}
		catch (IOException _)
		{
			// Evidence stays unreadable; no typed fallback is synthesized.
		}
	}

	/**
	 * Reads a completion and its proof from distinct immutable collections.
	 *
	 * @param root the completion bytes
	 * @param proof the proof bytes
	 * @return the test evidence reader
	 */
	private static io.github.cowwoc.cat.common.optimizer.api.EvidenceReader proofReader(byte[] root, byte[] proof)
	{
		return reference ->
		{
			if (reference.collection().equals("native-run-completions"))
				return root;
			return proof;
		};
	}

	/** An action that may fail while reading evidence. */
	@FunctionalInterface
	private interface IOAction
	{
		/**
		 * Executes a read.
		 *
		 * @throws IOException if the read is rejected
		 */
		void run() throws IOException;
	}

	/**
	 * Computes the evidence content identity.
	 *
	 * @param bytes the addressed bytes
	 * @return the lowercase SHA-256 digest
	 */
	private static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new AssertionError(exception);
		}
	}
}
