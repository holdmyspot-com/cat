package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationHistory;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameters;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord.CellResult;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryLabel;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.PromptConfiguration;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.store.CertificationHistoryJson;

import java.io.IOException;
import java.math.BigDecimal;
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
	 * A trial plugin that the user installed.
	 */
	private static final List<TrialPlugin> PLUGINS = List.of(new TrialPlugin("cat", "a".repeat(64),
		new TrialPlugin.Installed(), List.of(), null));

	/**
	 * Creates the test.
	 */
	public CertificationHistoryTest()
	{
	}

	/**
	 * A certification history retains the accepted observations that qualify its accumulated measurements.
	 *
	 * @throws IOException if the history cannot be written or read
	 */
	@Test
	public void retainsAcceptedObservations() throws IOException
	{
		// Requirement: R-M6
		MeasurementIdentity original = new MeasurementIdentity(Map.of(MeasurementCondition.JUDGE, "original"));
		MeasurementIdentity accepted = original.acceptChanges(new MeasurementIdentity(
			Map.of(MeasurementCondition.JUDGE, "accepted")));
		CertificationHistory history = CertificationHistory.EMPTY.appended(recordOf(1, accepted));
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("accepted-history-")).resolve("certifications.json");
			Files.write(file, codec.write(history));

			requireThat(codec.read(file), "reloadedHistory").isEqualTo(history);
		}
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
	 * A joint receipt remains the latest entry for one evaluator after the other delivers again.
	 *
	 * @throws IOException if the history cannot be written or read
	 */
	@Test
	public void indexesJointReceiptByEachEvaluator() throws IOException
	{
		// Requirement: R-M6, R-L9
		CertificationRecord joint = jointRecord();
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("joint-history-")).resolve("certifications.json");
			Files.write(file, codec.write(CertificationHistory.EMPTY.appended(record(1, "claude")).appended(joint)));
			CertificationHistory history = codec.read(file);

			requireThat(history.latest(), "latest").isEqualTo(List.of(joint));
			requireThat(history.latestCertification(Optional.of("claude")), "claude").isEqualTo(Optional.of(joint));
			requireThat(history.latestCertification(Optional.of("codex")), "codex").isEqualTo(Optional.of(joint));
			requireThat(history.latestCertification(Optional.empty()), "unknown").isEqualTo(Optional.empty());
			requireThat(history.latestCertification(Optional.of("unused")), "unused").isEqualTo(Optional.empty());

			CertificationRecord newer = record(3, "claude");
			CertificationHistory updated = history.appended(newer);
			requireThat(updated.latest(), "updated").isEqualTo(List.of(joint, newer));
			requireThat(updated.latestCertification(Optional.of("claude")), "claude").isEqualTo(Optional.of(newer));
		}
	}

	/**
	 * Observing one evaluator's current environment leaves the other evaluator's conditions unchecked.
	 */
	@Test
	public void observesOnlyTheMatchingEvaluator()
	{
		// Requirement: R-M7, R-L9
		CertificationRecord joint = jointRecord();
		MeasurementIdentity known = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "codex",
			MeasurementCondition.HARNESS_VERSION, "codex-v1", MeasurementCondition.LOADER_CONFIGURATION, "codex-loader"));

		Applicability same = joint.applicability(joint.promptFiles(), known, Contract.DEFAULT_MINIMUM_PASS_RATE);

		requireThat(same.status(), "status").isEqualTo(Applicability.Status.CURRENT);
		requireThat(same.unchecked(), "unchecked").contains(MeasurementCondition.HARNESS_VERSION).
			contains(MeasurementCondition.LOADER_CONFIGURATION);
		MeasurementIdentity changed = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "codex",
			MeasurementCondition.HARNESS_VERSION, "codex-v2"));
		Applicability different = joint.applicability(joint.promptFiles(), changed, Contract.DEFAULT_MINIMUM_PASS_RATE);
		requireThat(different.status(), "changed").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(different.changed(), "conditions").isEqualTo(List.of(MeasurementCondition.HARNESS_VERSION));
	}

	/**
	 * A certification measured by one peer is indexed under that peer and compares its known loader conditions.
	 */
	@Test
	public void peerOnlyConditionChangesAreHistorical()
	{
		// Requirement: R-M7, R-L9
		MeasurementIdentity identity = new MeasurementIdentity(Map.of(), Map.of("example", Map.of(
			MeasurementCondition.PROVIDER, "claude", MeasurementCondition.LOADER_CONFIGURATION, "peer-loader")));
		CertificationRecord peer = new CertificationRecord(TARGET, 1, CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(1, Contract.DEFAULT_MINIMUM_PASS_RATE),
			List.of(new CellResult("example", "example", 100, 0, Map.of(), Map.of())), Map.of(), identity);
		CertificationHistory history = CertificationHistory.EMPTY.appended(peer);
		requireThat(peer.harness(), "harness").isEqualTo(Optional.of("claude"));
		requireThat(history.latestCertification(Optional.of("claude")), "peer").isEqualTo(Optional.of(peer));
		requireThat(history.latestCertification(Optional.of("codex")), "owner").isEqualTo(Optional.empty());

		MeasurementIdentity owner = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "codex",
			MeasurementCondition.LOADER_CONFIGURATION, "owner-loader"));
		Applicability unchecked = peer.applicability(peer.promptFiles(), owner, Contract.DEFAULT_MINIMUM_PASS_RATE);
		requireThat(unchecked.status(), "ownerStatus").isEqualTo(Applicability.Status.CURRENT);
		requireThat(unchecked.unchecked(), "unchecked").contains(MeasurementCondition.LOADER_CONFIGURATION);

		MeasurementIdentity changed = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "claude",
			MeasurementCondition.LOADER_CONFIGURATION, "new-peer-loader"));
		Applicability historical = peer.applicability(peer.promptFiles(), changed, Contract.DEFAULT_MINIMUM_PASS_RATE);
		requireThat(historical.status(), "peerStatus").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(historical.changed(), "conditions").isEqualTo(List.of(MeasurementCondition.LOADER_CONFIGURATION));
	}

	/**
	 * Only the harness that judged a trial can verify the judge version of a peer-only certification.
	 */
	@Test
	public void peerCertificateChecksOwnerJudge()
	{
		// Requirement: R-M7, R-L9
		MeasurementIdentity identity = new MeasurementIdentity(Map.of(
			MeasurementCondition.JUDGE_HARNESS, "codex", MeasurementCondition.JUDGE, "1"), Map.of("example",
			Map.of(MeasurementCondition.PROVIDER, "claude")));
		CertificationRecord peer = new CertificationRecord(TARGET, 1, CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(1, Contract.DEFAULT_MINIMUM_PASS_RATE),
			List.of(new CellResult("example", "example", 100, 0, Map.of(), Map.of())), Map.of(), identity);

		MeasurementIdentity actualJudge = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "codex",
			MeasurementCondition.JUDGE, "2"));
		Applicability changed = peer.applicability(peer.promptFiles(), actualJudge, Contract.DEFAULT_MINIMUM_PASS_RATE);
		requireThat(changed.status(), "ownerJudgeStatus").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(changed.changed(), "changed").isEqualTo(List.of(MeasurementCondition.JUDGE));

		MeasurementIdentity evaluator = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "claude",
			MeasurementCondition.JUDGE, "3"));
		Applicability unchecked = peer.applicability(peer.promptFiles(), evaluator, Contract.DEFAULT_MINIMUM_PASS_RATE);
		requireThat(unchecked.status(), "peerJudgeStatus").isEqualTo(Applicability.Status.CURRENT);
		requireThat(unchecked.unchecked(), "unchecked").contains(MeasurementCondition.JUDGE);
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
	 * A record stays current while each trial plugin's manifest declares the version that the record holds, and becomes
	 * historical, naming the trial plugin versions, once a plugin declares another version in an unchanged base.
	 */
	@Test
	public void pluginVersionChangeMakesRecordHistorical()
	{
		// Requirement: R-M7
		CertificationRecord recorded = recordOf(1, MeasurementIdentity.ofPlugins(PLUGINS, Map.of("cat", "1.0")));

		Applicability same = recorded.applicability(recorded.promptFiles(),
			MeasurementIdentity.ofPlugins(PLUGINS, Map.of("cat", "1.0")), Contract.DEFAULT_MINIMUM_PASS_RATE);
		Applicability updated = recorded.applicability(recorded.promptFiles(),
			MeasurementIdentity.ofPlugins(PLUGINS, Map.of("cat", "1.1")), Contract.DEFAULT_MINIMUM_PASS_RATE);

		requireThat(same.status(), "same").isEqualTo(Applicability.Status.CURRENT);
		requireThat(updated.status(), "updated").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(updated.changed(), "changed").containsExactly(List.of(MeasurementCondition.PLUGIN_VERSIONS));
	}

	/**
	 * A record that CAT wrote before it recorded plugin versions stays current when CAT now knows the versions,
	 * because it holds no version that could differ.
	 */
	@Test
	public void recordWithoutPluginVersionsStaysCurrent()
	{
		// Requirement: R-M7
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(MeasurementIdentity.ofPlugins(PLUGINS, Map.of("cat", "1.0")).values());
		values.remove(MeasurementCondition.PLUGIN_VERSIONS);
		CertificationRecord recorded = recordOf(1, new MeasurementIdentity(values));

		Applicability known = recorded.applicability(recorded.promptFiles(),
			MeasurementIdentity.ofPlugins(PLUGINS, Map.of("cat", "1.1")), Contract.DEFAULT_MINIMUM_PASS_RATE);

		requireThat(known.status(), "status").isEqualTo(Applicability.Status.CURRENT);
		requireThat(known.unchecked(), "unchecked").isEmpty();
	}

	/**
	 * Each trial plugin's version is written to the history file as one line of {@code plugin_versions} and reads back
	 * as the same record; a record written without the field reads back without plugin versions.
	 *
	 * @throws IOException if the file cannot be written or read
	 */
	@Test
	public void pluginVersionsRoundTrip() throws IOException
	{
		// Requirement: R-M7
		List<TrialPlugin> plugins = List.of(PLUGINS.getFirst(),
			new TrialPlugin("formatter@acme-tools", "b".repeat(64), new TrialPlugin.Installed(), List.of(), null));
		CertificationRecord recorded = recordOf(1, MeasurementIdentity.ofPlugins(plugins,
			Map.of("cat", "1.0", "formatter@acme-tools", "2.3.1")));
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		byte[] document = codec.write(CertificationHistory.EMPTY.appended(recorded));
		ObjectNode root = (ObjectNode) JSON.readTree(document);
		ObjectNode conditions = (ObjectNode) root.get("records").get(0).get("identity").get("conditions");

		requireThat(conditions.path("plugin_versions").asString(""), "plugin_versions").
			isEqualTo("cat 1.0\nformatter@acme-tools 2.3.1");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, document);
			requireThat(codec.read(file).records(), "read").isEqualTo(List.of(recorded));

			conditions.remove("plugin_versions");
			Files.write(file, JSON.writeValueAsBytes(root));
			requireThat(codec.read(file).records().getFirst().identity().values().keySet(), "withoutVersions").
				doesNotContain(MeasurementCondition.PLUGIN_VERSIONS);
		}
	}

	/**
	 * A record written while CAT still recorded the trial model's provider reads back without it: the provider is
	 * dropped from the record's conditions and from the conditions of each of its cells.
	 *
	 * @throws IOException if the file cannot be written or read
	 */
	@Test
	public void readsRecordWithRetiredModelProvider() throws IOException
	{
		// Requirement: R-M7
		CertificationRecord recorded = recordOf(1, new MeasurementIdentity(
			Map.of(MeasurementCondition.PROVIDER, "codex"),
			Map.of("read-file/luna-low", Map.of(MeasurementCondition.MODEL, "gpt-6-luna",
				MeasurementCondition.REASONING, "low"))));
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		ObjectNode root = (ObjectNode) JSON.readTree(codec.write(CertificationHistory.EMPTY.appended(recorded)));
		ObjectNode identity = (ObjectNode) root.get("records").get(0).get("identity");
		((ObjectNode) identity.get("conditions")).put("model_provider", "openai");
		((ObjectNode) identity.get("cells").get("read-file/luna-low")).put("model_provider", "openai");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, JSON.writeValueAsBytes(root));

			requireThat(codec.read(file).records(), "read").isEqualTo(List.of(recorded));
		}
	}

	/**
	 * A record written while CAT recorded the host tools and outside sources that trials used reads back without
	 * them: both are dropped from the record's conditions and from the conditions of each of its cells.
	 *
	 * @throws IOException if the file cannot be written or read
	 */
	@Test
	public void readsRecordWithRetiredToolsAndSources() throws IOException
	{
		// Requirement: R-M8
		CertificationRecord recorded = recordOf(1, new MeasurementIdentity(
			Map.of(MeasurementCondition.PROVIDER, "codex"),
			Map.of("read-file/luna-low", Map.of(MeasurementCondition.MODEL, "gpt-6-luna",
				MeasurementCondition.REASONING, "low"))));
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		ObjectNode root = (ObjectNode) JSON.readTree(codec.write(CertificationHistory.EMPTY.appended(recorded)));
		ObjectNode identity = (ObjectNode) root.get("records").get(0).get("identity");
		((ObjectNode) identity.get("conditions")).put("host_tools", "git 2.51.0\nrg 14.1.0");
		((ObjectNode) identity.get("conditions")).put("external_sources", "github (MCP server)");
		((ObjectNode) identity.get("cells").get("read-file/luna-low")).put("external_sources", "web search");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, JSON.writeValueAsBytes(root));

			requireThat(codec.read(file).records(), "read").isEqualTo(List.of(recorded));
		}
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
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).putNull("control_margin")},
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).put("control_margin", 0.05)},
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).put("control_margin", true)},
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).putObject("control_margin")},
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).putArray("control_margin")},
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).put("control_margin", "invalid")},
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).put("control_margin", "-0.01")},
				{"records[1].control_margin", (Edit) root ->
					((ObjectNode) root.get("records").get(1)).put("control_margin", "1")},
				{"records[0].scope", (Edit) root -> ((ObjectNode) root.get("records").get(0)).remove("scope")},
				{"records[1].cells", (Edit) root -> ((ObjectNode) root.get("records").get(1)).put("cells", "none")},
				{"records[1]: must be an object", (Edit) root -> ((ArrayNode) root.get("records")).set(1, "text")},
				{"records: is missing", (Edit) root -> root.remove("records")},
				{"records: must be an array", (Edit) root -> root.put("records", "none")},
				{"format_version is 2", (Edit) root -> root.put("format_version", 2)}
			};
	}

	/**
	 * Format-one certified entries that omit their margin keep the fixed claim on read, and reading leaves the file
	 * bytes unchanged. Explicit margins remain as written.
	 *
	 * @throws IOException if the history file cannot be written or read
	 */
	@Test
	public void formatOneImplicitMarginIsFixed() throws IOException
	{
		// Requirement: R-M6
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		CertificationHistory history = CertificationHistory.EMPTY.appended(record(1, "claude")).
			appended(record(2, "codex")).appended(record(3, "claude"));
		ObjectNode root = (ObjectNode) JSON.readTree(codec.write(history));
		((ObjectNode) root.get("records").get(0)).remove("control_margin");
		((ObjectNode) root.get("records").get(1)).remove("control_margin");
		((ObjectNode) root.get("records").get(1)).remove("label");
		ObjectNode explicitEntry = (ObjectNode) root.get("records").get(2);
		explicitEntry.put("minimum_pass_rate", "0.90");
		explicitEntry.put("control_margin", "0.04");
		explicitEntry.put("comparisons_implied", false);
		JsonNode entries = root.get("records");
		requireThat(entries.get(0).has("control_margin"), "firstMarginPresent").isFalse();
		requireThat(entries.get(1).has("control_margin"), "secondMarginPresent").isFalse();
		requireThat(entries.get(1).has("label"), "secondLabelPresent").isFalse();
		byte[] original = JSON.writeValueAsBytes(root);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, original);
			CertificationHistory read = codec.read(file);

			requireThat(read.records().get(0).parameters().controlMargin(), "labelledImplicitMargin").
				isEqualTo(new BigDecimal("0.05"));
			requireThat(read.records().get(1).parameters().controlMargin(), "unlabelledImplicitMargin").
				isEqualTo(new BigDecimal("0.05"));
			requireThat(read.records().get(2).parameters().controlMargin(), "explicitMargin").
				isEqualTo(new BigDecimal("0.04"));
			requireThat(Files.readAllBytes(file), "unchangedBytes").isEqualTo(original);
		}
	}

	/**
	 * Appending a new claim preserves the earlier explicit and implicit margins and leaves delivery entries without a
	 * certification margin.
	 *
	 * @throws IOException if the history file cannot be written or read
	 */
	@Test
	public void appendPreservesMarginClaims() throws IOException
	{
		// Requirement: R-M6
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		CertificationRecord implicit = record(1, "claude");
		CertificationRecord explicit = record(2, "codex");
		DeliveryEntry delivery = deliveryEntry(DeliveryLabel.NOT_CERTIFIED, "claude");
		ObjectNode before = (ObjectNode) JSON.readTree(codec.write(CertificationHistory.EMPTY.appended(implicit).
			appended(explicit).appended(delivery)));
		((ObjectNode) before.get("records").get(0)).remove("control_margin");
		ObjectNode explicitEntry = (ObjectNode) before.get("records").get(1);
		explicitEntry.put("minimum_pass_rate", "0.90");
		explicitEntry.put("control_margin", "0.04");
		explicitEntry.put("comparisons_implied", false);
		byte[] initial = JSON.writeValueAsBytes(before);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, initial);
			CertificationHistory loaded = codec.read(file);
			CertificationHistory appended = loaded.appended(record(3, "claude"));
			Files.write(file, codec.write(appended));
			JsonNode after = JSON.readTree(Files.readAllBytes(file)).get("records");
			JsonNode beforeEntries = before.get("records");
			ObjectNode expectedImplicit = (ObjectNode) beforeEntries.get(0).deepCopy();
			expectedImplicit.put("control_margin", "0.05");

			requireThat(after.size(), "entryCount").isEqualTo(4);
			requireThat(after.get(0).get("control_margin").asString(), "implicitMargin").isEqualTo("0.05");
			requireThat(after.get(1).get("control_margin").asString(), "explicitMargin").isEqualTo("0.04");
			requireThat(after.get(2).has("control_margin"), "deliveryMarginPresent").isFalse();
			requireThat(after.get(3).get("control_margin").asString(), "appendedMargin").isEqualTo("0.05");
			requireThat(after.get(0), "implicitEntry").isEqualTo(expectedImplicit);
			requireThat(after.get(1), "explicitEntry").isEqualTo(beforeEntries.get(1));
			requireThat(after.get(2), "deliveryEntry").isEqualTo(beforeEntries.get(2));
			requireThat(codec.read(file).entries(), "reloadedEntries").isEqualTo(appended.entries());
		}
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
	 * A history file that an earlier version of CAT wrote, whose records carry no label, still loads: each record
	 * reads back as a certification record, and the history's entries are those records.
	 *
	 * @throws IOException if the file cannot be written or read
	 */
	@Test
	public void readsHistoryWithoutLabels() throws IOException
	{
		CertificationRecord first = record(1, "claude");
		CertificationRecord second = record(2, "codex");
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		ObjectNode root = (ObjectNode) JSON.readTree(codec.write(CertificationHistory.EMPTY.appended(first).
			appended(second)));
		for (JsonNode element : root.get("records"))
			((ObjectNode) element).remove("label");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
			Files.write(file, JSON.writeValueAsBytes(root));

			CertificationHistory read = codec.read(file);
			requireThat(read.entries(), "entries").isEqualTo(List.of(first, second));
			requireThat(read.records(), "records").isEqualTo(List.of(first, second));
		}
	}

	/**
	 * Each label of a delivery that was not certified round-trips with its claim, incumbent hash, prompt files and
	 * conditions, after an earlier certification. The delivery entry is the harness's latest entry, the certification
	 * stays the harness's latest certification, and only the certification is among the history's records.
	 *
	 * @throws IOException if the file cannot be written or read
	 */
	@Test
	public void deliveryEntriesRoundTrip() throws IOException
	{
		CertificationRecord certified = record(1, "claude");
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		for (DeliveryLabel label : List.of(DeliveryLabel.IMPROVED_NOT_CERTIFIED, DeliveryLabel.NOT_CERTIFIED,
			DeliveryLabel.INCUMBENT_KEPT))
		{
			DeliveryEntry entry = deliveryEntry(label, "claude");
			CertificationHistory history = CertificationHistory.EMPTY.appended(certified).appended(entry);
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path file = cleanup.add(Files.createTempDirectory("cat-history-")).resolve("certifications.json");
				Files.write(file, codec.write(history));
				JsonNode written = JSON.readTree(Files.readAllBytes(file)).get("records");
				requireThat(written.get(0).get("label").asString(), "certifiedLabel").isEqualTo("certified");
				requireThat(written.get(1).get("label").asString(), "label").withContext(label, "deliveryLabel").
					isEqualTo(label.label());

				CertificationHistory read = codec.read(file);
				requireThat(read, "read").withContext(label, "deliveryLabel").isEqualTo(history);
				requireThat(read.latest(), "latest").isEqualTo(List.of(entry));
				requireThat(read.latestCertification(Optional.of("claude")), "latestCertification").
					isEqualTo(Optional.of(certified));
				requireThat(read.records(), "records").isEqualTo(List.of(certified));
			}
		}
	}

	/**
	 * Returns the entry of a delivery of {@code CLAUDE.md} that was not certified, measured by a harness.
	 *
	 * @param label the delivery's label
	 * @param harness the model provider that the entry names
	 * @return the entry
	 */
	private static DeliveryEntry deliveryEntry(DeliveryLabel label, String harness)
	{
		int attempt = 0;
		List<String> provenBetter = List.of();
		if (label == DeliveryLabel.IMPROVED_NOT_CERTIFIED)
		{
			attempt = 2;
			provenBetter = List.of("formatting");
		}
		return new DeliveryEntry(TARGET, label, attempt, provenBetter, Sha256.of("incumbent".getBytes(UTF_8)),
			Map.of(TARGET, Sha256.of("prompt delivered".getBytes(UTF_8))), new MeasurementIdentity(
			Map.of(MeasurementCondition.PROVIDER, harness, MeasurementCondition.MODEL, "model-a")));
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
	 * Returns one attempt's certification with evidence from two evaluators and an unused inventory cell.
	 *
	 * @return the certification record
	 */
	private static CertificationRecord jointRecord()
	{
		List<CellResult> cells = List.of(new CellResult("test", "claude:test", 100, 0, Map.of(), Map.of()),
			new CellResult("test", "codex:test", 100, 0, Map.of(), Map.of()));
		MeasurementIdentity identity = new MeasurementIdentity(Map.of(), Map.of(
			"claude:test", Map.of(MeasurementCondition.PROVIDER, "claude", MeasurementCondition.HARNESS_VERSION,
				"claude-v1", MeasurementCondition.LOADER_CONFIGURATION, "claude-loader"),
			"codex:test", Map.of(MeasurementCondition.PROVIDER, "codex", MeasurementCondition.HARNESS_VERSION,
				"codex-v1", MeasurementCondition.LOADER_CONFIGURATION, "codex-loader"),
			"unused:test", Map.of(MeasurementCondition.PROVIDER, "unused")));
		return new CertificationRecord(TARGET, 2, CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(2, Contract.DEFAULT_MINIMUM_PASS_RATE), cells,
			Map.of(TARGET, Sha256.of("joint prompt".getBytes(UTF_8))), identity);
	}

	/**
	 * Returns a record of an empty scope with the given measurement conditions.
	 *
	 * @param index    the certification index
	 * @param identity the measurement conditions
	 * @return the record
	 */
	private static CertificationRecord recordOf(int index, MeasurementIdentity identity)
	{
		return new CertificationRecord(TARGET, index, CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(index, Contract.DEFAULT_MINIMUM_PASS_RATE), List.of(),
			Map.of(TARGET, Sha256.of(("prompt " + index).getBytes(UTF_8))), identity);
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
