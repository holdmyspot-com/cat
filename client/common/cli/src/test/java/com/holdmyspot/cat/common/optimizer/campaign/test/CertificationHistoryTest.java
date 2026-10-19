package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationHistory;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameters;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.PromptConfiguration;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.store.CertificationHistoryJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the certification history that a target keeps beside its prompt test configuration, and the file that holds
 * it.
 */
public final class CertificationHistoryTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Path TARGET = Path.of("CLAUDE.md");

	/**
	 * Creates the test.
	 */
	public CertificationHistoryTest()
	{
	}

	/**
	 * The history of a target lies in the target's test directory, beside its prompt test configuration.
	 */
	@Test
	public void historyLiesBesidePromptConfiguration()
	{
		// Requirement: R-M6
		requireThat(PromptTestLocations.certificationHistory(TARGET), "CLAUDE.md").
			isEqualTo(Path.of(".cat/tests/CLAUDE/certifications.json"));
		Path rule = Path.of("client/codex/rules/src/main/resources/rules/codex/read-file.md");
		requireThat(PromptTestLocations.certificationHistory(rule), "read-file.md").
			isEqualTo(PromptConfiguration.location(rule).resolveSibling("certifications.json"));
	}

	/**
	 * A record belongs to the harness named by the model provider that it recorded, and to none if it recorded no
	 * provider.
	 */
	@Test
	public void recordNamesItsHarness()
	{
		// Requirement: R-M6
		requireThat(record(1, "codex").harness(), "harness").isEqualTo(Optional.of("codex"));
		requireThat(record(1, null).harness(), "harness").isEqualTo(Optional.empty());
	}

	/**
	 * Appending a record to the history of a target that has no file creates a history of that record alone, and its
	 * file reads back as the same history.
	 *
	 * @throws IOException if the file cannot be written or read
	 */
	@Test
	public void appendToAbsentFileCreatesHistory() throws IOException
	{
		// Requirement: R-M6
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		CertificationRecord first = record(1, "claude");

		CertificationHistory history = CertificationHistory.EMPTY.appended(first);

		requireThat(history.records(), "records").isEqualTo(List.of(first));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, codec.write(history));
			requireThat(codec.read(file), "read").isEqualTo(history);
		}
	}

	/**
	 * Appending a record to an existing file adds it after the earlier records and leaves each earlier record's
	 * document as it was.
	 *
	 * @throws IOException if a file cannot be written or read
	 */
	@Test
	public void appendKeepsEarlierRecords() throws IOException
	{
		// Requirement: R-M6
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		CertificationRecord first = record(1, "claude");
		CertificationRecord second = record(2, "codex");
		CertificationRecord third = record(3, "claude");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, codec.write(CertificationHistory.EMPTY.appended(first).appended(second)));
			JsonNode before = JSON.readTree(Files.readAllBytes(file));

			Files.write(file, codec.write(codec.read(file).appended(third)));

			requireThat(codec.read(file).records(), "records").isEqualTo(List.of(first, second, third));
			JsonNode after = JSON.readTree(Files.readAllBytes(file));
			requireThat(after.get("records").size(), "size").isEqualTo(3);
			requireThat(after.get("records").get(0), "first").isEqualTo(before.get("records").get(0));
			requireThat(after.get("records").get(1), "second").isEqualTo(before.get("records").get(1));
		}
	}

	/**
	 * The latest record of each harness is the last one that the history lists for it, in the order of the history,
	 * and every other record is earlier history.
	 */
	@Test
	public void latestRecordOfEachHarness()
	{
		// Requirement: R-M6
		CertificationRecord claudeFirst = record(1, "claude");
		CertificationRecord codexFirst = record(1, "codex");
		CertificationRecord claudeSecond = record(2, "claude");
		CertificationHistory history = CertificationHistory.EMPTY.appended(claudeFirst).appended(codexFirst).
			appended(claudeSecond);

		requireThat(history.latest(), "latest").isEqualTo(List.of(codexFirst, claudeSecond));
		requireThat(history.earlier(), "earlier").isEqualTo(List.of(claudeFirst));
	}

	/**
	 * A record that names no harness is the latest of the records that name none.
	 */
	@Test
	public void recordsWithoutHarnessShareOneLatest()
	{
		// Requirement: R-M6
		CertificationRecord first = record(1, null);
		CertificationRecord second = record(2, null);
		CertificationHistory history = CertificationHistory.EMPTY.appended(first).appended(second);

		requireThat(history.latest(), "latest").isEqualTo(List.of(second));
		requireThat(history.earlier(), "earlier").isEqualTo(List.of(first));
	}

	/**
	 * Returns one malformed edit of a history document per row: the edit and the path that the refusal names.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] malformedEdits()
	{
		return new Object[][]
			{
				{"records[1].alpha", (Edit) root -> ((ObjectNode) root.get("records").get(1)).put("alpha", "0.03")},
				{"records[0].scope", (Edit) root -> ((ObjectNode) root.get("records").get(0)).remove("scope")},
				{"records[1].cells", (Edit) root -> ((ObjectNode) root.get("records").get(1)).put("cells", "none")},
				{"records[1]: must be an object", (Edit) root -> ((ArrayNode) root.get("records")).set(1, "text")},
				{"records: is missing", (Edit) root -> root.remove("records")},
				{"records: must be an array", (Edit) root -> root.put("records", "none")},
				{"format_version is 2", (Edit) root -> root.put("format_version", 2)}
			};
	}

	/**
	 * A history file that does not describe a valid history is refused, and the refusal names the file and the JSON
	 * path of the first problem.
	 *
	 * @param path the text that the refusal contains
	 * @param edit makes the problem in the document of a valid history of two records
	 * @throws IOException if the file cannot be written
	 */
	@Test(dataProvider = "malformedEdits")
	public void malformedFileIsRefusedAtItsPath(String path, Edit edit) throws IOException
	{
		// Requirement: R-M6
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		ObjectNode root = (ObjectNode) JSON.readTree(codec.write(
			CertificationHistory.EMPTY.appended(record(1, "claude")).appended(record(2, "codex"))));
		edit.apply(root);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, JSON.writeValueAsBytes(root));

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
			requireThat(failure.getMessage(), "message").contains(file.toString()).contains(path);
		}
	}

	/**
	 * A history file that is not JSON is refused with the file's name.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void fileThatIsNotJsonIsRefused() throws IOException
	{
		// Requirement: R-M6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.writeString(file, "{\"records\": [", UTF_8);

			IOException failure = null;
			try
			{
				new CertificationHistoryJson(JSON).read(file);
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "message").contains(file.toString()).contains("is not valid JSON");
		}
	}

	/**
	 * Returns the first certification of {@code CLAUDE.md} or a later one under the current parameters, measured by
	 * a harness.
	 *
	 * @param index the certification index
	 * @param harness the model provider that the record names, or {@code null} for none
	 * @return the record
	 */
	private static CertificationRecord record(int index, String harness)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.put(MeasurementCondition.MODEL, "model-a");
		if (harness != null)
			values.put(MeasurementCondition.PROVIDER, harness);
		return new CertificationRecord(TARGET, index, CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(index, Contract.DEFAULT_MINIMUM_PASS_RATE), List.of(),
			Map.of(TARGET, Sha256.of(("prompt " + index).getBytes(UTF_8))), new MeasurementIdentity(values));
	}

	/**
	 * Breaks a valid history document.
	 */
	@FunctionalInterface
	public interface Edit
	{
		/**
		 * Edits a document.
		 *
		 * @param root the document of a history
		 */
		void apply(ObjectNode root);
	}
}
