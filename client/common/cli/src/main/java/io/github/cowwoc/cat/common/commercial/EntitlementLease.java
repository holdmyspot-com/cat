package io.github.cowwoc.cat.common.commercial;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Applies the signed commercial-lease lifetime and bounded offline-grace contract before an operation starts.
 */
public final class EntitlementLease
{
	private static final String SIGNATURE_ALGORITHM = "Ed25519";
	private static final Duration MAXIMUM_NORMAL_LIFETIME = Duration.ofHours(24);
	private static final Duration MAXIMUM_OFFLINE_GRACE = Duration.ofDays(7);
	private final String schemaVersion;
	private final String licenseIdentifier;
	private final String deviceIdentifier;
	private final Instant confirmedAt;
	private final Instant expiresAt;
	private final Instant entitlementEndsAt;
	private final byte[] signature;

	/**
	 * Creates one signed lease returned by CAT's account service.
	 *
	 * @param schemaVersion the lease schema version
	 * @param licenseIdentifier the commercial license identifier
	 * @param deviceIdentifier the registered device identifier
	 * @param confirmedAt the latest confirmed active entitlement time
	 * @param expiresAt the normal lease expiry, no more than 24 hours after confirmation
	 * @param entitlementEndsAt the paid entitlement end time
	 * @param signature the backend signature over the lease payload
	 */
	public EntitlementLease(String schemaVersion, String licenseIdentifier, String deviceIdentifier, Instant confirmedAt,
		Instant expiresAt, Instant entitlementEndsAt, byte[] signature)
	{
		this.schemaVersion = requireText(schemaVersion, "schemaVersion");
		this.licenseIdentifier = requireText(licenseIdentifier, "licenseIdentifier");
		this.deviceIdentifier = requireText(deviceIdentifier, "deviceIdentifier");
		if (confirmedAt == null)
			throw new NullPointerException("confirmedAt must not be null");
		if (expiresAt == null)
			throw new NullPointerException("expiresAt must not be null");
		if (entitlementEndsAt == null)
			throw new NullPointerException("entitlementEndsAt must not be null");
		if (expiresAt.isBefore(confirmedAt) || expiresAt.isAfter(confirmedAt.plus(MAXIMUM_NORMAL_LIFETIME)))
			throw new IllegalArgumentException("expiresAt must be within 24 hours after confirmedAt");
		if (!entitlementEndsAt.isAfter(confirmedAt))
			throw new IllegalArgumentException("entitlementEndsAt must be after confirmedAt");
		if (signature == null)
			throw new NullPointerException("signature must not be null");
		if (signature.length != 64)
			throw new IllegalArgumentException("Ed25519 signature must contain 64 bytes");
		this.confirmedAt = confirmedAt;
		this.expiresAt = expiresAt;
		this.entitlementEndsAt = entitlementEndsAt;
		this.signature = Arrays.copyOf(signature, signature.length);
	}

	/**
	 * Creates a backend-signed lease over the canonical payload.
	 *
	 * @param schemaVersion the lease schema version
	 * @param licenseIdentifier the commercial license identifier
	 * @param deviceIdentifier the registered device identifier
	 * @param confirmedAt the latest confirmed active entitlement time
	 * @param expiresAt the normal lease expiry
	 * @param entitlementEndsAt the paid entitlement end time
	 * @param privateKey the backend Ed25519 private key
	 * @return the signed lease
	 * @throws GeneralSecurityException if Ed25519 signing is unavailable
	 */
	public static EntitlementLease sign(String schemaVersion, String licenseIdentifier, String deviceIdentifier,
		Instant confirmedAt, Instant expiresAt, Instant entitlementEndsAt, PrivateKey privateKey)
		throws GeneralSecurityException
	{
		if (privateKey == null)
			throw new NullPointerException("privateKey must not be null");
		EntitlementLease unsigned = new EntitlementLease(schemaVersion, licenseIdentifier, deviceIdentifier,
			confirmedAt, expiresAt, entitlementEndsAt, new byte[64]);
		Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
		signer.initSign(privateKey);
		signer.update(unsigned.canonicalPayload());
		return new EntitlementLease(schemaVersion, licenseIdentifier, deviceIdentifier, confirmedAt, expiresAt,
			entitlementEndsAt, signer.sign());
	}

	/**
	 * Verifies this lease against CAT's installed backend public key.
	 *
	 * @param publicKey the backend Ed25519 public key
	 * @return whether the canonical payload matches its signature
	 */
	public boolean verify(PublicKey publicKey)
	{
		if (publicKey == null)
			throw new NullPointerException("publicKey must not be null");
		try
		{
			Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
			verifier.initVerify(publicKey);
			verifier.update(canonicalPayload());
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("Ed25519 lease verification is unavailable", e);
		}
	}

	/**
	 * Decides whether a new commercial-only atomic operation may start.
	 *
	 * @param now the current trusted clock value
	 * @param renewalState the latest renewal attempt result
	 * @param signatureVerifier the installed backend-key verifier
	 * @return the authorization result and operation permit when allowed
	 */
	public Decision authorize(Instant now, RenewalState renewalState, SignatureVerifier signatureVerifier)
	{
		if (now == null)
			throw new NullPointerException("now must not be null");
		if (renewalState == null)
			throw new NullPointerException("renewalState must not be null");
		if (signatureVerifier == null)
			throw new NullPointerException("signatureVerifier must not be null");
		if (!signatureVerifier.verify(this))
			return new Decision(Status.INVALID_SIGNATURE, Optional.empty(), reconnectDeadline());
		if (renewalState == RenewalState.DENIED)
			return new Decision(Status.DENIED, Optional.empty(), reconnectDeadline());
		if (!now.isBefore(entitlementEndsAt))
			return new Decision(Status.ENTITLEMENT_ENDED, Optional.empty(), reconnectDeadline());

		OperationPermit permit = new OperationPermit(licenseIdentifier, now);
		if (!now.isAfter(expiresAt))
			return new Decision(Status.ALLOWED, Optional.of(permit), reconnectDeadline());
		if (renewalState == RenewalState.UNREACHABLE && !now.isAfter(reconnectDeadline()))
			return new Decision(Status.OFFLINE_GRACE, Optional.of(permit), reconnectDeadline());
		return new Decision(Status.RECONNECT_REQUIRED, Optional.empty(), reconnectDeadline());
	}

	/**
	 * Returns the last instant at which an unreachable backend may use this confirmation.
	 *
	 * @return the offline-grace deadline
	 */
	public Instant reconnectDeadline()
	{
		return confirmedAt.plus(MAXIMUM_OFFLINE_GRACE);
	}

	/**
	 * Returns the lease schema version.
	 *
	 * @return the schema version
	 */
	public String schemaVersion()
	{
		return schemaVersion;
	}

	/**
	 * Returns the commercial license identifier.
	 *
	 * @return the license identifier
	 */
	public String licenseIdentifier()
	{
		return licenseIdentifier;
	}

	/**
	 * Returns the registered device identifier.
	 *
	 * @return the device identifier
	 */
	public String deviceIdentifier()
	{
		return deviceIdentifier;
	}

	/**
	 * Returns the last backend-confirmed entitlement time.
	 *
	 * @return the confirmation time
	 */
	public Instant confirmedAt()
	{
		return confirmedAt;
	}

	/**
	 * Returns the normal lease expiration time.
	 *
	 * @return the expiration time
	 */
	public Instant expiresAt()
	{
		return expiresAt;
	}

	/**
	 * Returns the paid entitlement end time.
	 *
	 * @return the entitlement end time
	 */
	public Instant entitlementEndsAt()
	{
		return entitlementEndsAt;
	}

	/**
	 * Returns a defensive copy of the backend signature.
	 *
	 * @return the signature
	 */
	public byte[] signature()
	{
		return Arrays.copyOf(signature, signature.length);
	}

	/**
	 * Produces the unambiguous canonical payload signed by the backend.
	 *
	 * @return the canonical payload bytes
	 */
	private byte[] canonicalPayload()
	{
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes))
			{
				writeString(output, schemaVersion);
				writeString(output, licenseIdentifier);
				writeString(output, deviceIdentifier);
				writeString(output, confirmedAt.toString());
				writeString(output, expiresAt.toString());
				writeString(output, entitlementEndsAt.toString());
			}
			return bytes.toByteArray();
		}
		catch (IOException e)
		{
			throw new AssertionError("In-memory lease encoding failed", e);
		}
	}

	/**
	 * Writes one UTF-8 string with a 32-bit byte length.
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
	 * Verifies the lease against CAT's installed backend public key.
	 */
	@FunctionalInterface
	public interface SignatureVerifier
	{
		/**
		 * Verifies the lease payload and signature.
		 *
		 * @param lease the lease
		 * @return whether its signature is valid
		 */
		boolean verify(EntitlementLease lease);
	}

	/**
	 * Names the latest backend renewal result.
	 */
	public enum RenewalState
	{
		NOT_ATTEMPTED,
		UNREACHABLE,
		DENIED
	}

	/**
	 * Names whether a new commercial operation may start.
	 */
	public enum Status
	{
		ALLOWED,
		OFFLINE_GRACE,
		RECONNECT_REQUIRED,
		ENTITLEMENT_ENDED,
		DENIED,
		INVALID_SIGNATURE
	}

	/**
	 * Holds one lease decision.
	 *
	 * @param status the authorization result
	 * @param permit the operation permit when a new atomic operation may start
	 * @param reconnectDeadline the last supported offline-grace instant
	 */
	public record Decision(Status status, Optional<OperationPermit> permit, Instant reconnectDeadline)
	{
		/**
		 * Validates one decision.
		 *
		 * @param status the authorization result
		 * @param permit the operation permit when a new atomic operation may start
		 * @param reconnectDeadline the last supported offline-grace instant
		 */
		public Decision
		{
			if (status == null)
				throw new NullPointerException("status must not be null");
			if (permit == null)
				throw new NullPointerException("permit must not be null");
			if (reconnectDeadline == null)
				throw new NullPointerException("reconnectDeadline must not be null");
		}
	}

	/**
	 * Proves that one atomic operation passed its entry-time entitlement check.
	 *
	 * @param licenseIdentifier the authorizing license
	 * @param admittedAt the operation admission time
	 */
	public record OperationPermit(String licenseIdentifier, Instant admittedAt)
	{
		/**
		 * Validates one operation permit.
		 *
		 * @param licenseIdentifier the authorizing license
		 * @param admittedAt the operation admission time
		 */
		public OperationPermit
		{
			licenseIdentifier = requireText(licenseIdentifier, "licenseIdentifier");
			if (admittedAt == null)
				throw new NullPointerException("admittedAt must not be null");
		}

		/**
		 * Confirms that a started atomic operation may finish even if entitlement later changes.
		 *
		 * @param completionTime the prospective completion time
		 * @return always {@code true} for the operation that owns this permit
		 */
		public boolean mayFinish(Instant completionTime)
		{
			if (completionTime == null)
				throw new NullPointerException("completionTime must not be null");
			if (completionTime.isBefore(admittedAt))
				throw new IllegalArgumentException("completionTime must not precede admittedAt");
			return true;
		}
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
}
