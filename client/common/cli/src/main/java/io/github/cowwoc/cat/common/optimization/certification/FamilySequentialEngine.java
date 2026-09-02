package io.github.cowwoc.cat.common.optimization.certification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binds formal family evidence to its pre-outcome candidate allowance before selecting the next statistical action.
 */
public final class FamilySequentialEngine
{
	/**
	 * Prevents construction of this static engine boundary.
	 */
	private FamilySequentialEngine()
	{
	}

	/**
	 * Verifies the immutable ledgers and selects the only permitted next action.
	 *
	 * @param json the engine-owned JSON mapper
	 * @param allowanceManifest the optimization-lineage allowance manifest
	 * @param trialManifest the frozen candidate's formal trial manifest
	 * @param policy the frozen family-sequential policy
	 * @return the weakest-family projection and next action
	 * @throws IOException if evidence is missing, malformed, or bound to another allowance
	 */
	public static FamilySequentialDecision.Result decide(JsonMapper json, Path allowanceManifest, Path trialManifest,
		FamilySequentialDecision.Policy policy) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(allowanceManifest, "allowanceManifest must not be null");
		Objects.requireNonNull(trialManifest, "trialManifest must not be null");
		Objects.requireNonNull(policy, "policy must not be null");
		FormalTrialLedger.Summary trials = FormalTrialLedger.read(json, trialManifest);
		CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.find(json, allowanceManifest,
			trials.identity().candidateId()).orElseThrow(() -> new IOException(
			"Formal candidate has no assigned lineage allowance: " + trials.identity().candidateId()));
		if (!assignment.entryFingerprint().equals(trials.identity().allowanceEntryFingerprint()))
			throw new IOException("Formal trial ledger does not match the candidate allowance entry");
		return FamilySequentialDecision.evaluate(trials.evidence(), assignment, policy);
	}

	/**
	 * Verifies the immutable ledgers against a loaded statistical policy and selects the next action.
	 *
	 * @param json the engine-owned JSON mapper
	 * @param allowanceManifest the optimization-lineage allowance manifest
	 * @param trialManifest the frozen candidate's formal trial manifest
	 * @param policy the loaded policy and canonical fingerprint
	 * @return the weakest-family projection and next action
	 * @throws IOException if evidence is malformed or bound to another policy or allowance
	 */
	public static FamilySequentialDecision.Result decide(JsonMapper json, Path allowanceManifest, Path trialManifest,
		FamilySequentialPolicyDocument.Loaded policy) throws IOException
	{
		Objects.requireNonNull(policy, "policy must not be null");
		FormalTrialLedger.Summary trials = FormalTrialLedger.read(json, trialManifest);
		if (!trials.identity().statisticalPolicyFingerprint().equals(policy.fingerprint()))
			throw new IOException("Formal trial ledger does not match the family-sequential policy");
		return decide(json, allowanceManifest, trialManifest, policy.policy().decisionPolicy());
	}
}
