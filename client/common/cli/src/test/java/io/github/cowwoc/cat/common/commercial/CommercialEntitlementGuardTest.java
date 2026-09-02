package io.github.cowwoc.cat.common.commercial;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests renewal, local caching, offline grace, and entry-time Commercial authorization.
 */
public final class CommercialEntitlementGuardTest
{
	private static final Instant CONFIRMED = Instant.parse("2026-09-01T10:00:00Z");
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public CommercialEntitlementGuardTest()
	{
	}

	/**
	 * Closes the JSON scope after all tests.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a backend-renewed signed lease is cached and authorizes one new atomic operation.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws GeneralSecurityException if Ed25519 is unavailable
	 */
	@Test
	public void renewsCachesAndAuthorizes() throws IOException, GeneralSecurityException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("commercial-entitlement-guard-"));
			KeyPair keys = keys();
			EntitlementLease renewed = lease(keys, CONFIRMED);
			CommercialEntitlementGuard guard = new CommercialEntitlementGuard(scope.json(), root.resolve("lease.json"),
				"device-1", keys.getPublic(), device -> CommercialEntitlementGuard.Renewal.renewed(renewed));

			CommercialEntitlementGuard.Authorization authorization = guard.authorize(CONFIRMED.plus(Duration.ofHours(1)));

			requireThat(authorization.status(), "status").isEqualTo(EntitlementLease.Status.ALLOWED);
			requireThat(authorization.permit(), "permit").isPresent();
			requireThat(authorization.renewed(), "renewed").isTrue();
			requireThat(Files.exists(root.resolve("lease.json")), "cachedLease").isTrue();
		}
	}

	/**
	 * Verifies an unreachable backend uses a valid cached lease only through the seven-day grace boundary.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws GeneralSecurityException if Ed25519 is unavailable
	 */
	@Test
	public void appliesOfflineGraceToCachedLease() throws IOException, GeneralSecurityException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("commercial-entitlement-guard-"));
			KeyPair keys = keys();
			Path cache = root.resolve("lease.json");
			EntitlementLeaseDocument.write(scope.json(), cache, lease(keys, CONFIRMED));
			CommercialEntitlementGuard guard = new CommercialEntitlementGuard(scope.json(), cache, "device-1",
				keys.getPublic(), device -> CommercialEntitlementGuard.Renewal.unreachable());

			CommercialEntitlementGuard.Authorization withinGrace = guard.authorize(CONFIRMED.plus(Duration.ofDays(6)));
			CommercialEntitlementGuard.Authorization afterGrace = guard.authorize(CONFIRMED.plus(Duration.ofDays(8)));

			requireThat(withinGrace.status(), "withinGrace").isEqualTo(EntitlementLease.Status.OFFLINE_GRACE);
			requireThat(afterGrace.status(), "afterGrace").isEqualTo(EntitlementLease.Status.RECONNECT_REQUIRED);
		}
	}

	/**
	 * Verifies a confirmed backend denial overrides a cached offline allowance.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws GeneralSecurityException if Ed25519 is unavailable
	 */
	@Test
	public void rejectsConfirmedDenial() throws IOException, GeneralSecurityException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("commercial-entitlement-guard-"));
			KeyPair keys = keys();
			Path cache = root.resolve("lease.json");
			EntitlementLeaseDocument.write(scope.json(), cache, lease(keys, CONFIRMED));
			CommercialEntitlementGuard guard = new CommercialEntitlementGuard(scope.json(), cache, "device-1",
				keys.getPublic(), device -> CommercialEntitlementGuard.Renewal.denied());

			CommercialEntitlementGuard.Authorization authorization = guard.authorize(CONFIRMED.plus(Duration.ofDays(2)));

			requireThat(authorization.status(), "status").isEqualTo(EntitlementLease.Status.DENIED);
			requireThat(authorization.permit(), "permit").isEmpty();
		}
	}

	/**
	 * Verifies an unavailable backend cannot authorize a device that has no cached signed lease.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws GeneralSecurityException if Ed25519 is unavailable
	 */
	@Test
	public void requiresReconnectWithoutCachedLease() throws IOException, GeneralSecurityException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("commercial-entitlement-guard-"));
			KeyPair keys = keys();
			CommercialEntitlementGuard guard = new CommercialEntitlementGuard(scope.json(), root.resolve("lease.json"),
				"device-1", keys.getPublic(), device -> CommercialEntitlementGuard.Renewal.unreachable());

			CommercialEntitlementGuard.Authorization authorization = guard.authorize(CONFIRMED);

			requireThat(authorization.status(), "status").isEqualTo(EntitlementLease.Status.RECONNECT_REQUIRED);
			requireThat(authorization.permit(), "permit").isEmpty();
		}
	}

	/**
	 * Creates a signed lease fixture.
	 *
	 * @param keys the backend signing keys
	 * @param confirmed the confirmation time
	 * @return the signed lease
	 * @throws GeneralSecurityException if Ed25519 is unavailable
	 */
	private static EntitlementLease lease(KeyPair keys, Instant confirmed) throws GeneralSecurityException
	{
		return EntitlementLease.sign("lease-v1", "license-1", "device-1", confirmed,
			confirmed.plus(Duration.ofHours(24)), confirmed.plus(Duration.ofDays(30)), keys.getPrivate());
	}

	/**
	 * Creates an Ed25519 key pair.
	 *
	 * @return the key pair
	 * @throws GeneralSecurityException if Ed25519 is unavailable
	 */
	private static KeyPair keys() throws GeneralSecurityException
	{
		return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
	}
}
