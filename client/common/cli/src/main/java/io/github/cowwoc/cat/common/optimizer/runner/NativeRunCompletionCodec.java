package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Field-by-field canonical schema-one representation of a typed native completion.
 */
public final class NativeRunCompletionCodec
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();
	private static final Comparator<String> UTF8_ORDER = (left, right) ->
		Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));

	/** Prevents construction. */
	private NativeRunCompletionCodec()
	{
	}

	/**
	 * Encodes a validated completion, verifying any claimed no-dispatch receipt with the given reader.
	 *
	 * @param evidence the completion to encode
	 * @param reader immutable evidence reader
	 * @return canonical UTF-8 JSON
	 * @throws IOException if a referenced proof is unreadable or invalid
	 */
	public static byte[] encode(NativeRunCompletionEvidence evidence, EvidenceReader reader) throws IOException
	{
		Objects.requireNonNull(evidence, "evidence");
		Objects.requireNonNull(reader, "reader");
		new NativeRunCompletionEvidence(evidence.campaignId(), evidence.logicalCallId(), evidence.runRequestRef(),
			evidence.slotClaimRef(), evidence.runId(), evidence.outcome(), evidence.result(), evidence.failure(),
			evidence.orderedNativeEvidenceRefs(), evidence.terminalResponseEvidence(), evidence.noDispatchProof());
		if (evidence.noDispatchProof().isPresent())
		{
			if (evidence.failure().orElseThrow().retrySafety() != RetrySafety.PROVEN_OUTCOME_FREE)
				throw new IllegalArgumentException("claimed proof was not C0R-verified");
			verifyProof(evidence, reader);
		}
		return write(evidence);
	}

	/**
	 * Reads one addressed completion and refuses malformed, noncanonical, or unverifiable evidence.
	 *
	 * @param reference the content address
	 * @param reader immutable evidence reader
	 * @return the typed completion
	 * @throws IOException if reading or validation fails
	 */
	public static NativeRunCompletionEvidence decode(EvidenceRef reference, EvidenceReader reader) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		Objects.requireNonNull(reader, "reader");
		if (!reference.collection().equals("native-run-completions"))
			throw new IOException("wrong native completion collection");
		byte[] bytes = reader.read(reference);
		if (bytes == null || !sha256(bytes).equals(reference.evidenceId()))
			throw new IOException("native completion content hash mismatch");
		try
		{
			String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			NativeRunCompletionEvidence evidence = parse(JSON.readTree(json));
			if (evidence.noDispatchProof().isPresent())
			{
				verifyProof(evidence, reader);
				NestedRunFailure trusted = evidence.failure().orElseThrow().verified();
				evidence = new NativeRunCompletionEvidence(evidence.campaignId(), evidence.logicalCallId(),
					evidence.runRequestRef(), evidence.slotClaimRef(), evidence.runId(), evidence.outcome(),
					evidence.result(), Optional.of(trusted), evidence.orderedNativeEvidenceRefs(),
					evidence.terminalResponseEvidence(), evidence.noDispatchProof());
			}
			if (!Arrays.equals(bytes, write(evidence)))
				throw new IOException("noncanonical native completion JSON");
			return evidence;
		}
		catch (CharacterCodingException exception)
		{
			throw new IOException("malformed UTF-8 native completion", exception);
		}
		catch (RuntimeException exception)
		{
			throw new IOException("invalid native completion", exception);
		}
	}

	/**
	 * Verifies exact content and identity of a claimed C0R non-dispatch receipt.
	 *
	 * @param evidence the evidence value
	 * @param reader the reader value
	 */
	private static void verifyProof(NativeRunCompletionEvidence evidence, EvidenceReader reader) throws IOException
	{
		NoNativeTurnDispatchProof proof = evidence.noDispatchProof().orElseThrow();
		NestedRunFailure failure = evidence.failure().orElseThrow();
		if (evidence.outcome() != NativeRunCompletionEvidence.Outcome.FAILED ||
			evidence.terminalResponseEvidence().isPresent() || failure.terminalResponseEvidence().isPresent() ||
			!proof.logicalCallId().equals(evidence.logicalCallId()) ||
			!proof.logicalCallId().equals(failure.logicalCallId()) || !proof.runId().equals(evidence.runId()) ||
			!proof.runId().equals(failure.runId()) ||
			!proof.completionRef().collection().equals("native-dispatch-completions"))
			throw new IOException("contradictory no-dispatch proof identity");
		byte[] bytes = reader.read(proof.completionRef());
		if (bytes == null || !sha256(bytes).equals(proof.completionRef().evidenceId()))
			throw new IOException("no-dispatch proof content hash mismatch");
		NativeDispatchCompletion completion = NativeDispatchCompletion.fromBytes(bytes);
		if (!completion.logicalCallId().equals(proof.logicalCallId()) || !completion.runId().equals(proof.runId()))
			throw new IOException("no-dispatch receipt identifies another call or run");
	}

	/**
	 * Produces the complete ordered schema-one tree.
	 *
	 * @param evidence the evidence value
	 * @return the write value
	 */
	private static byte[] write(NativeRunCompletionEvidence evidence)
	{
		ObjectNode root = JSON.createObjectNode();
		root.put("schemaVersion", 1);
		root.put("campaignId", evidence.campaignId());
		root.put("logicalCallId", evidence.logicalCallId());
		root.set("runRequestRef", ref(evidence.runRequestRef()));
		root.set("slotClaimRef", ref(evidence.slotClaimRef()));
		optionalString(root, "runId", evidence.runId());
		root.put("outcome", evidence.outcome().name());
		root.set("result", evidence.result().<JsonNode>map(NativeRunCompletionCodec::result).orElse(JSON.nullNode()));
		root.set("failure", evidence.failure().<JsonNode>map(NativeRunCompletionCodec::failure).orElse(JSON.nullNode()));
		root.set("orderedNativeEvidenceRefs", refs(evidence.orderedNativeEvidenceRefs()));
		optionalRef(root, "terminalResponseEvidence", evidence.terminalResponseEvidence());
		optionalProof(root, "noDispatchProof", evidence.noDispatchProof());
		return JSON.writeValueAsBytes(root);
	}

	/**
	 * Writes a two-field content address.
	 *
	 * @param value the value value
	 * @return the ref value
	 */
	private static ObjectNode ref(EvidenceRef value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("collection", value.collection());
		node.put("evidenceId", value.evidenceId());
		return node;
	}

	/**
	 * Writes addresses without sorting or deduplication.
	 *
	 * @param values the values value
	 * @return the refs value
	 */
	private static ArrayNode refs(List<EvidenceRef> values)
	{
		ArrayNode node = JSON.createArrayNode();
		for (EvidenceRef value : values)
			node.add(ref(value));
		return node;
	}

	/**
	 * Writes an optional text field with an explicit null.
	 *
	 * @param node the node value
	 * @param name the name value
	 * @param value the value value
	 */
	private static void optionalString(ObjectNode node, String name, Optional<String> value)
	{
		if (value.isPresent())
			node.put(name, value.orElseThrow());
		else
			node.putNull(name);
	}

	/**
	 * Writes an optional reference field with an explicit null.
	 *
	 * @param node the node value
	 * @param name the name value
	 * @param value the value value
	 */
	private static void optionalRef(ObjectNode node, String name, Optional<EvidenceRef> value)
	{
		node.set(name, value.<JsonNode>map(NativeRunCompletionCodec::ref).orElse(JSON.nullNode()));
	}

	/**
	 * Writes an optional path field with an explicit null.
	 *
	 * @param node the node value
	 * @param name the name value
	 * @param value the value value
	 */
	private static void optionalPath(ObjectNode node, String name, Optional<Path> value)
	{
		optionalString(node, name, value.map(Path::toString));
	}

	/**
	 * Writes an optional enum field with an explicit null.
	 *
	 * @param node the node value
	 * @param name the name value
	 * @param value the value value
	 */
	private static void optionalEnum(ObjectNode node, String name, Optional<? extends Enum<?>> value)
	{
		optionalString(node, name, value.map(Enum::name));
	}

	/**
	 * Writes one claimed proof without a retry-safety flag.
	 *
	 * @param value the value value
	 * @return the proof value
	 */
	private static ObjectNode proof(NoNativeTurnDispatchProof value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("logicalCallId", value.logicalCallId());
		optionalString(node, "runId", value.runId());
		node.set("completionRef", ref(value.completionRef()));
		return node;
	}

	/**
	 * Writes an optional proof field with an explicit null.
	 *
	 * @param node the node value
	 * @param name the name value
	 * @param value the value value
	 */
	private static void optionalProof(ObjectNode node, String name, Optional<NoNativeTurnDispatchProof> value)
	{
		node.set(name, value.<JsonNode>map(NativeRunCompletionCodec::proof).orElse(JSON.nullNode()));
	}

	/**
	 * Writes the public seven-field failure constructor shape.
	 *
	 * @param value the value value
	 * @return the failure value
	 */
	private static ObjectNode failure(NestedRunFailure value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("kind", value.kind().name());
		node.put("logicalCallId", value.logicalCallId());
		optionalString(node, "runId", value.runId());
		node.put("reason", value.reason());
		node.set("nativeEvidence", refs(value.nativeEvidence()));
		optionalRef(node, "terminalResponseEvidence", value.terminalResponseEvidence());
		optionalProof(node, "noNativeTurnDispatchProof", value.noNativeTurnDispatchProof());
		return node;
	}

	/**
	 * Writes the C0R result in record-component order.
	 *
	 * @param value the value value
	 * @return the result value
	 */
	private static ObjectNode result(NestedRunResult value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("harness", value.harness());
		node.put("runId", value.runId());
		node.put("executionProject", value.executionProject().toString());
		ArrayNode turns = node.putArray("turnResults");
		for (TurnResult turn : value.turnResults())
			turns.add(turn(turn));
		ArrayNode events = node.putArray("nativeEventEvidence");
		for (NativeEventEvidence event : value.nativeEventEvidence())
			events.add(event(event));
		node.set("runnerIdentity", runner(value.runnerIdentity()));
		node.set("observedModelProfile", model(value.observedModelProfile()));
		ArrayNode sources = node.putArray("promptSources");
		for (RuntimePromptSource source : value.promptSources())
			sources.add(source(source));
		ArrayNode tools = node.putArray("directToolExecutions");
		for (DirectToolExecution tool : value.directToolExecutions())
			tools.add(tool(tool));
		ArrayNode externals = node.putArray("externalSourceExecutions");
		for (ExternalSourceExecution external : value.externalSourceExecutions())
			externals.add(external(external));
		ArrayNode coverage = node.putArray("coverage");
		for (ObservationCoverage item : value.coverage())
			coverage.add(coverage(item));
		return node;
	}

	/**
	 * Writes one native event.
	 *
	 * @param value the value value
	 * @return the event value
	 */
	private static ObjectNode event(NativeEventEvidence value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("conversationId", value.conversationId());
		node.put("eventOrdinal", value.eventOrdinal());
		node.set("evidence", ref(value.evidence()));
		return node;
	}

	/**
	 * Writes one turn.
	 *
	 * @param value the value value
	 * @return the turn value
	 */
	private static ObjectNode turn(TurnResult value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("turnOrdinal", value.turnOrdinal());
		node.put("conversationId", value.conversationId());
		node.put("responseText", value.responseText());
		node.set("nativeTurnEvent", event(value.nativeTurnEvent()));
		return node;
	}

	/**
	 * Writes a map in unsigned UTF-8 key order.
	 *
	 * @param values the values value
	 * @return the stringMap value
	 */
	private static ObjectNode stringMap(Map<String, String> values)
	{
		ObjectNode node = JSON.createObjectNode();
		values.keySet().stream().sorted(UTF8_ORDER).forEach(key -> node.put(key, values.get(key)));
		return node;
	}

	/**
	 * Writes one runner identity.
	 *
	 * @param value the value value
	 * @return the runner value
	 */
	private static ObjectNode runner(RunnerIdentity value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("harness", value.harness());
		node.put("runnerVersion", value.runnerVersion());
		node.put("runtimeManifestSha256", value.runtimeManifestSha256());
		optionalString(node, "providerIdentity", value.providerIdentity());
		node.set("harnessConfiguration", stringMap(value.harnessConfiguration()));
		ArrayNode limitations = node.putArray("limitations");
		value.limitations().forEach(limitations::add);
		return node;
	}

	/**
	 * Writes one model profile.
	 *
	 * @param value the value value
	 * @return the model value
	 */
	private static ObjectNode model(ModelProfile value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("modelId", value.modelId());
		optionalString(node, "reasoningProfile", value.reasoningProfile());
		node.set("exposedSettings", stringMap(value.exposedSettings()));
		return node;
	}

	/**
	 * Writes one runtime prompt-source observation.
	 *
	 * @param value the value value
	 * @return the source value
	 */
	private static ObjectNode source(RuntimePromptSource value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("conversationId", value.conversationId());
		node.put("eventOrdinal", value.eventOrdinal());
		node.put("sourceId", value.sourceId());
		node.put("normalizedRelativePath", value.normalizedRelativePath().toString());
		node.put("origin", value.origin().name());
		optionalEnum(node, "loadMode", value.loadMode());
		optionalPath(node, "parentPath", value.parentPath());
		optionalString(node, "loadReason", value.loadReason());
		optionalPath(node, "triggerPath", value.triggerPath());
		node.set("nativeEventEvidence", ref(value.nativeEventEvidence()));
		return node;
	}

	/**
	 * Writes one directly invoked host tool.
	 *
	 * @param value the value value
	 * @return the tool value
	 */
	private static ObjectNode tool(DirectToolExecution value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("conversationId", value.conversationId());
		node.put("eventOrdinal", value.eventOrdinal());
		node.put("toolId", value.toolId());
		node.put("executablePath", value.executablePath().toString());
		optionalString(node, "exposedVersion", value.exposedVersion());
		node.set("invocationEvidence", ref(value.invocationEvidence()));
		return node;
	}

	/**
	 * Writes one external-source observation.
	 *
	 * @param value the value value
	 * @return the external value
	 */
	private static ObjectNode external(ExternalSourceExecution value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("sourceId", value.sourceId());
		node.put("kind", value.kind());
		optionalString(node, "observableIdentity", value.observableIdentity());
		optionalRef(node, "materialResponseEvidence", value.materialResponseEvidence());
		node.set("nativeEventEvidence", ref(value.nativeEventEvidence()));
		node.put("runId", value.runId());
		node.put("conversationId", value.conversationId());
		node.put("eventOrdinal", value.eventOrdinal());
		node.put("observedAt", value.observedAt().toString());
		optionalString(node, "limitation", value.limitation());
		return node;
	}

	/**
	 * Writes one observation-coverage record.
	 *
	 * @param value the value value
	 * @return the coverage value
	 */
	private static ObjectNode coverage(ObservationCoverage value)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("domain", value.domain().name());
		node.put("status", value.status().name());
		optionalString(node, "limitation", value.limitation());
		node.set("evidence", refs(value.evidence()));
		return node;
	}

	/**
	 * Reconstructs the root without polymorphic or optional binding.
	 *
	 * @param value the value value
	 * @return the parse value
	 */
	private static NativeRunCompletionEvidence parse(JsonNode value)
	{
		fields(value, "schemaVersion", "campaignId", "logicalCallId", "runRequestRef", "slotClaimRef",
			"runId", "outcome", "result", "failure", "orderedNativeEvidenceRefs",
			"terminalResponseEvidence", "noDispatchProof");
		if (integer(value.get("schemaVersion")) != 1)
			throw new IllegalArgumentException("unknown native completion schema");
		return new NativeRunCompletionEvidence(string(value.get("campaignId")), string(value.get("logicalCallId")),
			parseRef(value.get("runRequestRef")), parseRef(value.get("slotClaimRef")),
			optional(value.get("runId"), NativeRunCompletionCodec::string),
			enumValue(value.get("outcome"), NativeRunCompletionEvidence.Outcome.class),
			optional(value.get("result"), NativeRunCompletionCodec::parseResult),
			optional(value.get("failure"), NativeRunCompletionCodec::parseFailure),
			list(value.get("orderedNativeEvidenceRefs"), NativeRunCompletionCodec::parseRef),
			optional(value.get("terminalResponseEvidence"), NativeRunCompletionCodec::parseRef),
			optional(value.get("noDispatchProof"), NativeRunCompletionCodec::parseProof));
	}

	/**
	 * Requires exactly the declared object fields.
	 *
	 * @param value the value value
	 * @param names the names value
	 */
	private static void fields(JsonNode value, String... names)
	{
		if (value == null || !value.isObject() || value.size() != names.length ||
			!Set.copyOf(value.propertyNames()).equals(Set.of(names)))
			throw new IllegalArgumentException("unexpected JSON object fields");
	}

	/**
	 * Reads a JSON string without coercion.
	 *
	 * @param value the value value
	 * @return the string value
	 */
	private static String string(JsonNode value)
	{
		if (value == null || !value.isString())
			throw new IllegalArgumentException("expected JSON string");
		return value.stringValue();
	}

	/**
	 * Reads an exact-width integral JSON number.
	 *
	 * @param value the value value
	 * @return the integer value
	 */
	private static int integer(JsonNode value)
	{
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
			throw new IllegalArgumentException("expected 32-bit integer");
		return value.intValue();
	}

	/**
	 * Reads an exact-width integral JSON number.
	 *
	 * @param value the value value
	 * @return the longInteger value
	 */
	private static long longInteger(JsonNode value)
	{
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
			throw new IllegalArgumentException("expected 64-bit integer");
		return value.longValue();
	}

	/**
	 * Reads a case-sensitive declared enum value.
	 *
	 * @param <T> the enum type
	 * @param value the value value
	 * @param type the type value
	 * @return the enumValue value
	 */
	private static <T extends Enum<T>> T enumValue(JsonNode value, Class<T> type)
	{
		return Enum.valueOf(type, string(value));
	}

	/**
	 * Reads an explicit null or the declared nested type.
	 *
	 * @param <T> the decoded type
	 * @param value the value value
	 * @param parser the parser value
	 * @return the optional value
	 */
	private static <T> Optional<T> optional(JsonNode value, Function<JsonNode, T> parser)
	{
		if (value == null)
			throw new IllegalArgumentException("missing optional field");
		if (value.isNull())
			return Optional.empty();
		return Optional.of(parser.apply(value));
	}

	/**
	 * Reads an array without changing source order.
	 *
	 * @param <T> the decoded element type
	 * @param value the value value
	 * @param parser the parser value
	 * @return the list value
	 */
	private static <T> List<T> list(JsonNode value, Function<JsonNode, T> parser)
	{
		if (value == null || !value.isArray())
			throw new IllegalArgumentException("expected JSON array");
		List<T> result = new ArrayList<>();
		for (JsonNode item : value)
			result.add(parser.apply(item));
		return List.copyOf(result);
	}

	/**
	 * Reads a path without CWD resolution or normalization.
	 *
	 * @param value the value value
	 * @return the path value
	 */
	private static Path path(JsonNode value)
	{
		String text = string(value);
		Path result = Path.of(text);
		if (!result.toString().equals(text))
			throw new IllegalArgumentException("noncanonical path spelling");
		return result;
	}

	/**
	 * Reads an instant without changing its spelling or precision.
	 *
	 * @param value the value value
	 * @return the instant value
	 */
	private static Instant instant(JsonNode value)
	{
		String text = string(value);
		Instant result = Instant.parse(text);
		if (!result.toString().equals(text))
			throw new IllegalArgumentException("noncanonical instant spelling");
		return result;
	}

	/**
	 * Reads an address.
	 *
	 * @param value the value value
	 * @return the parseRef value
	 */
	private static EvidenceRef parseRef(JsonNode value)
	{
		fields(value, "collection", "evidenceId");
		return new EvidenceRef(string(value.get("collection")), string(value.get("evidenceId")));
	}

	/**
	 * Reads a claimed proof without conferring retry authority.
	 *
	 * @param value the value value
	 * @return the parseProof value
	 */
	private static NoNativeTurnDispatchProof parseProof(JsonNode value)
	{
		fields(value, "logicalCallId", "runId", "completionRef");
		return new NoNativeTurnDispatchProof(string(value.get("logicalCallId")),
			optional(value.get("runId"), NativeRunCompletionCodec::string), parseRef(value.get("completionRef")));
	}

	/**
	 * Reads a failure as initially untrusted.
	 *
	 * @param value the value value
	 * @return the parseFailure value
	 */
	private static NestedRunFailure parseFailure(JsonNode value)
	{
		fields(value, "kind", "logicalCallId", "runId", "reason", "nativeEvidence",
			"terminalResponseEvidence", "noNativeTurnDispatchProof");
		return new NestedRunFailure(enumValue(value.get("kind"), NestedRunFailureKind.class),
			string(value.get("logicalCallId")), optional(value.get("runId"), NativeRunCompletionCodec::string),
			string(value.get("reason")), list(value.get("nativeEvidence"), NativeRunCompletionCodec::parseRef),
			optional(value.get("terminalResponseEvidence"), NativeRunCompletionCodec::parseRef),
			optional(value.get("noNativeTurnDispatchProof"), NativeRunCompletionCodec::parseProof));
	}

	/**
	 * Reads a C0R result through its public constructor.
	 *
	 * @param value the value value
	 * @return the parseResult value
	 */
	private static NestedRunResult parseResult(JsonNode value)
	{
		fields(value, "harness", "runId", "executionProject", "turnResults", "nativeEventEvidence",
			"runnerIdentity", "observedModelProfile", "promptSources", "directToolExecutions",
			"externalSourceExecutions", "coverage");
		return new NestedRunResult(string(value.get("harness")), string(value.get("runId")),
			path(value.get("executionProject")), list(value.get("turnResults"), NativeRunCompletionCodec::parseTurn),
			list(value.get("nativeEventEvidence"), NativeRunCompletionCodec::parseEvent),
			parseRunner(value.get("runnerIdentity")), parseModel(value.get("observedModelProfile")),
			list(value.get("promptSources"), NativeRunCompletionCodec::parseSource),
			list(value.get("directToolExecutions"), NativeRunCompletionCodec::parseTool),
			list(value.get("externalSourceExecutions"), NativeRunCompletionCodec::parseExternal),
			list(value.get("coverage"), NativeRunCompletionCodec::parseCoverage));
	}

	/**
	 * Reads a native event.
	 *
	 * @param value the value value
	 * @return the parseEvent value
	 */
	private static NativeEventEvidence parseEvent(JsonNode value)
	{
		fields(value, "conversationId", "eventOrdinal", "evidence");
		return new NativeEventEvidence(string(value.get("conversationId")), longInteger(value.get("eventOrdinal")),
			parseRef(value.get("evidence")));
	}

	/**
	 * Reads a turn.
	 *
	 * @param value the value value
	 * @return the parseTurn value
	 */
	private static TurnResult parseTurn(JsonNode value)
	{
		fields(value, "turnOrdinal", "conversationId", "responseText", "nativeTurnEvent");
		return new TurnResult(integer(value.get("turnOrdinal")), string(value.get("conversationId")),
			string(value.get("responseText")), parseEvent(value.get("nativeTurnEvent")));
	}

	/**
	 * Reads a string map without coercing its values.
	 *
	 * @param value the value value
	 * @return the parseMap value
	 */
	private static Map<String, String> parseMap(JsonNode value)
	{
		if (value == null || !value.isObject())
			throw new IllegalArgumentException("expected JSON object map");
		Map<String, String> result = new HashMap<>();
		for (String key : value.propertyNames())
			result.put(key, string(value.get(key)));
		return Map.copyOf(result);
	}

	/**
	 * Reads a runner identity.
	 *
	 * @param value the value value
	 * @return the parseRunner value
	 */
	private static RunnerIdentity parseRunner(JsonNode value)
	{
		fields(value, "harness", "runnerVersion", "runtimeManifestSha256", "providerIdentity",
			"harnessConfiguration", "limitations");
		return new RunnerIdentity(string(value.get("harness")), string(value.get("runnerVersion")),
			string(value.get("runtimeManifestSha256")),
			optional(value.get("providerIdentity"), NativeRunCompletionCodec::string),
			parseMap(value.get("harnessConfiguration")),
			list(value.get("limitations"), NativeRunCompletionCodec::string));
	}

	/**
	 * Reads a model profile.
	 *
	 * @param value the value value
	 * @return the parseModel value
	 */
	private static ModelProfile parseModel(JsonNode value)
	{
		fields(value, "modelId", "reasoningProfile", "exposedSettings");
		return new ModelProfile(string(value.get("modelId")),
			optional(value.get("reasoningProfile"), NativeRunCompletionCodec::string),
			parseMap(value.get("exposedSettings")));
	}

	/**
	 * Reads a prompt-source observation.
	 *
	 * @param value the value value
	 * @return the parseSource value
	 */
	private static RuntimePromptSource parseSource(JsonNode value)
	{
		fields(value, "conversationId", "eventOrdinal", "sourceId", "normalizedRelativePath", "origin",
			"loadMode", "parentPath", "loadReason", "triggerPath", "nativeEventEvidence");
		return new RuntimePromptSource(string(value.get("conversationId")), longInteger(value.get("eventOrdinal")),
			string(value.get("sourceId")), path(value.get("normalizedRelativePath")),
			enumValue(value.get("origin"), PromptSourceOrigin.class),
			optional(value.get("loadMode"), item -> enumValue(item, PromptLoadMode.class)),
			optional(value.get("parentPath"), NativeRunCompletionCodec::path),
			optional(value.get("loadReason"), NativeRunCompletionCodec::string),
			optional(value.get("triggerPath"), NativeRunCompletionCodec::path),
			parseRef(value.get("nativeEventEvidence")));
	}

	/**
	 * Reads a direct host-tool observation.
	 *
	 * @param value the value value
	 * @return the parseTool value
	 */
	private static DirectToolExecution parseTool(JsonNode value)
	{
		fields(value, "conversationId", "eventOrdinal", "toolId", "executablePath", "exposedVersion",
			"invocationEvidence");
		return new DirectToolExecution(string(value.get("conversationId")), longInteger(value.get("eventOrdinal")),
			string(value.get("toolId")), path(value.get("executablePath")),
			optional(value.get("exposedVersion"), NativeRunCompletionCodec::string),
			parseRef(value.get("invocationEvidence")));
	}

	/**
	 * Reads an external-source observation.
	 *
	 * @param value the value value
	 * @return the parseExternal value
	 */
	private static ExternalSourceExecution parseExternal(JsonNode value)
	{
		fields(value, "sourceId", "kind", "observableIdentity", "materialResponseEvidence",
			"nativeEventEvidence", "runId", "conversationId", "eventOrdinal", "observedAt", "limitation");
		return new ExternalSourceExecution(string(value.get("sourceId")), string(value.get("kind")),
			optional(value.get("observableIdentity"), NativeRunCompletionCodec::string),
			optional(value.get("materialResponseEvidence"), NativeRunCompletionCodec::parseRef),
			parseRef(value.get("nativeEventEvidence")), string(value.get("runId")),
			string(value.get("conversationId")), longInteger(value.get("eventOrdinal")),
			instant(value.get("observedAt")),
			optional(value.get("limitation"), NativeRunCompletionCodec::string));
	}

	/**
	 * Reads an observation-coverage record.
	 *
	 * @param value the value value
	 * @return the parseCoverage value
	 */
	private static ObservationCoverage parseCoverage(JsonNode value)
	{
		fields(value, "domain", "status", "limitation", "evidence");
		return new ObservationCoverage(enumValue(value.get("domain"), ObservationDomain.class),
			enumValue(value.get("status"), ObservationCoverageStatus.class),
			optional(value.get("limitation"), NativeRunCompletionCodec::string),
			list(value.get("evidence"), NativeRunCompletionCodec::parseRef));
	}

	/**
	 * Computes the lowercase SHA-256 content identity.
	 *
	 * @param bytes the bytes value
	 * @return the sha256 value
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
