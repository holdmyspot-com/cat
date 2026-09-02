package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the stable frontend launch-gate registry.
 */
public final class FrontendLaunchGateTest
{
	/**
	 * Creates the test fixture.
	 */
	public FrontendLaunchGateTest()
	{
	}

	/**
	 * Verifies the exact required registry order and identifier parser.
	 */
	@Test
	public void exposesExactRequiredRegistry()
	{
		List<FrontendLaunchGate.Identifier> identifiers = FrontendLaunchGate.requiredIdentifiers();
		requireThat(identifiers.size(), "identifierCount").isEqualTo(10);
		requireThat(identifiers.getFirst(), "firstIdentifier").isEqualTo(FrontendLaunchGate.Identifier.PERSONAL_LIFECYCLE);
		requireThat(identifiers.getLast(), "lastIdentifier").isEqualTo(
			FrontendLaunchGate.Identifier.LICENSE_OFFER_CONSISTENCY);
		requireThat(FrontendLaunchGate.Identifier.fromExternalValue("prompt-system-boundary"), "parsedIdentifier").
			isEqualTo(FrontendLaunchGate.Identifier.PROMPT_SYSTEM_BOUNDARY);
	}

	/**
	 * Writes and reads strict, fingerprinted receipts and their ordered aggregate report.
	 *
	 * @throws IOException if the temporary release evidence cannot be created
	 */
	@Test
	public void persistsAndReadsPassedReceiptsAndReport() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("frontend-launch-gate-"));
			Path evidence = root.resolve("evidence.txt");
			Files.writeString(evidence, "verified evidence\n");
			String fingerprint = Fingerprints.ofFile(evidence);
			JsonMapper json = new JsonMapper();
			Map<FrontendLaunchGate.Identifier, Path> receiptPaths = new LinkedHashMap<>();
			List<FrontendLaunchGate.Receipt> receipts = new java.util.ArrayList<>();
			for (FrontendLaunchGate.Identifier identifier : FrontendLaunchGate.requiredIdentifiers())
			{
				Path receiptPath = root.resolve(identifier.externalValue() + ".json");
				FrontendLaunchGate.Receipt receipt = new FrontendLaunchGate.Receipt(identifier,
					FrontendLaunchGate.Status.PASSED, "mvn test", Instant.EPOCH, Instant.EPOCH,
					List.of(evidence), List.of(fingerprint), "");
				FrontendLaunchGate.writeReceipt(json, receiptPath, receipt);
				requireThat(FrontendLaunchGate.readReceipt(json, receiptPath), "storedReceipt").isEqualTo(receipt);
				receiptPaths.put(identifier, receiptPath);
				receipts.add(receipt);
			}
			Path reportPath = root.resolve("launch-gates.json");
			List<String> arguments = new java.util.ArrayList<>(List.of("1.0.0", reportPath.toString()));
			for (FrontendLaunchGate.Identifier identifier : FrontendLaunchGate.requiredIdentifiers())
				arguments.add(receiptPaths.get(identifier).toString());
			requireThat(FrontendLaunchGateMain.run(arguments.toArray(String[]::new)), "gateExitCode").isEqualTo(0);
			FrontendLaunchGate.Report report = FrontendLaunchGate.readReport(json, reportPath);
			requireThat(report.entries().stream().allMatch(entry -> entry.status() == FrontendLaunchGate.Status.PASSED),
				"allGatesPassed").isTrue();
			FrontendLaunchGate.requirePassed(receipts);
		}
	}

	/**
	 * Rejects a second write to the immutable receipt path.
	 *
	 * @throws IOException if the temporary release evidence cannot be created
	 */
	@Test(expectedExceptions = IOException.class)
	public void doesNotReplaceAnExistingReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("immutable-launch-gate-"));
			Path evidence = root.resolve("evidence.txt");
			Files.writeString(evidence, "verified evidence\n");
			FrontendLaunchGate.Receipt receipt = new FrontendLaunchGate.Receipt(
				FrontendLaunchGate.Identifier.PERSONAL_LIFECYCLE, FrontendLaunchGate.Status.PASSED, "mvn test",
				Instant.EPOCH, Instant.EPOCH, List.of(evidence), List.of(Fingerprints.ofFile(evidence)), "");
			Path destination = root.resolve("personal.json");
			FrontendLaunchGate.writeReceipt(new JsonMapper(), destination, receipt);
			FrontendLaunchGate.writeReceipt(new JsonMapper(), destination, receipt);
		}
	}

	/**
	 * Allocates separate immutable evidence directories when a launch gate is rerun for one release version.
	 *
	 * @throws IOException if temporary launch-gate evidence cannot be created
	 */
	@Test
	public void allocatesNewAttemptDirectories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("launch-gate-attempts-"));
			Path first = FrontendLaunchGate.createAttemptDirectory(root, "1.0");
			Path receipt = first.resolve("personal-lifecycle.json");
			Files.writeString(receipt, "first receipt\n");

			Path second = FrontendLaunchGate.createAttemptDirectory(root, "1.0");
			requireThat(second, "secondAttempt").isNotEqualTo(first);
			requireThat(Files.isDirectory(second), "secondAttemptExists").isTrue();
			requireThat(Files.readString(receipt), "firstReceipt").isEqualTo("first receipt\n");
		}
	}

	/**
	 * Rejects a release version that could escape its evidence directory.
	 *
	 * @throws IOException if temporary launch-gate evidence cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsEscapingVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("launch-gate-attempts-"));
			FrontendLaunchGate.createAttemptDirectory(root, "1.0/other-release");
		}
	}

	/**
	 * Rejects an unrun result that falsely records command execution.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsExecutedUnrunReceipt()
	{
		new FrontendLaunchGate.Receipt(FrontendLaunchGate.Identifier.PERSONAL_LIFECYCLE,
			FrontendLaunchGate.Status.UNRUN, "mvn test", null, null, List.of(), List.of(), "Codex unavailable");
	}

	/**
	 * Records an exclusion only through the aggregator's explicit authorization options.
	 *
	 * @throws IOException if the temporary release evidence cannot be created
	 */
	@Test
	public void recordsAuthorizedExclusion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("excluded-launch-gate-"));
			Path report = root.resolve("gate-report.json");
			List<String> arguments = new java.util.ArrayList<>(List.of("1.0.0", report.toString()));
			for (FrontendLaunchGate.Identifier identifier : FrontendLaunchGate.requiredIdentifiers())
				arguments.add(root.resolve(identifier.externalValue() + ".json").toString());
			arguments.addAll(List.of("--exclude", "platform-matrix", "--authorized-by", "product-owner",
				"--reason", "macOS arm64 evidence is unavailable"));
			requireThat(FrontendLaunchGateMain.run(arguments.toArray(String[]::new)), "gateExitCode").isEqualTo(1);
			Path excludedReceipt = root.resolve("platform-matrix.json");
			requireThat(FrontendLaunchGate.readReceipt(new JsonMapper(), excludedReceipt).status(), "excludedStatus").
				isEqualTo(FrontendLaunchGate.Status.EXCLUDED);
		}
	}

	/**
	 * Records a passed shell-command outcome through the command-line receipt boundary.
	 *
	 * @throws IOException if the temporary release evidence cannot be created
	 */
	@Test
	public void recordsShellOutcome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("shell-launch-gate-"));
			Path evidence = root.resolve("command.log");
			Files.writeString(evidence, "passed\n");
			Path receipt = root.resolve("receipt.json");
			String instant = "2026-09-01T00:00:00Z";
			requireThat(FrontendLaunchGateMain.run(new String[]{"record", "personal-lifecycle", receipt.toString(),
				"passed", "test command", instant, instant, "-", evidence.toString()}), "recordExitCode").isEqualTo(0);
			requireThat(FrontendLaunchGate.readReceipt(new JsonMapper(), receipt).evidencePaths(), "retainedEvidence").
				isEqualTo(List.of(evidence.toAbsolutePath().normalize()));
		}
	}

	/**
	 * Writes the backend handoff only from a report in which every required gate passed.
	 *
	 * @throws IOException if the temporary release evidence cannot be created
	 */
	@Test
	public void writesBackendHandoffFromPassedReport() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("backend-handoff-"));
			Path evidence = root.resolve("evidence.txt");
			Files.writeString(evidence, "verified evidence\n");
			JsonMapper json = new JsonMapper();
			Map<FrontendLaunchGate.Identifier, Path> receiptPaths = new LinkedHashMap<>();
			for (FrontendLaunchGate.Identifier identifier : FrontendLaunchGate.requiredIdentifiers())
			{
				Path receiptPath = root.resolve(identifier.externalValue() + ".json");
				FrontendLaunchGate.writeReceipt(json, receiptPath, new FrontendLaunchGate.Receipt(identifier,
					FrontendLaunchGate.Status.PASSED, "mvn test", Instant.EPOCH, Instant.EPOCH, List.of(evidence),
					List.of(Fingerprints.ofFile(evidence)), ""));
				receiptPaths.put(identifier, receiptPath);
			}
			Path reportPath = root.resolve("gate-report.json");
			FrontendLaunchGate.Report report = FrontendLaunchGate.writeReport(json, "1.0", reportPath, receiptPaths);
			Path personal = releaseTree(root, "personal");
			Path commercial = releaseTree(root, "commercial");
			Path offer = root.resolve("pricing.md");
			Files.writeString(offer, "Commercial offer\n");
			Path handoff = root.resolve("backend-handoff.json");

			BackendHandoff.write(json, report, handoff, reportPath, personal, commercial,
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "1.0", offer);

			String result = Files.readString(handoff);
			requireThat(result, "handoff").contains("\"schema_version\" : \"cat-backend-handoff-v1\"").
				contains("\"release_version\" : \"1.0\"").contains("\"tag\" : \"1.0\"").
				contains("https://github.com/catsforbots/cat/").
				contains("https://github.com/catsforbots/cat-work/").
				contains("bootstrap-<harness>.sh").doesNotContain("cat/bootstrap.sh");
		}
	}

	/**
	 * Creates the smallest edition tree accepted by the handoff boundary.
	 *
	 * @param root the temporary root
	 * @param edition the edition name
	 * @return the created tree
	 * @throws IOException if the tree cannot be created
	 */
	private static Path releaseTree(Path root, String edition) throws IOException
	{
		Path tree = root.resolve(edition);
		Files.createDirectories(tree);
		Files.writeString(tree.resolve("release-manifest.json"), "{\n  \"edition\" : \"" + edition + "\"\n}\n");
		return tree;
	}
}
