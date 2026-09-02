package io.github.cowwoc.cat.common.commercial;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renews, verifies, caches, and applies a Commercial lease before a new atomic operation starts.
 */
public final class CommercialEntitlementGuard
{
	private final JsonMapper json;
	private final Path leaseCache;
	private final String deviceIdentifier;
	private final PublicKey backendPublicKey;
	private final LeaseRenewer renewer;

	/**
	 * Creates one client-side Commercial entitlement boundary.
	 *
	 * @param json the JSON codec
	 * @param leaseCache the owner-only local signed-lease cache
	 * @param deviceIdentifier the current pseudonymous registered device
	 * @param backendPublicKey CAT's installed lease-signing public key
	 * @param renewer the authenticated account-service renewal boundary
	 */
	public CommercialEntitlementGuard(JsonMapper json, Path leaseCache, String deviceIdentifier,
		PublicKey backendPublicKey, LeaseRenewer renewer)
	{
		this.json = Objects.requireNonNull(json, "json must not be null");
		this.leaseCache = Objects.requireNonNull(leaseCache, "leaseCache must not be null").toAbsolutePath().normalize();
		this.deviceIdentifier = requireText(deviceIdentifier, "deviceIdentifier");
		this.backendPublicKey = Objects.requireNonNull(backendPublicKey, "backendPublicKey must not be null");
		this.renewer = Objects.requireNonNull(renewer, "renewer must not be null");
	}

	/**
	 * Attempts startup renewal, then applies the signed lease and bounded offline-grace contract.
	 *
	 * @param now the trusted current time
	 * @return the entry decision and permit when a new atomic operation may start
	 * @throws IOException if renewal or the retained lease cannot be read or written safely
	 */
	public Authorization authorize(Instant now) throws IOException
	{
		Objects.requireNonNull(now, "now must not be null");
		Renewal renewal;
		try
		{
			renewal = renewer.renew(deviceIdentifier);
		}
		catch (IOException _)
		{
			renewal = Renewal.unreachable();
		}
		if (renewal.status() == RenewalStatus.RENEWED)
		{
			EntitlementLease lease = renewal.lease().orElseThrow();
			if (!lease.deviceIdentifier().equals(deviceIdentifier) || !lease.verify(backendPublicKey))
				return authorization(EntitlementLease.Status.INVALID_SIGNATURE, Optional.empty(), Optional.empty(), false);
			EntitlementLeaseDocument.write(json, leaseCache, lease);
			EntitlementLease.Decision decision = lease.authorize(now, EntitlementLease.RenewalState.NOT_ATTEMPTED,
				value -> value.verify(backendPublicKey));
			return authorization(decision, true);
		}

		if (!Files.exists(leaseCache))
		{
			EntitlementLease.Status status = EntitlementLease.Status.RECONNECT_REQUIRED;
			if (renewal.status() == RenewalStatus.DENIED)
				status = EntitlementLease.Status.DENIED;
			return authorization(status, Optional.empty(), Optional.empty(), false);
		}
		EntitlementLease cached = EntitlementLeaseDocument.read(json, leaseCache);
		if (!cached.deviceIdentifier().equals(deviceIdentifier))
			return authorization(EntitlementLease.Status.INVALID_SIGNATURE, Optional.empty(), Optional.empty(), false);
		EntitlementLease.RenewalState state = EntitlementLease.RenewalState.UNREACHABLE;
		if (renewal.status() == RenewalStatus.DENIED)
			state = EntitlementLease.RenewalState.DENIED;
		EntitlementLease.Decision decision = cached.authorize(now, state,
			value -> value.verify(backendPublicKey));
		return authorization(decision, false);
	}

	/**
	 * Converts one lease decision into the client boundary result.
	 *
	 * @param decision the lease decision
	 * @param renewed whether this invocation retained a new backend lease
	 * @return the authorization result
	 */
	private static Authorization authorization(EntitlementLease.Decision decision, boolean renewed)
	{
		return authorization(decision.status(), decision.permit(), Optional.of(decision.reconnectDeadline()), renewed);
	}

	/**
	 * Creates one client boundary result.
	 *
	 * @param status the lease decision
	 * @param permit the operation permit
	 * @param reconnectDeadline the offline-grace deadline when a lease exists
	 * @param renewed whether this invocation retained a new backend lease
	 * @return the authorization result
	 */
	private static Authorization authorization(EntitlementLease.Status status,
		Optional<EntitlementLease.OperationPermit> permit, Optional<Instant> reconnectDeadline, boolean renewed)
	{
		return new Authorization(status, permit, reconnectDeadline, renewed);
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
	 * Renews one device lease through the authenticated CAT account boundary.
	 */
	@FunctionalInterface
	public interface LeaseRenewer
	{
		/**
		 * Attempts to renew one pseudonymous device lease.
		 *
		 * @param deviceIdentifier the registered device identifier
		 * @return the typed renewal result
		 * @throws IOException if the service cannot return a trustworthy typed result
		 */
		Renewal renew(String deviceIdentifier) throws IOException;
	}

	/**
	 * Names the account-service renewal result.
	 */
	public enum RenewalStatus
	{
		RENEWED,
		UNREACHABLE,
		DENIED
	}

	/**
	 * Holds one renewal result without treating unavailability as denial.
	 *
	 * @param status the renewal status
	 * @param lease the newly signed lease only when renewed
	 */
	public record Renewal(RenewalStatus status, Optional<EntitlementLease> lease)
	{
		/**
		 * Validates one renewal result.
		 *
		 * @param status the renewal status
		 * @param lease the newly signed lease only when renewed
		 */
		public Renewal
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(lease, "lease must not be null");
			if ((status == RenewalStatus.RENEWED) != lease.isPresent())
				throw new IllegalArgumentException("Only a renewed result must contain a lease");
		}

		/**
		 * Creates a successful renewal.
		 *
		 * @param lease the signed lease
		 * @return the result
		 */
		public static Renewal renewed(EntitlementLease lease)
		{
			return new Renewal(RenewalStatus.RENEWED, Optional.of(lease));
		}

		/**
		 * Creates a service-unreachable result.
		 *
		 * @return the result
		 */
		public static Renewal unreachable()
		{
			return new Renewal(RenewalStatus.UNREACHABLE, Optional.empty());
		}

		/**
		 * Creates a confirmed entitlement-denied result.
		 *
		 * @return the result
		 */
		public static Renewal denied()
		{
			return new Renewal(RenewalStatus.DENIED, Optional.empty());
		}
	}

	/**
	 * Holds the complete result of one Commercial operation-entry check.
	 *
	 * @param status the lease decision
	 * @param permit the permit owned by an admitted atomic operation
	 * @param reconnectDeadline the offline-grace deadline when a lease exists
	 * @param renewed whether this invocation retained a new backend lease
	 */
	public record Authorization(EntitlementLease.Status status, Optional<EntitlementLease.OperationPermit> permit,
		Optional<Instant> reconnectDeadline, boolean renewed)
	{
		/**
		 * Validates one authorization result.
		 *
		 * @param status the lease decision
		 * @param permit the permit owned by an admitted atomic operation
		 * @param reconnectDeadline the offline-grace deadline when a lease exists
		 * @param renewed whether this invocation retained a new backend lease
		 */
		public Authorization
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(permit, "permit must not be null");
			Objects.requireNonNull(reconnectDeadline, "reconnectDeadline must not be null");
			boolean allowed = status == EntitlementLease.Status.ALLOWED ||
				status == EntitlementLease.Status.OFFLINE_GRACE;
			if (allowed != permit.isPresent())
				throw new IllegalArgumentException("Only an allowed authorization must contain an operation permit");
		}
	}
}
