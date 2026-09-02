package io.github.cowwoc.cat.common.commercial;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.time.Instant;
import org.testng.annotations.Test;

/**
 * Tests commercial lease lifetime, offline grace, and already-started atomic operations.
 */
public final class EntitlementLeaseTest
{
	private static final Instant CONFIRMED = Instant.parse("2026-09-01T00:00:00Z");

	/**
	 * Creates the test fixture.
	 */
	public EntitlementLeaseTest()
	{
	}

	/**
	 * Verifies a valid signed lease permits a new commercial operation during its normal lifetime.
	 */
	@Test
	public void permitsCurrentLease()
	{
		EntitlementLease.Decision decision = lease().authorize(CONFIRMED.plus(Duration.ofHours(12)),
			EntitlementLease.RenewalState.NOT_ATTEMPTED, _ -> true);

		requireThat(decision.status(), "status").isEqualTo(EntitlementLease.Status.ALLOWED);
		requireThat(decision.permit().isPresent(), "permitPresent").isTrue();
	}

	/**
	 * Verifies a backend outage uses at most the recorded seven-day grace period.
	 */
	@Test
	public void appliesOfflineGrace()
	{
		EntitlementLease.Decision withinGrace = lease().authorize(CONFIRMED.plus(Duration.ofDays(6)),
			EntitlementLease.RenewalState.UNREACHABLE, _ -> true);
		EntitlementLease.Decision afterGrace = lease().authorize(CONFIRMED.plus(Duration.ofDays(8)),
			EntitlementLease.RenewalState.UNREACHABLE, _ -> true);

		requireThat(withinGrace.status(), "withinGrace").isEqualTo(EntitlementLease.Status.OFFLINE_GRACE);
		requireThat(afterGrace.status(), "afterGrace").isEqualTo(EntitlementLease.Status.RECONNECT_REQUIRED);
	}

	/**
	 * Verifies revocation and invalid signatures cannot open a new commercial operation.
	 */
	@Test
	public void rejectsRevokedOrInvalidLease()
	{
		EntitlementLease.Decision revoked = lease().authorize(CONFIRMED.plus(Duration.ofHours(2)),
			EntitlementLease.RenewalState.DENIED, _ -> true);
		EntitlementLease.Decision invalid = lease().authorize(CONFIRMED.plus(Duration.ofHours(2)),
			EntitlementLease.RenewalState.NOT_ATTEMPTED, _ -> false);

		requireThat(revoked.status(), "revoked").isEqualTo(EntitlementLease.Status.DENIED);
		requireThat(invalid.status(), "invalid").isEqualTo(EntitlementLease.Status.INVALID_SIGNATURE);
	}

	/**
	 * Verifies an operation admitted under a valid lease may finish after the lease state changes.
	 */
	@Test
	public void finishesStartedAtomicOperation()
	{
		EntitlementLease.OperationPermit permit = lease().authorize(CONFIRMED.plus(Duration.ofHours(12)),
			EntitlementLease.RenewalState.NOT_ATTEMPTED, _ -> true).permit().orElseThrow();

		requireThat(permit.mayFinish(CONFIRMED.plus(Duration.ofDays(9))), "mayFinish").isTrue();
	}

	/**
	 * Creates a representative signed lease.
	 *
	 * @return the lease
	 */
	private static EntitlementLease lease()
	{
		return new EntitlementLease("lease-v1", "license-1", "device-1", CONFIRMED,
			CONFIRMED.plus(Duration.ofHours(24)), CONFIRMED.plus(Duration.ofDays(30)), new byte[64]);
	}
}
