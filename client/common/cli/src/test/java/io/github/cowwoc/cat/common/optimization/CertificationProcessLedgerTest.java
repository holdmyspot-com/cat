package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests durable certification process transition replay, idempotency, and stale-writer rejection.
 */
public final class CertificationProcessLedgerTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public CertificationProcessLedgerTest()
	{
	}

	/**
	 * Verifies a retained transition replays to the registered successor and an identical retry retains its clock.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void replaysAndRetainsIdempotentTransition() throws IOException
	{
		Path manifest = Files.createTempDirectory("cat-certificationProcess-ledger-").resolve("certificationProcess.json");
		JsonMapper json = new JsonMapper();
		CertificationProcessLedger.initializeForFixture(json, manifest, "certificationProcess-1", SHA_A, SHA_B,
			OperationKind.GENERATE_CANDIDATES);
		Instant completedAt = Instant.parse("2026-09-01T00:00:00Z");
		CertificationProcessLedger.Entry first = CertificationProcessLedger.record(json, manifest, 0,
			OperationResultKind.CHALLENGERS,
			Optional.of(OperationKind.COMPARE_DEVELOPMENT_CANDIDATES), SHA_A, completedAt);

		CertificationProcessLedger.Entry retry = CertificationProcessLedger.record(json, manifest, 0,
			OperationResultKind.CHALLENGERS,
			Optional.of(OperationKind.COMPARE_DEVELOPMENT_CANDIDATES), SHA_A,
			completedAt.plusSeconds(60));
		CertificationProcessLedger.Snapshot snapshot = CertificationProcessLedger.read(json, manifest);

		requireThat(retry, "retry").isEqualTo(first);
		requireThat(snapshot.state().sequence(), "sequence").isEqualTo(1L);
		requireThat(snapshot.state().currentOperation().orElseThrow(), "currentOperation").isEqualTo(
			OperationKind.COMPARE_DEVELOPMENT_CANDIDATES);
	}

	/**
	 * Verifies a stale writer cannot append a different result at a completed sequence.
	 *
	 * @throws IOException if fixture persistence fails for an unexpected reason
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Completed certification process transition differs from its retry.*")
	public void rejectsChangedStaleRetry() throws IOException
	{
		Path manifest = Files.createTempDirectory("cat-certificationProcess-ledger-").resolve("certificationProcess.json");
		JsonMapper json = new JsonMapper();
		CertificationProcessLedger.initializeForFixture(json, manifest, "certificationProcess-1", SHA_A, SHA_B,
			OperationKind.GENERATE_CANDIDATES);
		CertificationProcessLedger.record(json, manifest, 0, OperationResultKind.CHALLENGERS,
			Optional.of(OperationKind.COMPARE_DEVELOPMENT_CANDIDATES), SHA_A,
			Instant.parse("2026-09-01T00:00:00Z"));

		CertificationProcessLedger.record(json, manifest, 0,
			OperationResultKind.NO_ELIGIBLE_CHALLENGER,
			Optional.of(OperationKind.DECIDE_READINESS), SHA_B,
			Instant.parse("2026-09-01T00:01:00Z"));
	}
}
