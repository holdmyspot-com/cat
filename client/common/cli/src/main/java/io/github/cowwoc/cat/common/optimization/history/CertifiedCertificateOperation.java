package io.github.cowwoc.cat.common.optimization.history;

import io.github.cowwoc.cat.common.optimization.certification.CandidateAllowanceLedger;
import io.github.cowwoc.cat.common.optimization.certification.FamilyCertification;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialDecision;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialEngine;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialPolicyDocument;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialLedger;
import io.github.cowwoc.cat.common.RecordStore;
import io.github.cowwoc.cat.common.optimization.development.PromptTestIdentityLedger;
import io.github.cowwoc.cat.common.optimization.development.PromptTestPoolPartition;
import io.github.cowwoc.cat.common.optimization.evaluator.EvaluatorCalibrationDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues one locally signed certified result directly from verified formal ledgers and persisted evaluator approval.
 */
public final class CertifiedCertificateOperation
{
	private static final String CERTIFICATE_SCHEMA = "certificate-v1";

	/**
	 * Verifies the complete proof boundary and writes an idempotent locally signed certificate.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param allowanceManifest the lineage allowance manifest
	 * @param trialManifest the candidate formal-trial manifest
	 * @param statisticalPolicy the frozen statistical policy
	 * @param calibration the persisted selected evaluator approval
	 * @param promptTestIdentityLedger the durable contract-wide promptTest partition
	 * @param keyDirectory the installation-owned signing-key directory
	 * @param certificatePath the durable certificate history destination
	 * @return the new or identical retained local certificate
	 * @throws IOException if any proof input is inconsistent, uncertified, or cannot be retained
	 */
	public static LocalCertificate issue(JsonMapper json, Path allowanceManifest, Path trialManifest,
		FamilySequentialPolicyDocument.Loaded statisticalPolicy, EvaluatorCalibrationDocument.Loaded calibration,
		Path promptTestIdentityLedger, Path keyDirectory, Path certificatePath) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(allowanceManifest, "allowanceManifest must not be null");
		Objects.requireNonNull(trialManifest, "trialManifest must not be null");
		Objects.requireNonNull(statisticalPolicy, "statisticalPolicy must not be null");
		Objects.requireNonNull(calibration, "calibration must not be null");
		Objects.requireNonNull(promptTestIdentityLedger, "promptTestIdentityLedger must not be null");
		Objects.requireNonNull(keyDirectory, "keyDirectory must not be null");
		Objects.requireNonNull(certificatePath, "certificatePath must not be null");

		FamilySequentialDecision.Result decision = FamilySequentialEngine.decide(json, allowanceManifest,
			trialManifest, statisticalPolicy);
		if (decision.action() != FamilySequentialDecision.Action.CERTIFIED ||
			decision.certification().status() != FamilyCertification.Status.CERTIFIED)
		{
			throw new IOException("Formal evidence is not certified: " + decision.action());
		}
		FormalTrialLedger.Summary trials = FormalTrialLedger.read(json, trialManifest);
		verifyCalibration(trials, calibration);
		PromptTestIdentityLedger.Summary identities = PromptTestIdentityLedger.read(json, promptTestIdentityLedger);
		verifyPromptTestIdentities(trials, identities);
		CandidateAllowanceLedger.Snapshot allowance = CandidateAllowanceLedger.read(json, allowanceManifest);
		String protocolFingerprint = calibration.selectedProtocol().orElseThrow(() ->
			new IOException("Evaluator calibration has no approved protocol")).fingerprint();
		List<String> evidenceFingerprints = evidenceFingerprints(trials, identities, allowance, statisticalPolicy,
			calibration, protocolFingerprint);
		LocalCertificate.Payload payload = new LocalCertificate.Payload(CERTIFICATE_SCHEMA,
			LocalCertificate.TerminalResult.CERTIFIED, trials.identity().candidateId(),
			trials.identity().candidateFingerprint(), OptionalDouble.of(decision.certification().currentLowerBound()),
			statisticalPolicy.policy().decisionPolicy().target(), trials.identity().contractFingerprint(),
			trials.identity().environmentFingerprint(), allowance.ledgerFingerprint(), List.of(protocolFingerprint),
			statisticalPolicy.policy().statisticalRevision(), evidenceFingerprints);

		Path normalizedCertificate = certificatePath.toAbsolutePath().normalize();
		LocalCertificate[] result = new LocalCertificate[1];
		RecordStore.withLock(normalizedCertificate, () ->
		{
			LocalCertificateStore store = LocalCertificateStore.open(keyDirectory);
			if (Files.exists(normalizedCertificate))
			{
				result[0] = retained(store, json, normalizedCertificate, payload);
				return;
			}
			try
			{
				result[0] = store.sign(payload);
				store.write(json, normalizedCertificate, result[0]);
			}
			catch (GeneralSecurityException e)
			{
				throw new IOException("Local certificate signing failed", e);
			}
		});
		return result[0];
	}

	/**
	 * Verifies the formal manifest uses the exact persisted evaluator policy and selected approval.
	 *
	 * @param trials the verified formal trial summary
	 * @param calibration the persisted evaluator calibration
	 * @throws IOException if the evaluator identity differs or no protocol passed
	 */
	private static void verifyCalibration(FormalTrialLedger.Summary trials,
		EvaluatorCalibrationDocument.Loaded calibration) throws IOException
	{
		if (!trials.identity().evaluatorPolicyFingerprint().equals(calibration.policy().fingerprint()))
			throw new IOException("Formal trial ledger does not match the evaluator calibration policy");
		if (calibration.selectedProtocol().isEmpty() || calibration.selection().approval().isEmpty())
			throw new IOException("Evaluator calibration has no approved protocol");
		calibration.selection().approval().orElseThrow().verifyFor(calibration.selectedProtocol().orElseThrow());
	}

	/**
	 * Verifies every formal outcome used a pre-inference identity in the same certification contract.
	 *
	 * @param trials the verified formal trial summary
	 * @param identities the verified promptTest partition
	 * @throws IOException if a formal promptTest or contract identity is absent
	 */
	private static void verifyPromptTestIdentities(FormalTrialLedger.Summary trials,
		PromptTestIdentityLedger.Summary identities) throws IOException
	{
		if (!identities.contractFingerprint().equals(trials.identity().contractFingerprint()))
			throw new IOException("PromptTest identity ledger does not match the certified contract");
		Set<String> formalPromptTests = new HashSet<>();
		for (PromptTestPoolPartition.Identity identity : identities.identities())
		{
			if (identity.pool() == PromptTestPoolPartition.Pool.FORMAL)
				formalPromptTests.add(identity.instanceIdentifier());
		}
		if (!formalPromptTests.containsAll(trials.promptTestIdentifiers()))
			throw new IOException("Formal trial ledger contains a promptTest without a pre-inference reservation");
	}

	/**
	 * Builds the canonical reproduction evidence set committed by the certificate.
	 *
	 * @param trials the formal trial summary
	 * @param identities the promptTest partition summary
	 * @param allowance the lineage allowance summary
	 * @param statisticalPolicy the statistical policy
	 * @param calibration the evaluator calibration
	 * @param protocolFingerprint the selected evaluator protocol fingerprint
	 * @return the sorted evidence fingerprints
	 */
	private static List<String> evidenceFingerprints(FormalTrialLedger.Summary trials,
		PromptTestIdentityLedger.Summary identities, CandidateAllowanceLedger.Snapshot allowance,
		FamilySequentialPolicyDocument.Loaded statisticalPolicy, EvaluatorCalibrationDocument.Loaded calibration,
		String protocolFingerprint)
	{
		Set<String> result = new TreeSet<>(trials.evidenceFingerprints());
		result.add(trials.identity().candidateFingerprint());
		result.add(trials.identity().contractFingerprint());
		result.add(trials.identity().environmentFingerprint());
		result.add(trials.identity().allowanceEntryFingerprint());
		result.add(trials.ledgerFingerprint());
		result.add(identities.ledgerFingerprint());
		result.add(allowance.ledgerFingerprint());
		result.add(statisticalPolicy.fingerprint());
		result.add(calibration.fingerprint());
		result.add(protocolFingerprint);
		return List.copyOf(result);
	}

	/**
	 * Reads and verifies one existing idempotent certificate.
	 *
	 * @param store the installation signing store
	 * @param json the operation-owned JSON mapper
	 * @param certificatePath the retained certificate path
	 * @param expectedPayload the expected proof-derived payload
	 * @return the verified retained certificate
	 * @throws IOException if its signature or payload differs
	 */
	private static LocalCertificate retained(LocalCertificateStore store, JsonMapper json, Path certificatePath,
		LocalCertificate.Payload expectedPayload) throws IOException
	{
		try
		{
			LocalCertificateStore.VerifiedCertificate verified = store.readAndVerify(json, certificatePath, Set.of());
			if (verified.verification().integrity() != LocalCertificate.Integrity.VALID)
				throw new IOException("Retained local certificate has an invalid signature: " + certificatePath);
			if (!verified.certificate().payload().equals(expectedPayload))
				throw new IOException("Certified result retry changes retained certificate input: " + certificatePath);
			return verified.certificate();
		}
		catch (GeneralSecurityException e)
		{
			throw new IOException("Retained local certificate cannot be verified: " + certificatePath, e);
		}
	}

	/**
	 * Prevents construction of this static issuance owner.
	 */
	private CertifiedCertificateOperation()
	{
	}
}
