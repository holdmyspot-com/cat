package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessRunReceipt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates sealed reviewer bundles from selected candidate evidence.
 */
public final class CandidateReviews
{
	private static final String BUNDLE_MARKER = ".cat-prompt-review-bundle";
	/**
	 * Prevents construction of this static candidate-review utility.
	 */
	private CandidateReviews()
	{
	}

	/**
	 * Returns whether a directory is a bundle created by this review workflow.
	 *
	 * @param bundle the candidate bundle directory
	 * @return {@code true} if the bundle contains this workflow's marker file
	 */
	public static boolean isPrepared(Path bundle)
	{
		return Files.isRegularFile(bundle.resolve(BUNDLE_MARKER), NOFOLLOW_LINKS);
	}

	/**
	 * Returns the bounded reviewer instruction for related active failures.
	 *
	 * @param signatureKeys the acceptance conditions reviewed together
	 * @return the reviewer instruction
	 */
	public static String recurrenceQuestion(List<String> signatureKeys)
	{
		return "Review the retained prompt, selected route, assertions, and evidence for these active acceptance " +
			"conditions: " + String.join(", ", signatureKeys) + ". For each condition, identify the earliest prompt " +
			"decision, inventory the obligations that must remain, and choose either a compact restructuring or no prompt " +
			"change. Write review-result.json with a findings array. Each finding must contain signature_key, decision " +
			"(restructure or no_prompt_change), and preserved_obligations. Do not provide fixture answers or edit the " +
			"candidate.";
	}

	/**
	 * Returns the reviewer instruction for one changed candidate.
	 *
	 * @param obligationKeys the obligations that the revision must preserve
	 * @return the reviewer instruction
	 */
	public static String revisionQuestion(List<String> obligationKeys)
	{
		return "Review the proposed prompt revision against these retained obligations: " +
			String.join(", ", obligationKeys) + ". For each obligation, decide whether the revision preserves the " +
			"obligation, then identify the actor, direction, affected scope, correction target, and verification outcome. " +
			"Write review-result.json with a findings array. Each finding must contain obligation_key, preserved, actor, " +
			"direction, affected_scope, correction_target, and verification_outcome. Set preserved to false when the " +
			"revision reverses the actor direction, drops a correction target, broadens the affected scope, or otherwise " +
			"fails the " +
			"obligation. Do not edit the candidate or provide fixture answers.";
	}

	/**
	 * Reads the reviewer conclusion from the retained project named by a reviewer receipt.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param reviewerReceiptPath the retained runner receipt
	 * @return the retained result file and its structured conclusion
	 * @throws IOException if the receipt lacks a copied prepared bundle or review result
	 */
	public static RevisionResult revisionResult(JsonMapper json, Path reviewerReceiptPath) throws IOException
	{
		HarnessEvaluationResult reviewerRun = HarnessRunReceipt.fromReceipt(json.readTree(reviewerReceiptPath.toFile()),
			reviewerReceiptPath);
		Path reviewerProject = reviewerRun.retainedProject();
		if (!isPrepared(reviewerProject))
		{
			throw new IOException("Reviewer receipt does not retain a copy of the prepared revision-review bundle: " +
				reviewerReceiptPath);
		}
		Path result = reviewerProject.resolve("review-result.json");
		if (!Files.isRegularFile(result, NOFOLLOW_LINKS))
			throw new IOException("Reviewer receipt does not retain review-result.json: " + reviewerReceiptPath);
		JsonNode parsed = json.readTree(result.toFile());
		if (!parsed.isObject())
			throw new IOException("Revision-review result must be a JSON object: " + result);
		return new RevisionResult(result, (ObjectNode) parsed);
	}

	/**
	 * Adds one pending recurrence-review record to the candidate-state ledger.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param candidateStatePath the candidate-state ledger
	 * @param bundleDirectory the sealed review bundle
	 * @param sourceRunPath the run that supplied the review evidence
	 * @param signatureKeys the repeated signatures that require review
	 * @throws IOException if the ledger cannot record the pending review
	 */
	public static void prepareRecurrence(JsonMapper json, Path candidateStatePath, Path bundleDirectory,
		Path sourceRunPath,
		List<String> signatureKeys) throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path bundle = bundleDirectory.toAbsolutePath().normalize();
		Path sourceRun = sourceRunPath.toAbsolutePath().normalize();
		RecordStore.withLock(statePath, () ->
		{
			ObjectNode state = object(RecordStore.read(json, statePath), "candidate state", statePath);
			ArrayNode reviews = reviews(state, "recurrence_reviews", "recurrence reviews", statePath);
			for (JsonNode node : reviews)
			{
				ObjectNode review = object(node, "recurrence review", statePath);
				if (text(review, "bundle", statePath).equals(bundle.toString()))
					throw new IOException("A recurrence review already uses this bundle: " + bundle);
			}
			ObjectNode review = reviews.addObject();
			review.put("bundle", bundle.toString());
			review.put("source_run", sourceRun.toString());
			review.set("signature_keys", json.valueToTree(signatureKeys));
			review.put("status", "pending");
			review.put("prepared_at_epoch_millis", System.currentTimeMillis());
			RecordStore.write(json, statePath, state);
		});
	}

	/**
	 * Validates and records one recurrence-review conclusion.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param candidateStatePath the candidate-state ledger
	 * @param bundleDirectory the sealed review bundle
	 * @param resultPath the reviewer's conclusion inside that bundle
	 * @return the completed review record
	 * @throws IOException if the review is incomplete or does not belong to the ledger
	 */
	public static ObjectNode completeRecurrence(JsonMapper json, Path candidateStatePath, Path bundleDirectory,
		Path resultPath)
		throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path bundle = bundleDirectory.toAbsolutePath().normalize();
		Path result = resultPath.toAbsolutePath().normalize();
		if (!isPrepared(bundle))
			throw new IOException("Expected prepared recurrence-review bundle: " + bundle);
		if (!result.startsWith(bundle) || !Files.isRegularFile(result, NOFOLLOW_LINKS))
			throw new IOException("Expected a reviewer result inside its prepared bundle: " + result);
		ObjectNode conclusion = object(RecordStore.read(json, result), "recurrence-review result", result);
		ObjectNode[] completed = new ObjectNode[1];
		RecordStore.withLock(statePath, () ->
		{
			ObjectNode state = object(RecordStore.read(json, statePath), "candidate state", statePath);
			ObjectNode review = pendingReview(state, "recurrence_reviews", "recurrence reviews", statePath, bundle);
			Set<String> requiredKeys = values(review.path("signature_keys"), "review signature keys", statePath);
			Set<String> addressedKeys = new HashSet<>();
			for (JsonNode node : array(conclusion.path("findings"), "review findings", result))
			{
				ObjectNode finding = object(node, "review finding", result);
				String key = text(finding, "signature_key", result);
				String decision = text(finding, "decision", result);
				if (!Set.of("restructure", "no_prompt_change").contains(decision))
					throw new IOException("Review finding has unsupported decision: " + decision);
				if (array(finding.path("preserved_obligations"), "preserved obligations", result).isEmpty())
					throw new IOException("Review finding must link preserved obligations: " + key);
				addressedKeys.add(key);
			}
			if (!addressedKeys.equals(requiredKeys))
				throw new IOException("Review findings must address exactly the repeated failure signatures: " + requiredKeys);
			complete(review, result);
			RecordStore.write(json, statePath, state);
			completed[0] = review.deepCopy();
		});
		return completed[0];
	}

	/**
	 * Adds one pending changed-candidate review to the candidate-state ledger.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param candidateStatePath the candidate-state ledger
	 * @param bundleDirectory the sealed review bundle
	 * @param candidateIdentity the proposed candidate identity
	 * @param obligationKeys the obligations that review must address
	 * @throws IOException if the ledger cannot record the pending review
	 */
	public static void prepareRevision(JsonMapper json, Path candidateStatePath, Path bundleDirectory,
		String candidateIdentity,
		List<String> obligationKeys) throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path bundle = bundleDirectory.toAbsolutePath().normalize();
		RecordStore.withLock(statePath, () ->
		{
			ObjectNode state = object(RecordStore.read(json, statePath), "candidate state", statePath);
			ArrayNode reviews = reviews(state, "revision_reviews", "revision reviews", statePath);
			for (JsonNode node : reviews)
			{
				ObjectNode review = object(node, "revision review", statePath);
				if (text(review, "bundle", statePath).equals(bundle.toString()))
					throw new IOException("A revision review already uses this bundle: " + bundle);
			}
			ObjectNode review = reviews.addObject();
			review.put("bundle", bundle.toString());
			review.put("candidate_identity_sha256", candidateIdentity);
			review.set("obligation_keys", json.valueToTree(obligationKeys));
			review.put("status", "pending");
			review.put("prepared_at_epoch_millis", System.currentTimeMillis());
			RecordStore.write(json, statePath, state);
		});
	}

	/**
	 * Validates and records the conclusion that authorizes a changed candidate only when it preserves every obligation.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param candidateStatePath the candidate-state ledger
	 * @param bundleDirectory the sealed review bundle
	 * @param reviewerReceiptPath the retained runner receipt for the reviewer's copied project
	 * @return the completed review record
	 * @throws IOException if the review is incomplete, rejects a retained obligation, or does not belong to the ledger
	 */
	public static ObjectNode completeRevision(JsonMapper json, Path candidateStatePath, Path bundleDirectory,
		Path reviewerReceiptPath)
		throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path bundle = bundleDirectory.toAbsolutePath().normalize();
		if (!isPrepared(bundle))
			throw new IOException("Expected prepared revision-review bundle: " + bundle);
		RevisionResult reviewerResult = revisionResult(json, reviewerReceiptPath);
		Path result = reviewerResult.path();
		ObjectNode conclusion = reviewerResult.conclusion();
		ObjectNode[] completed = new ObjectNode[1];
		RecordStore.withLock(statePath, () ->
		{
			ObjectNode state = object(RecordStore.read(json, statePath), "candidate state", statePath);
			ObjectNode review = pendingReview(state, "revision_reviews", "revision reviews", statePath, bundle);
			Set<String> requiredKeys = values(review.path("obligation_keys"), "revision obligation keys", statePath);
			Set<String> addressedKeys = new HashSet<>();
			for (JsonNode node : array(conclusion.path("findings"), "revision-review findings", result))
			{
				ObjectNode finding = object(node, "revision-review finding", result);
				String key = text(finding, "obligation_key", result);
				if (!finding.path("preserved").isBoolean())
					throw new IOException("Revision-review finding must declare whether it preserves the obligation: " + key);
				if (!finding.path("preserved").asBoolean())
					throw new IOException("Revision-review finding does not preserve the retained obligation: " + key);
				for (String field : List.of("actor", "direction", "affected_scope", "correction_target",
					"verification_outcome"))
					if (text(finding, field, result).isBlank())
						throw new IOException("Revision-review finding must name its " + field + ": " + key);
				addressedKeys.add(key);
			}
			if (!addressedKeys.equals(requiredKeys))
			{
				throw new IOException("Revision-review findings must address exactly the retained obligations: " +
					requiredKeys);
			}
			complete(review, result);
			RecordStore.write(json, statePath, state);
			completed[0] = review.deepCopy();
		});
		return completed[0];
	}

	/**
	 * Returns all retained obligations that a changed candidate must address.
	 *
	 * @param state the current candidate state
	 * @param statePath the state source used in diagnostics
	 * @return stable obligation keys in deterministic order
	 * @throws IOException if an obligation record is malformed
	 */
	public static List<String> revisionObligationKeys(ObjectNode state, Path statePath) throws IOException
	{
		Set<String> keys = new TreeSet<>();
		if (state.path("failure_signatures").isArray())
		{
			for (JsonNode node : array(state.path("failure_signatures"), "failure signatures", statePath))
			{
				ObjectNode signature = object(node, "failure signature", statePath);
				if (!signature.has("cleared_by_certification"))
					keys.add(text(signature, "key", statePath));
			}
		}
		if (state.path("lineage").isObject())
		{
			ObjectNode lineage = object(state.path("lineage"), "candidate lineage", statePath);
			for (JsonNode node : array(lineage.path("obligations"), "lineage obligations", statePath))
			{
				ObjectNode obligation = object(node, "lineage obligation", statePath);
				if (!obligation.path("status").asString().equals("cleared"))
					keys.add(text(obligation, "key", statePath));
			}
		}
		return List.copyOf(keys);
	}

	/**
	 * Determines whether the current candidate identity still needs a completed revision review.
	 *
	 * @param state the current candidate state
	 * @param statePath the state source used in diagnostics
	 * @param candidateIdentity the proposed candidate identity
	 * @param obligationKeys the obligations the review must address
	 * @return whether no retained completion authorizes this identity
	 * @throws IOException if retained review evidence is malformed
	 */
	public static boolean requiresRevision(ObjectNode state, Path statePath, String candidateIdentity,
		List<String> obligationKeys) throws IOException
	{
		if (candidateAlreadyAddresses(state, statePath, candidateIdentity, obligationKeys))
			return false;
		for (JsonNode node : existingReviews(state, "revision_reviews", "revision reviews", statePath))
		{
			ObjectNode review = object(node, "revision review", statePath);
			if (!review.path("status").asString().equals("completed") ||
				!text(review, "candidate_identity_sha256", statePath).equals(candidateIdentity))
			{
				continue;
			}
			if (values(review.path("obligation_keys"), "revision obligation keys", statePath).
				equals(new HashSet<>(obligationKeys)))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Determines whether one repeated signature has a retained, untampered recurrence conclusion.
	 *
	 * @param state the current candidate state
	 * @param statePath the state source used in diagnostics
	 * @param signatureKey the required signature
	 * @return whether a completed recurrence review addresses the signature
	 * @throws IOException if retained review evidence is malformed
	 */
	public static boolean hasCompletedRecurrence(ObjectNode state, Path statePath, String signatureKey) throws IOException
	{
		for (JsonNode node : existingReviews(state, "recurrence_reviews", "recurrence reviews", statePath))
		{
			ObjectNode review = object(node, "recurrence review", statePath);
			if (!review.path("status").asString().equals("completed"))
				continue;
			for (JsonNode key : array(review.path("signature_keys"), "review signature keys", statePath))
				if (key.asString().equals(signatureKey) && resultAvailable(review, statePath))
					return true;
		}
		return false;
	}

	/**
	 * Creates one sealed reviewer input directory from selected readable evidence.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param temporaryRoot the CAT work temporary directory that owns the new bundle
	 * @param question the reviewer question to copy into the bundle
	 * @param bundleDirectory the initially absent bundle directory below {@code temporaryRoot}
	 * @param resultRelativePath the reviewer-writable result location relative to the bundle
	 * @param evidence the readable files or directories to copy into the bundle
	 * @return the copied question, evidence locations, and result location
	 * @throws IOException if an input is unsafe or the bundle cannot be created
	 */
	public static ObjectNode createBundle(JsonMapper json, Path temporaryRoot, Path question,
		Path bundleDirectory,
		String resultRelativePath,
		List<Path> evidence) throws IOException
	{
		if (!Files.isRegularFile(question, NOFOLLOW_LINKS))
			throw new IOException("Expected reviewer question file: " + question);
		if (evidence.isEmpty())
			throw new IOException("A reviewer bundle requires at least one evidence artifact");
		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path bundle = bundleDirectory.toAbsolutePath().normalize();
		if (!bundle.startsWith(temporary) || bundle.equals(temporary))
			throw new IOException("Reviewer bundle must be beneath the CAT work temp directory: " + bundle);
		if (Files.exists(bundle, NOFOLLOW_LINKS))
			throw new IOException("Reviewer bundle already exists: " + bundle);
		Path result = bundle.resolve(resultRelativePath).normalize();
		if (!result.startsWith(bundle) || result.equals(bundle))
			throw new IOException("Reviewer result path escapes its bundle: " + resultRelativePath);

		Files.createDirectories(bundle);
		try
		{
			Files.writeString(bundle.resolve(BUNDLE_MARKER), System.lineSeparator(), StandardCharsets.UTF_8);
			Path copiedQuestion = bundle.resolve("question.md");
			Files.copy(question, copiedQuestion);
			ArrayNode copiedEvidence = json.createArrayNode();
			for (int index = 0; index < evidence.size(); ++index)
			{
				Path source = evidence.get(index).toAbsolutePath().normalize();
				Path destination = bundle.resolve("evidence/" + (index + 1) + "-" + source.getFileName());
				copyEvidence(source, destination);
				copiedEvidence.add(destination.toString());
			}
			Files.createDirectories(result.getParent());
			ObjectNode receipt = json.createObjectNode();
			receipt.put("question", copiedQuestion.toString());
			receipt.set("evidence", copiedEvidence);
			receipt.put("result", result.toString());
			return receipt;
		}
		catch (IOException | RuntimeException e)
		{
			TemporaryDirectories.deleteRecursively(bundle);
			throw e;
		}
	}

	/**
	 * Returns mutable review records, creating their array only when this ledger has none yet.
	 *
	 * @param state the mutable candidate state
	 * @param field the review-array field
	 * @param description the review-array description for diagnostics
	 * @param statePath the state source used in diagnostics
	 * @return the mutable review records
	 * @throws IOException if the existing records are malformed
	 */
	private static ArrayNode reviews(ObjectNode state, String field, String description, Path statePath)
		throws IOException
	{
		JsonNode existing = state.path(field);
		if (existing.isMissingNode())
			return state.putArray(field);
		return array(existing, description, statePath);
	}

	/**
	 * Returns existing review records without changing a state queried for authorization.
	 *
	 * @param state the candidate state
	 * @param field the review-array field
	 * @param description the review-array description for diagnostics
	 * @param statePath the state source used in diagnostics
	 * @return the existing records, or an empty array when none were recorded
	 * @throws IOException if existing records are malformed
	 */
	private static ArrayNode existingReviews(ObjectNode state, String field, String description, Path statePath)
		throws IOException
	{
		JsonNode existing = state.path(field);
		if (existing.isMissingNode())
			return tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
		return array(existing, description, statePath);
	}

	/**
	 * Determines whether every obligation was already observed for the proposed candidate identity.
	 *
	 * @param state the candidate state
	 * @param statePath the state source used in diagnostics
	 * @param candidateIdentity the proposed candidate identity
	 * @param obligationKeys the obligations under review
	 * @return whether the retained observations already address every obligation
	 * @throws IOException if an obligation record is malformed
	 */
	private static boolean candidateAlreadyAddresses(ObjectNode state, Path statePath, String candidateIdentity,
		List<String> obligationKeys) throws IOException
	{
		for (String key : obligationKeys)
		{
			ObjectNode signature = failureSignature(state, statePath, key);
			if (signature != null)
			{
				if (!signature.hasNonNull("last_observed_candidate_sha256"))
					continue;
				if (!signature.path("last_observed_candidate_sha256").asString().equals(candidateIdentity))
					return false;
				continue;
			}
			ObjectNode obligation = lineageObligation(state, statePath, key);
			if (obligation == null || !obligation.path("covered_by_check").isObject())
				return false;
			ObjectNode coverage = object(obligation.path("covered_by_check"), "covered obligation", statePath);
			if (!coverage.path("candidate_identity_sha256").asString().equals(candidateIdentity))
				return false;
		}
		return true;
	}

	/**
	 * Finds one active failure signature by its ledger key.
	 *
	 * @param state the candidate state
	 * @param statePath the state source used in diagnostics
	 * @param key the requested signature key
	 * @return the signature, or {@code null} if it is absent
	 * @throws IOException if the signature ledger is malformed
	 */
	private static ObjectNode failureSignature(ObjectNode state, Path statePath, String key) throws IOException
	{
		if (!state.path("failure_signatures").isArray())
			return null;
		for (JsonNode node : array(state.path("failure_signatures"), "failure signatures", statePath))
		{
			ObjectNode signature = object(node, "failure signature", statePath);
			if (text(signature, "key", statePath).equals(key))
				return signature;
		}
		return null;
	}

	/**
	 * Finds one lineage obligation by its ledger key.
	 *
	 * @param state the candidate state
	 * @param statePath the state source used in diagnostics
	 * @param key the requested obligation key
	 * @return the obligation, or {@code null} if it is absent
	 * @throws IOException if the lineage ledger is malformed
	 */
	private static ObjectNode lineageObligation(ObjectNode state, Path statePath, String key) throws IOException
	{
		if (!state.path("lineage").isObject())
			return null;
		ObjectNode lineage = object(state.path("lineage"), "candidate lineage", statePath);
		for (JsonNode node : array(lineage.path("obligations"), "lineage obligations", statePath))
		{
			ObjectNode obligation = object(node, "lineage obligation", statePath);
			if (text(obligation, "key", statePath).equals(key))
				return obligation;
		}
		return null;
	}

	/**
	 * Determines whether a recorded conclusion still exists with its recorded digest.
	 *
	 * @param review the completed recurrence review
	 * @param statePath the state source used in diagnostics
	 * @return whether the conclusion is available and unchanged
	 * @throws IOException if the review record is malformed
	 */
	private static boolean resultAvailable(ObjectNode review, Path statePath) throws IOException
	{
		Path result = Path.of(text(review, "result", statePath));
		return Files.isRegularFile(result, NOFOLLOW_LINKS) &&
			sha256(result).equals(text(review, "result_sha256", statePath));
	}

	/**
	 * Finds one pending review whose bundle has the requested location.
	 *
	 * @param state the mutable candidate state
	 * @param field the review-array field
	 * @param description the review-array description for diagnostics
	 * @param statePath the state source used in diagnostics
	 * @param bundle the prepared review bundle
	 * @return the pending review
	 * @throws IOException if no pending review owns the bundle
	 */
	private static ObjectNode pendingReview(ObjectNode state, String field, String description, Path statePath,
		Path bundle) throws IOException
	{
		for (JsonNode node : reviews(state, field, description, statePath))
		{
			ObjectNode review = object(node, "candidate review", statePath);
			boolean pending = review.path("status").asString().equals("pending");
			if (pending && text(review, "bundle", statePath).equals(bundle.toString()))
				return review;
		}
		throw new IOException("Candidate state has no pending review for bundle: " + bundle);
	}

	/**
	 * Marks one validated review complete and binds its exact conclusion bytes.
	 *
	 * @param review the mutable pending review
	 * @param result the validated conclusion file
	 * @throws IOException if the conclusion cannot be hashed
	 */
	private static void complete(ObjectNode review, Path result) throws IOException
	{
		review.put("status", "completed");
		review.put("result", result.toString());
		review.put("result_sha256", sha256(result));
		review.put("completed_at_epoch_millis", System.currentTimeMillis());
	}

	/**
	 * Returns the required keys in one review field.
	 *
	 * @param node the key array
	 * @param description the key-array description for diagnostics
	 * @param source the state source used in diagnostics
	 * @return the declared keys
	 * @throws IOException if the key array is malformed
	 */
	private static Set<String> values(JsonNode node, String description, Path source) throws IOException
	{
		Set<String> result = new HashSet<>();
		for (JsonNode key : array(node, description, source))
			result.add(key.asString());
		return result;
	}

	/**
	 * Returns an object node or reports its invalid source shape.
	 *
	 * @param node the node to validate
	 * @param description the node's domain meaning
	 * @param source the source used in diagnostics
	 * @return the validated object
	 * @throws IOException if the node is not an object
	 */
	private static ObjectNode object(JsonNode node, String description, Path source) throws IOException
	{
		if (!node.isObject())
			throw new IOException("Expected " + description + " object: " + source);
		return (ObjectNode) node;
	}

	/**
	 * Returns an array node or reports its invalid source shape.
	 *
	 * @param node the node to validate
	 * @param description the node's domain meaning
	 * @param source the source used in diagnostics
	 * @return the validated array
	 * @throws IOException if the node is not an array
	 */
	private static ArrayNode array(JsonNode node, String description, Path source) throws IOException
	{
		if (!node.isArray())
			throw new IOException("Expected " + description + " array: " + source);
		return (ArrayNode) node;
	}

	/**
	 * Returns one required text field.
	 *
	 * @param object the object that owns the field
	 * @param field the required field
	 * @param source the source used in diagnostics
	 * @return the nonblank field value
	 * @throws IOException if the field is absent, non-textual, or blank
	 */
	private static String text(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Expected nonblank " + field + " in " + source);
		return value.asString();
	}

	/**
	 * Hashes one retained reviewer conclusion.
	 *
	 * @param file the conclusion file
	 * @return its SHA-256 value
	 * @throws IOException if the digest is unavailable or the file cannot be read
	 */
	private static String sha256(Path file) throws IOException
	{
		try
		{
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Copies one selected reviewer input without following symbolic links.
	 *
	 * @param source the selected source file or directory
	 * @param destination the initially absent bundle location
	 * @throws IOException if the source is unsafe or cannot be copied
	 */
	private static void copyEvidence(Path source, Path destination) throws IOException
	{
		if (Files.isRegularFile(source, NOFOLLOW_LINKS))
		{
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination);
			return;
		}
		if (Files.isDirectory(source, NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
		{
			EvaluationFiles.copyFixture(source, destination);
			return;
		}
		throw new IOException("Reviewer evidence is not a regular file or directory: " + source);
	}

	/**
	 * A reviewer result stored in its retained copied project.
	 *
	 * @param path the result-file location
	 * @param conclusion the structured reviewer conclusion
	 */
	record RevisionResult(Path path, ObjectNode conclusion)
	{
	}
}
