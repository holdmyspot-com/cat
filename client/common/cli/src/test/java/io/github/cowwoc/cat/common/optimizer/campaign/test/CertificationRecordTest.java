package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationParameters;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord.CellResult;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.ControlPairing;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.store.CertificationRecordJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the certification record that a certified delivery keeps in the project, and its file.
 * <p>
 * The fixture campaign certified its second attempt under the two-cell contract, with the weakest-cell scope. The
 * attempt recorded one formal failure and a per-cell trial model. The delivered snapshot holds the target
 * {@code CLAUDE.md}, the derived prompt source {@code rules/style.md} and the non-prompt file {@code README.md}.
 */
public final class CertificationRecordTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Path ROOT = Path.of("/project");
	private static final String FAILING_TEXT = "logs";
	/**
	 * The largest file that the fixture's record may need, which keeps the record a few kilobytes.
	 */
	private static final int MAXIMUM_SIZE = 4096;
	private static final String TONE = "uses_requested_tone";
	private static final PairCounts BIG_PAIRS = new PairCounts(110, 9, 1, 0);
	private static final PairCounts SMALL_PAIRS = new PairCounts(88, 10, 0, 2);
	private static final PairCounts SAFETY_PAIRS = new PairCounts(140, 8, 0, 2);

	/**
	 * Creates the test.
	 */
	public CertificationRecordTest()
	{
	}

	/**
	 * A certified attempt's record holds the certification index, the confirmed scope, the attempt's parameters, each
	 * cell's counts with its test and the pairs of each control comparison, the hash of each delivered in-project
	 * prompt file and the attempt's measurement conditions, and its file reads back as the same record.
	 *
	 * @throws IOException if the record file cannot be written or read
	 */
	@Test
	public void recordHoldsTheCertification() throws IOException
	{
		// Requirement: R-M6
		Campaign campaign = certifiedCampaign();
		CertificationAttempt attempt = campaign.attempt(2);

		CertificationRecord record = CertificationRecord.of(campaign, attempt, snapshot(), promptSources());

		requireThat(record.target(), "target").isEqualTo(Path.of("CLAUDE.md"));
		requireThat(record.certificationIndex(), "certificationIndex").isEqualTo(2);
		requireThat(record.scope(), "scope").isEqualTo(CertificationScope.WEAKEST_CELL);
		requireThat(record.parameters(), "parameters").isEqualTo(attempt.parameters());
		requireThat(record.cells(), "cells").isEqualTo(List.of(
			new CellResult("formatting", "formatting@big/high", 120, 0, Map.of(TONE, BIG_PAIRS)),
			new CellResult("formatting", "formatting@small/high", 99, 1, Map.of(TONE, SMALL_PAIRS)),
			new CellResult("safety", "safety", 150, 0, Map.of(TestCase.EXPECTED_OUTCOME, SAFETY_PAIRS))));
		requireThat(record.promptFiles(), "promptFiles").isEqualTo(Map.of(
			Path.of("CLAUDE.md"), hash("prompt v2"),
			Path.of("rules/style.md"), hash("style v1")));
		requireThat(record.identity(), "identity").isEqualTo(attempt.identity());

		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-record-")).resolve("CLAUDE.json");
			CertificationRecordJson codec = new CertificationRecordJson(JSON);
			Files.write(file, codec.write(record));
			requireThat(codec.read(file), "read").isEqualTo(record);
		}
	}

	/**
	 * A record file of format version 3, whose cells lack control comparisons, is refused rather than misread.
	 *
	 * @throws IOException if a record file cannot be written
	 */
	@Test
	public void refusesVersionThreeRecord() throws IOException
	{
		// Requirement: R-M6
		// Requirement: R-C11
		Campaign campaign = certifiedCampaign();
		CertificationRecord record = CertificationRecord.of(campaign, campaign.attempt(2), snapshot(),
			promptSources());
		CertificationRecordJson codec = new CertificationRecordJson(JSON);
		ObjectNode edited = (ObjectNode) JSON.readTree(codec.write(record));
		edited.put("format_version", 3);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-record-")).resolve("CLAUDE.json");
			Files.write(file, JSON.writeValueAsBytes(edited));
			IOException failure = null;
			try
			{
				codec.read(file);
			}
			catch (IOException e)
			{
				failure = e;
			}
			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "message").contains("format_version is 3");
		}
	}

	/**
	 * The record's file names the certification's fields and no trial result: it holds no formal failure's test case,
	 * and it stays under a few kilobytes.
	 */
	@Test
	public void recordFileHoldsNoTrialResults()
	{
		// Requirement: R-M6
		Campaign campaign = certifiedCampaign();
		CertificationRecord record = CertificationRecord.of(campaign, campaign.attempt(2), snapshot(),
			promptSources());

		byte[] bytes = new CertificationRecordJson(JSON).write(record);

		requireThat(bytes.length, "size").isLessThan(MAXIMUM_SIZE);
		String text = new String(bytes, UTF_8);
		requireThat(text, "text").doesNotContain(FAILING_TEXT);
		JsonNode root = JSON.readTree(bytes);
		requireThat(new HashSet<>(root.propertyNames()), "fields").isEqualTo(Set.of("format_version", "target",
			"certification_index", "scope", "minimum_pass_rate", "alpha", "cells", "prompt_files", "identity"));
		requireThat(root.get("scope").asString(), "scope").isEqualTo("weakest_cell");
	}

	/**
	 * The record's file writes the certification level as {@code alpha}, the decimal error probability
	 * {@code 1 / threshold}: exact when it terminates, otherwise rounded up to ten significant digits so that the
	 * written claim is never stronger than the certified one. Each value reads back as the same level, and a value that
	 * is not the reciprocal of an integer is refused.
	 *
	 * @throws IOException if a record file cannot be written or read
	 */
	@Test
	public void alphaIsTheLevelAsADecimal() throws IOException
	{
		// Requirement: R-M6
		CertificationRecordJson codec = new CertificationRecordJson(JSON);
		Map<Integer, String> expected = Map.of(1, "0.02", 2, "0.006666666667", 3, "0.003333333334");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-record-"));
			for (Map.Entry<Integer, String> entry : expected.entrySet())
			{
				int index = entry.getKey();
				CertificationRecord record = new CertificationRecord(Path.of("CLAUDE.md"), index,
					CertificationScope.LATEST_VERSIONS, CertificationParameters.current(index,
					Contract.DEFAULT_MINIMUM_PASS_RATE), List.of(),
					Map.of(Path.of("CLAUDE.md"), hash("prompt v1")), Builder.OBSERVED);
				byte[] bytes = codec.write(record);
				requireThat(JSON.readTree(bytes).get("alpha").asString(), "alpha").withContext(index, "index").
					isEqualTo(entry.getValue());
				Path file = directory.resolve("CLAUDE-" + index + ".json");
				Files.write(file, bytes);
				requireThat(codec.read(file), "read").withContext(index, "index").isEqualTo(record);
			}

			CertificationRecord record = new CertificationRecord(Path.of("CLAUDE.md"), 1,
				CertificationScope.LATEST_VERSIONS, CertificationParameters.current(1, Contract.DEFAULT_MINIMUM_PASS_RATE),
				List.of(), Map.of(),
				Builder.OBSERVED);
			ObjectNode edited = (ObjectNode) JSON.readTree(codec.write(record));
			edited.put("alpha", "0.03");
			Path file = directory.resolve("edited.json");
			Files.write(file, JSON.writeValueAsBytes(edited));
			IOException failure = null;
			try
			{
				codec.read(file);
			}
			catch (IOException e)
			{
				failure = e;
			}
			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "message").contains("alpha");
		}
	}

	/**
	 * The record of a target lives below {@code .cat/certifications}, named after the target without its extension.
	 */
	@Test
	public void locationDropsTheExtension()
	{
		// Requirement: R-M6
		requireThat(CertificationRecord.location(Path.of("CLAUDE.md")), "CLAUDE.md").
			isEqualTo(Path.of(".cat/certifications/CLAUDE.json"));
		requireThat(CertificationRecord.location(Path.of(".claude/skills/review/SKILL.md")), "SKILL.md").
			isEqualTo(Path.of(".cat/certifications/.claude/skills/review/SKILL.json"));
		requireThat(CertificationRecord.location(Path.of(".cursorrules")), ".cursorrules").
			isEqualTo(Path.of(".cat/certifications/.cursorrules.json"));
	}

	/**
	 * Returns a campaign whose second attempt certified the two-cell contract under the weakest-cell scope, with one
	 * formal failure, one control comparison per cell and a trial model that differs between cells.
	 *
	 * @return the campaign
	 */
	private static Campaign certifiedCampaign()
	{
		Map<MeasurementCondition, String> small = new EnumMap<>(MeasurementCondition.class);
		small.put(MeasurementCondition.MODEL, "small-model");
		MeasurementIdentity identity = new MeasurementIdentity(Builder.confirmedIdentity("big-model").values(),
			Map.of("formatting@small/high", small));
		DrawnTestCase failing = Builder.testCase("safety", FAILING_TEXT);
		return new Builder().
			confirmed(ContractFixtures.twoCellContract().withScope(CertificationScope.WEAKEST_CELL), 1).working(2).
			readyDevelopment().readyBaseline().
			certificationAttempt(1).cell("safety", 10, 5).outcome(Outcome.BELOW_MINIMUM).campaign().
			certificationAttempt(2).identity(identity).cell("formatting@big/high", 120, 0).
			pairing("formatting@big/high", pairing(TONE, BIG_PAIRS)).cell("formatting@small/high", 99, 1).
			pairing("formatting@small/high", pairing(TONE, SMALL_PAIRS)).cell("safety", 150, 0).
			pairing("safety", pairing(TestCase.EXPECTED_OUTCOME, SAFETY_PAIRS)).failure(failing).
			outcome(Outcome.CERTIFIED).
			campaign().lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).build();
	}

	/**
	 * Returns the control comparisons of a cell that compared one assertion.
	 *
	 * @param assertionId the identifier of the assertion
	 * @param pairs       the assertion's pairs
	 * @return the comparisons, with no unpaired formal trial
	 */
	private static ControlPairing pairing(String assertionId, PairCounts pairs)
	{
		return new ControlPairing(List.of(), Map.of(assertionId, pairs));
	}

	/**
	 * Returns the delivered snapshot.
	 *
	 * @return the files of the delivered candidate
	 */
	private static FileInventory snapshot()
	{
		return new FileInventory(ROOT, Map.of(
			Path.of("CLAUDE.md"), file("prompt v2"),
			Path.of("rules/style.md"), file("style v1"),
			Path.of("README.md"), file("readme v1"),
			Path.of("/home/user/shared.md"), file("shared v1")));
	}

	/**
	 * Returns the participating prompt sources: the target, the {@code rules} directory and a read-only external
	 * participant.
	 *
	 * @return the sources
	 */
	private static Set<Path> promptSources()
	{
		return Set.of(Path.of("CLAUDE.md"), Path.of("rules"), Path.of("/home/user/shared.md"));
	}

	/**
	 * Returns a regular file.
	 *
	 * @param text the file's content
	 * @return the entry
	 */
	private static FileEntry file(String text)
	{
		return new RegularFile(hash(text), PosixFilePermissions.fromString("rw-r--r--"));
	}

	/**
	 * Returns the hash of a text.
	 *
	 * @param text the text
	 * @return the hash of its UTF-8 bytes
	 */
	private static Sha256 hash(String text)
	{
		return Sha256.of(text.getBytes(UTF_8));
	}
}
