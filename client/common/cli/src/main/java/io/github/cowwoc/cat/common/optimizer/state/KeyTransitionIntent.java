package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical durable predecessor for one campaign key release or reacquisition.
 *
 * @param operation key operation whose campaign transition must finish
 * @param key canonical project and target identity
 * @param binding exact registered campaign identity
 * @param expectedCounter campaign counter before its transition
 * @param producerRecordRef immutable producer evidence for the transition
 * @param requestDigest digest of the exact transition request
 * @param expectedOwner campaign occupying the key before cancellation, absent for resumption
 * @param expectedPhase campaign phase before its transition
 * @param expectedClosedPhase active phase retained by cancellation or restored by resumption
 * @param expectedRetainedClaim latest per-key confirmed claim, when the campaign is confirmed
 */
record KeyTransitionIntent(KeyTransitionIntent.Operation operation, CampaignLookupKey key, CampaignBinding binding,
	long expectedCounter, EvidenceRef producerRecordRef, String requestDigest, Optional<String> expectedOwner,
	CampaignStatus expectedPhase, CampaignStatus expectedClosedPhase,
	Optional<LatestConfirmedClaim> expectedRetainedClaim)
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

	/**
	 * The only operations that change ownership of a campaign lookup key.
	 */
	enum Operation
	{
		CANCEL, RESUME
	}

	/**
	 * Validates the request-bound key transaction envelope.
	 */
	KeyTransitionIntent
	{
		if (operation == null || key == null || binding == null || producerRecordRef == null ||
			expectedOwner == null || expectedPhase == null || expectedClosedPhase == null ||
			expectedRetainedClaim == null || expectedCounter < 0 ||
			requestDigest == null || !requestDigest.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Key transition intent is incomplete");
		if (operation == Operation.CANCEL && !expectedOwner.equals(Optional.of(binding.campaignId())) ||
			operation == Operation.RESUME && expectedOwner.isPresent())
			throw new IllegalArgumentException("Key transition intent has the wrong predecessor owner");
		boolean activePhase = switch (expectedClosedPhase)
		{
			case AWAITING_CONTRACT_CONFIRMATION, DEVELOPING, FORMAL_TESTING -> true;
			default -> false;
		};
		if (!activePhase || operation == Operation.CANCEL && expectedPhase != expectedClosedPhase &&
			expectedPhase != CampaignStatus.INTERRUPTED || operation == Operation.RESUME &&
			expectedPhase != CampaignStatus.CANCELLED)
			throw new IllegalArgumentException("Key transition intent has an invalid saved phase");
		TransitionKind kind = transitionKind(operation);
		if (!requestDigest.equals(requestDigest(new TransitionRequest(binding, expectedCounter, kind,
			producerRecordRef))))
			throw new IllegalArgumentException("Key transition intent request digest differs from its request");
		if (expectedRetainedClaim.isPresent() && !expectedRetainedClaim.orElseThrow().key().equals(key))
			throw new IllegalArgumentException("Key transition intent retains a foreign claim");
	}

	/**
	 * Restores the exact transition request encoded by this intent.
	 *
	 * @return request with the original binding, counter, kind, and producer evidence
	 */
	TransitionRequest request()
	{
		TransitionKind kind = transitionKind(operation);
		return new TransitionRequest(binding, expectedCounter, kind, producerRecordRef);
	}

	/**
	 * Selects the campaign transition represented by one key operation.
	 *
	 * @param operation key operation
	 * @return corresponding campaign transition kind
	 */
	private static TransitionKind transitionKind(Operation operation)
	{
		return switch (operation)
		{
			case CANCEL -> TransitionKind.CANCELLATION;
			case RESUME -> TransitionKind.RESUMPTION;
		};
	}

	/**
	 * Decodes only the declared canonical schema-one JSON form.
	 *
	 * @param bytes complete durable intent bytes
	 * @return validated intent
	 * @throws IOException if the bytes are malformed or noncanonical
	 */
	static KeyTransitionIntent decode(byte[] bytes) throws IOException
	{
		try
		{
			String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			JsonNode value = JSON.readTree(text);
			fields(value, Set.of("schemaVersion", "operation", "key", "binding", "expectedCounter",
				"producerRecordRef", "requestDigest", "expectedPhase", "expectedClosedPhase"),
				Set.of("expectedOwner", "expectedRetainedClaim"));
			if (number(value.get("schemaVersion")) != 1)
				throw new IllegalArgumentException("Unsupported key transition intent schema");
			Operation operation = Operation.valueOf(string(value.get("operation")));
			CampaignLookupKey key = parseKey(value.get("key"));
			CampaignBinding binding = parseBinding(value.get("binding"));
			Optional<String> owner = Optional.empty();
			if (value.has("expectedOwner"))
				owner = Optional.of(string(value.get("expectedOwner")));
			Optional<LatestConfirmedClaim> claim = Optional.empty();
			if (value.has("expectedRetainedClaim"))
				claim = Optional.of(parseClaim(value.get("expectedRetainedClaim")));
			KeyTransitionIntent intent = new KeyTransitionIntent(operation, key, binding,
				number(value.get("expectedCounter")), parseRef(value.get("producerRecordRef")),
				string(value.get("requestDigest")), owner,
				CampaignStatus.valueOf(string(value.get("expectedPhase"))),
				CampaignStatus.valueOf(string(value.get("expectedClosedPhase"))), claim);
			if (!Arrays.equals(bytes, intent.encode()))
				throw new IOException("Key transition intent is noncanonical");
			return intent;
		}
		catch (CharacterCodingException exception)
		{
			throw new IOException("Key transition intent is not UTF-8", exception);
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Key transition intent is malformed", exception);
		}
	}

	/**
	 * Serializes one complete intent in the declared field order.
	 *
	 * @return compact canonical UTF-8 JSON
	 */
	byte[] encode()
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("schemaVersion", 1);
		node.put("operation", operation.name());
		node.set("key", keyNode(key));
		node.set("binding", bindingNode(binding));
		node.put("expectedCounter", expectedCounter);
		node.set("producerRecordRef", refNode(producerRecordRef));
		node.put("requestDigest", requestDigest);
		expectedOwner.ifPresent(owner -> node.put("expectedOwner", owner));
		node.put("expectedPhase", expectedPhase.name());
		node.put("expectedClosedPhase", expectedClosedPhase.name());
		expectedRetainedClaim.ifPresent(claim -> node.set("expectedRetainedClaim", claimNode(claim)));
		return JSON.writeValueAsBytes(node);
	}

	/**
	 * Computes the digest of the exact canonical transition request.
	 *
	 * @param request transition request
	 * @return lowercase SHA-256 digest
	 */
	static String requestDigest(TransitionRequest request)
	{
		return digest(requestBytes(request));
	}

	/**
	 * Encodes the four fields carried by both a request and an embedded receipt.
	 *
	 * @param request transition request
	 * @return canonical request bytes
	 */
	private static byte[] requestBytes(TransitionRequest request)
	{
		ObjectNode node = JSON.createObjectNode();
		node.set("binding", bindingNode(request.binding()));
		node.put("expectedCounter", request.expectedCounter());
		node.put("kind", request.kind().name());
		node.set("producerRecordRef", refNode(request.producerRecordRef()));
		return JSON.writeValueAsBytes(node);
	}

	/**
	 * Writes the project and target paths without Jackson's URI path conversion.
	 *
	 * @param key campaign lookup key
	 * @return canonical nested key
	 */
	private static ObjectNode keyNode(CampaignLookupKey key)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("canonicalProjectRoot", key.canonicalProjectRoot().toString());
		node.put("normalizedTargetRelativePath", key.normalizedTargetRelativePath().toString());
		return node;
	}

	/**
	 * Writes the exact registered campaign binding.
	 *
	 * @param binding campaign binding
	 * @return canonical nested binding
	 */
	private static ObjectNode bindingNode(CampaignBinding binding)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("campaignId", binding.campaignId());
		node.put("realDirectory", binding.realDirectory().toString());
		return node;
	}

	/**
	 * Writes one immutable evidence reference.
	 *
	 * @param reference evidence reference
	 * @return canonical nested reference
	 */
	private static ObjectNode refNode(EvidenceRef reference)
	{
		ObjectNode node = JSON.createObjectNode();
		node.put("collection", reference.collection());
		node.put("evidenceId", reference.evidenceId());
		return node;
	}

	/**
	 * Writes the exact retained confirmed claim.
	 *
	 * @param claim latest confirmed claim
	 * @return canonical nested claim
	 */
	private static ObjectNode claimNode(LatestConfirmedClaim claim)
	{
		ObjectNode node = JSON.createObjectNode();
		node.set("key", keyNode(claim.key()));
		node.put("campaignId", claim.campaignId());
		node.put("lineageId", claim.lineageId());
		node.put("contractId", claim.contractId());
		node.put("confirmationCounter", claim.confirmationCounter());
		node.put("confirmationSequence", claim.confirmationSequence());
		return node;
	}

	/**
	 * Parses and checks a canonical project/target key.
	 *
	 * @param value nested key JSON
	 * @return canonical key
	 */
	private static CampaignLookupKey parseKey(JsonNode value)
	{
		fields(value, Set.of("canonicalProjectRoot", "normalizedTargetRelativePath"), Set.of());
		return new CampaignLookupKey(path(value.get("canonicalProjectRoot")),
			path(value.get("normalizedTargetRelativePath")));
	}

	/**
	 * Parses and checks a pinned campaign binding.
	 *
	 * @param value nested binding JSON
	 * @return pinned binding
	 */
	private static CampaignBinding parseBinding(JsonNode value)
	{
		fields(value, Set.of("campaignId", "realDirectory"), Set.of());
		return new CampaignBinding(string(value.get("campaignId")), path(value.get("realDirectory")));
	}

	/**
	 * Parses an immutable evidence reference.
	 *
	 * @param value nested reference JSON
	 * @return evidence reference
	 */
	private static EvidenceRef parseRef(JsonNode value)
	{
		fields(value, Set.of("collection", "evidenceId"), Set.of());
		return new EvidenceRef(string(value.get("collection")), string(value.get("evidenceId")));
	}

	/**
	 * Parses a retained confirmed claim.
	 *
	 * @param value nested claim JSON
	 * @return confirmed claim
	 */
	private static LatestConfirmedClaim parseClaim(JsonNode value)
	{
		fields(value, Set.of("key", "campaignId", "lineageId", "contractId", "confirmationCounter",
			"confirmationSequence"), Set.of());
		return new LatestConfirmedClaim(parseKey(value.get("key")), string(value.get("campaignId")),
			string(value.get("lineageId")), string(value.get("contractId")),
			number(value.get("confirmationCounter")), number(value.get("confirmationSequence")));
	}

	/**
	 * Requires every declared field and refuses every unknown field.
	 *
	 * @param value object to check
	 * @param required fields that must be present
	 * @param optional fields that may be absent
	 */
	private static void fields(JsonNode value, Set<String> required, Set<String> optional)
	{
		if (value == null || !value.isObject() || !value.propertyNames().containsAll(required))
			throw new IllegalArgumentException("Key transition intent has missing object fields");
		Set<String> allowed = new HashSet<>(required);
		allowed.addAll(optional);
		if (!allowed.containsAll(value.propertyNames()))
			throw new IllegalArgumentException("Key transition intent has unknown object fields");
	}

	/**
	 * Reads a JSON string without coercion.
	 *
	 * @param value JSON node
	 * @return string value
	 */
	private static String string(JsonNode value)
	{
		if (value == null || !value.isString())
			throw new IllegalArgumentException("Key transition intent field is not a string");
		return value.stringValue();
	}

	/**
	 * Reads an exact integral JSON number.
	 *
	 * @param value JSON node
	 * @return long value
	 */
	private static long number(JsonNode value)
	{
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
			throw new IllegalArgumentException("Key transition intent field is not an integer");
		return value.longValue();
	}

	/**
	 * Reads a path without discarding a noncanonical spelling.
	 *
	 * @param value JSON node
	 * @return path with its original spelling
	 */
	private static Path path(JsonNode value)
	{
		String text = string(value);
		Path path = Path.of(text);
		if (!path.toString().equals(text))
			throw new IllegalArgumentException("Key transition intent path spelling is noncanonical");
		return path;
	}

	/**
	 * Computes a lowercase SHA-256 content identity.
	 *
	 * @param bytes canonical bytes
	 * @return lowercase hexadecimal digest
	 */
	private static String digest(byte[] bytes)
	{
		try
		{
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new AssertionError("SHA-256 is unavailable", exception);
		}
	}
}
