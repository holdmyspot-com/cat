package com.holdmyspot.cat.common.updater.trust;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Thrown when the updater refuses a release, its signature or its trust policy. The updater changes nothing after a
 * refusal and reports the reason to the user.
 */
public final class TrustException extends Exception
{
	private static final long serialVersionUID = 1L;
	/**
	 * The rule that the release, signature or policy broke.
	 */
	private final Reason reason;

	/**
	 * Creates an exception.
	 *
	 * @param reason  the rule that was broken
	 * @param message the field or rule that the input broke, in terms the user can act on
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code message} is blank
	 */
	public TrustException(Reason reason, String message)
	{
		super(message);
		requireThat(reason, "reason").isNotNull();
		requireThat(message, "message").isNotBlank();
		this.reason = reason;
	}

	/**
	 * Returns the rule that the release, signature or policy broke.
	 *
	 * @return the reason
	 */
	public Reason reason()
	{
		return reason;
	}

	/**
	 * The rules whose failure makes the updater refuse a release.
	 */
	public enum Reason
	{
		/**
		 * A release version is not two or three dot-separated decimal numbers without leading zeros.
		 */
		MALFORMED_VERSION,
		/**
		 * The manifest is larger than the updater reads.
		 */
		MANIFEST_TOO_LARGE,
		/**
		 * The signature file is not the base64 of a 64-byte Ed25519 signature, optionally followed by one newline.
		 */
		MALFORMED_SIGNATURE,
		/**
		 * The trust policy lists no release key, so no manifest can verify.
		 */
		NO_TRUSTED_KEYS,
		/**
		 * No key of the trust policy verifies the manifest's signature.
		 */
		SIGNATURE_NOT_VERIFIED,
		/**
		 * The verified manifest breaks a rule of the release manifest format.
		 */
		MALFORMED_MANIFEST,
		/**
		 * The trust policy breaks a rule of the policy format.
		 */
		MALFORMED_POLICY,
		/**
		 * The user requested a version other than the one that the manifest declares.
		 */
		VERSION_MISMATCH,
		/**
		 * The release is older than the policy's minimum version.
		 */
		BELOW_MINIMUM,
		/**
		 * The release's major version is newer than the policy allows.
		 */
		ABOVE_MAXIMUM_MAJOR,
		/**
		 * The release is older than the active installation, and only a user's rollback may install it.
		 */
		DOWNGRADE
	}
}
