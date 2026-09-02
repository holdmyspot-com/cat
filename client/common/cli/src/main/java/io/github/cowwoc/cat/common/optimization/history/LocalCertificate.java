package io.github.cowwoc.cat.common.optimization.history;

import io.github.cowwoc.cat.common.optimization.ReproductionState;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;

/**
 * Holds a locally signed certificate whose trust claim is limited to owner-controlled integrity and reproduction.
 */
public final class LocalCertificate
{
	private static final String SIGNATURE_ALGORITHM = "Ed25519";
	private static final String TRUST_BOUNDARY = "Local integrity and reproducibility evidence; not independent CAT " +
		"attestation. A user who controls this installation can replace its key or forge local history.";
	private final Payload payload;
	private final String keyIdentifier;
	private final byte[] signature;

	/**
	 * Creates one signed local certificate.
	 *
	 * @param payload the immutable certificate claim and evidence identities
	 * @param keyIdentifier the local signing-key identifier
	 * @param signature the Ed25519 signature over the canonical payload
	 */
	private LocalCertificate(Payload payload, String keyIdentifier, byte[] signature)
	{
		if (payload == null)
			throw new NullPointerException("payload must not be null");
		this.payload = payload;
		this.keyIdentifier = requireText(keyIdentifier, "keyIdentifier");
		if (signature == null)
			throw new NullPointerException("signature must not be null");
		if (signature.length != 64)
			throw new IllegalArgumentException("Ed25519 signature must contain 64 bytes");
		this.signature = Arrays.copyOf(signature, signature.length);
	}

	/**
	 * Signs one certificate with an installation-controlled key.
	 *
	 * @param payload the immutable certificate claim and evidence identities
	 * @param privateKey the local Ed25519 private key
	 * @param keyIdentifier the local key identifier
	 * @return the signed certificate
	 * @throws GeneralSecurityException if the key cannot sign with Ed25519
	 */
	public static LocalCertificate sign(Payload payload, PrivateKey privateKey, String keyIdentifier)
		throws GeneralSecurityException
	{
		if (payload == null)
			throw new NullPointerException("payload must not be null");
		if (privateKey == null)
			throw new NullPointerException("privateKey must not be null");
		Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
		signer.initSign(privateKey);
		signer.update(payload.canonicalBytes());
		return new LocalCertificate(payload, keyIdentifier, signer.sign());
	}

	/**
	 * Restores one encoded certificate without treating import as signature verification.
	 *
	 * @param payload the encoded immutable payload
	 * @param keyIdentifier the encoded local key identifier
	 * @param signature the encoded Ed25519 signature
	 * @return the imported certificate, which must still be verified with its public key
	 */
	public static LocalCertificate restore(Payload payload, String keyIdentifier, byte[] signature)
	{
		return new LocalCertificate(payload, keyIdentifier, signature);
	}

	/**
	 * Verifies signature integrity and whether every retained evidence artifact remains available.
	 *
	 * @param publicKey the local Ed25519 public key corresponding to the recorded key identifier
	 * @param availableEvidenceFingerprints fingerprints of evidence currently retained on this installation
	 * @return the integrity and reproduction result
	 * @throws GeneralSecurityException if the public key cannot verify Ed25519 signatures
	 */
	public Verification verify(PublicKey publicKey, Set<String> availableEvidenceFingerprints)
		throws GeneralSecurityException
	{
		if (publicKey == null)
			throw new NullPointerException("publicKey must not be null");
		if (availableEvidenceFingerprints == null)
			throw new NullPointerException("availableEvidenceFingerprints must not be null");
		Set<String> available = new TreeSet<>();
		for (String fingerprint : availableEvidenceFingerprints)
			available.add(requireSha256(fingerprint, "availableEvidenceFingerprints element"));

		Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
		verifier.initVerify(publicKey);
		verifier.update(payload.canonicalBytes());
		if (!verifier.verify(signature))
			return new Verification(Integrity.INVALID, ReproductionState.UNAVAILABLE, payload.evidenceFingerprints());

		List<String> missing = new ArrayList<>(payload.evidenceFingerprints());
		missing.removeAll(available);
		if (missing.isEmpty())
			return new Verification(Integrity.VALID, ReproductionState.AVAILABLE, List.of());
		return new Verification(Integrity.VALID, ReproductionState.UNAVAILABLE, missing);
	}

	/**
	 * Produces the plain-language certificate projection.
	 *
	 * @param verification the current local integrity and reproduction result
	 * @return the display text
	 */
	public String display(Verification verification)
	{
		if (verification == null)
			throw new NullPointerException("verification must not be null");
		String currentLowerBound = "unavailable";
		if (payload.currentLowerBound().isPresent())
			currentLowerBound = percent(payload.currentLowerBound().getAsDouble());
		return "Current lower bound: " + currentLowerBound + "\n" +
			"Target: above " + percent(payload.target()) + "\n" +
			"Status: " + payload.terminalResult().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "\n" +
			"Integrity: " + verification.integrity().name().toLowerCase(Locale.ROOT) + "\n" +
			"Reproduction: " + verification.reproduction().name().toLowerCase(Locale.ROOT) + "\n" +
			"Trust: " + TRUST_BOUNDARY;
	}

	/**
	 * Returns a copy with a different payload and the original signature.
	 *
	 * <p>This operation supports import verification and tests: changing a signed payload must make verification
	 * fail.</p>
	 *
	 * @param replacement the replacement payload
	 * @return the deliberately unverified record
	 */
	public LocalCertificate withPayload(Payload replacement)
	{
		return new LocalCertificate(replacement, keyIdentifier, signature);
	}

	/**
	 * Returns the immutable signed payload.
	 *
	 * @return the payload
	 */
	public Payload payload()
	{
		return payload;
	}

	/**
	 * Returns the local key identifier.
	 *
	 * @return the key identifier
	 */
	public String keyIdentifier()
	{
		return keyIdentifier;
	}

	/**
	 * Returns a defensive copy of the signature.
	 *
	 * @return the Ed25519 signature bytes
	 */
	public byte[] signature()
	{
		return Arrays.copyOf(signature, signature.length);
	}

	/**
	 * Names a terminal optimization result.
	 */
	public enum TerminalResult
	{
		CERTIFIED,
		NOT_CERTIFIED
	}

	/**
	 * Names whether the local signature matches the payload.
	 */
	public enum Integrity
	{
		VALID,
		INVALID
	}

	/**
	 * Holds the immutable certificate claim and its exact evidence identities.
	 *
	 * @param schemaVersion the certificate schema version
	 * @param terminalResult the terminal result
	 * @param candidateIdentifier the immutable candidate identifier
	 * @param candidateFingerprint the immutable prompt-system candidate fingerprint
	 * @param currentLowerBound the scalar weakest-family bound, empty without formal evidence
	 * @param target the target that certification must exceed
	 * @param contractFingerprint the frozen certification-contract fingerprint
	 * @param environmentFingerprint the recorded environment fingerprint
	 * @param lineageLedgerFingerprint the candidate-allowance ledger fingerprint
	 * @param evaluatorProtocolFingerprints the frozen evaluator protocol fingerprints
	 * @param statisticalRevision the statistical method revision
	 * @param evidenceFingerprints every local artifact required for reproduction
	 */
	public record Payload(String schemaVersion, TerminalResult terminalResult, String candidateIdentifier,
		String candidateFingerprint, OptionalDouble currentLowerBound, double target, String contractFingerprint,
		String environmentFingerprint, String lineageLedgerFingerprint,
		List<String> evaluatorProtocolFingerprints, String statisticalRevision, List<String> evidenceFingerprints)
	{
		/**
		 * Validates and canonicalizes one certificate payload.
		 *
		 * @param schemaVersion the certificate schema version
		 * @param terminalResult the terminal result
		 * @param candidateIdentifier the immutable candidate identifier
		 * @param candidateFingerprint the immutable prompt-system candidate fingerprint
		 * @param currentLowerBound the scalar weakest-family bound, empty without formal evidence
		 * @param target the target that certification must exceed
		 * @param contractFingerprint the frozen certification-contract fingerprint
		 * @param environmentFingerprint the recorded environment fingerprint
		 * @param lineageLedgerFingerprint the candidate-allowance ledger fingerprint
		 * @param evaluatorProtocolFingerprints the frozen evaluator protocol fingerprints
		 * @param statisticalRevision the statistical method revision
		 * @param evidenceFingerprints every local artifact required for reproduction
		 */
		public Payload
		{
			schemaVersion = requireText(schemaVersion, "schemaVersion");
			if (terminalResult == null)
				throw new NullPointerException("terminalResult must not be null");
			candidateIdentifier = requireText(candidateIdentifier, "candidateIdentifier");
			candidateFingerprint = requireSha256(candidateFingerprint, "candidateFingerprint");
			if (currentLowerBound == null)
				throw new NullPointerException("currentLowerBound must not be null");
			if (currentLowerBound.isPresent())
				validateRate(currentLowerBound.getAsDouble(), "currentLowerBound", true);
			validateRate(target, "target", false);
			contractFingerprint = requireSha256(contractFingerprint, "contractFingerprint");
			environmentFingerprint = requireSha256(environmentFingerprint, "environmentFingerprint");
			lineageLedgerFingerprint = requireSha256(lineageLedgerFingerprint, "lineageLedgerFingerprint");
			evaluatorProtocolFingerprints = canonicalFingerprints(evaluatorProtocolFingerprints,
				"evaluatorProtocolFingerprints");
			statisticalRevision = requireText(statisticalRevision, "statisticalRevision");
			evidenceFingerprints = canonicalFingerprints(evidenceFingerprints, "evidenceFingerprints");
			if (terminalResult == TerminalResult.CERTIFIED &&
				(currentLowerBound.isEmpty() || currentLowerBound.getAsDouble() <= target))
			{
				throw new IllegalArgumentException("A certified payload requires a current lower bound above target");
			}
		}

		/**
		 * Produces an unambiguous canonical byte representation for signing.
		 *
		 * @return the canonical payload bytes
		 */
		private byte[] canonicalBytes()
		{
			try
			{
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				try (DataOutputStream output = new DataOutputStream(bytes))
				{
					writeString(output, schemaVersion);
					writeString(output, terminalResult.name());
					writeString(output, candidateIdentifier);
					writeString(output, candidateFingerprint);
					output.writeBoolean(currentLowerBound.isPresent());
					if (currentLowerBound.isPresent())
						output.writeLong(Double.doubleToLongBits(currentLowerBound.getAsDouble()));
					output.writeLong(Double.doubleToLongBits(target));
					writeString(output, contractFingerprint);
					writeString(output, environmentFingerprint);
					writeString(output, lineageLedgerFingerprint);
					writeStrings(output, evaluatorProtocolFingerprints);
					writeString(output, statisticalRevision);
					writeStrings(output, evidenceFingerprints);
				}
				return bytes.toByteArray();
			}
			catch (IOException e)
			{
				throw new AssertionError("In-memory certificate encoding failed", e);
			}
		}
	}

	/**
	 * Holds current verification state.
	 *
	 * @param integrity whether the local signature matches
	 * @param reproduction whether every required evidence artifact remains available
	 * @param missingEvidenceFingerprints missing evidence identities in canonical order
	 */
	public record Verification(Integrity integrity, ReproductionState reproduction,
		List<String> missingEvidenceFingerprints)
	{
		/**
		 * Validates and snapshots one verification result.
		 *
		 * @param integrity whether the local signature matches
		 * @param reproduction whether every required evidence artifact remains available
		 * @param missingEvidenceFingerprints missing evidence identities in canonical order
		 */
		public Verification
		{
			if (integrity == null)
				throw new NullPointerException("integrity must not be null");
			if (reproduction == null)
				throw new NullPointerException("reproduction must not be null");
			missingEvidenceFingerprints = List.copyOf(missingEvidenceFingerprints);
		}
	}

	/**
	 * Writes a canonical string list.
	 *
	 * @param output the destination
	 * @param values the strings
	 * @throws IOException if the in-memory stream reports a failure
	 */
	private static void writeStrings(DataOutputStream output, List<String> values) throws IOException
	{
		output.writeInt(values.size());
		for (String value : values)
			writeString(output, value);
	}

	/**
	 * Writes one UTF-8 string with an unambiguous 32-bit byte length.
	 *
	 * @param output the destination
	 * @param value the string
	 * @throws IOException if the in-memory stream reports a failure
	 */
	private static void writeString(DataOutputStream output, String value) throws IOException
	{
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.writeInt(bytes.length);
		output.write(bytes);
	}

	/**
	 * Formats a probability as a readable percentage.
	 *
	 * @param rate the probability
	 * @return the percentage
	 */
	private static String percent(double rate)
	{
		BigDecimal percentage = BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(100)).
			setScale(1, RoundingMode.HALF_UP).stripTrailingZeros();
		return percentage.toPlainString() + "%";
	}

	/**
	 * Validates one probability.
	 *
	 * @param value the probability
	 * @param name the parameter name
	 * @param inclusive whether endpoints are permitted
	 */
	private static void validateRate(double value, String name, boolean inclusive)
	{
		boolean invalid = Double.isNaN(value) || value < 0 || value > 1;
		if (!inclusive)
			invalid = invalid || value == 0 || value == 1;
		if (invalid)
			throw new IllegalArgumentException(name + " is outside its permitted probability range: " + value);
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Validates one lowercase SHA-256 value.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireSha256(String value, String name)
	{
		requireText(value, name);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
		return value;
	}

	/**
	 * Canonicalizes fingerprint lists.
	 *
	 * @param values the fingerprints
	 * @param name the parameter name
	 * @return the sorted unique fingerprints
	 */
	private static List<String> canonicalFingerprints(List<String> values, String name)
	{
		if (values == null)
			throw new NullPointerException(name + " must not be null");
		Set<String> result = new TreeSet<>();
		for (String value : values)
		{
			requireSha256(value, name + " element");
			if (!result.add(value))
				throw new IllegalArgumentException(name + " must not contain duplicates: " + value);
		}
		return List.copyOf(result);
	}
}
