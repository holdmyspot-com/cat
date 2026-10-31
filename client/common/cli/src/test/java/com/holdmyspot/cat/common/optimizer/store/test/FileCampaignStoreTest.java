package com.holdmyspot.cat.common.optimizer.store.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.test.SessionHarnesses;

import com.holdmyspot.cat.common.optimizer.evidence.CommandSummary;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.campaign.OptimizationEvidence;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.Promotion;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionRun;
import com.holdmyspot.cat.common.optimizer.campaign.PromptSize;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.TrialInputs;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.report.CellReport;
import com.holdmyspot.cat.common.optimizer.report.OptimizationReport;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;
import com.holdmyspot.cat.common.optimizer.report.StatusReport;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;
import com.holdmyspot.cat.common.optimizer.store.DeliveryJournal;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import com.holdmyspot.cat.common.optimizer.store.WriteStep;
import java.nio.file.attribute.PosixFilePermissions;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.store.TrialSession;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyPolicy;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyWindows;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the {@link CampaignStore} contract against {@link FileCampaignStore}, and tests its file layout and crash
 * consistency.
 */
public final class FileCampaignStoreTest extends CampaignStoreContract
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String ID = "campaign-1";
	private static final String SESSION_ID = "6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a";
	/**
	 * The number of write steps that one atomic file replacement performs.
	 */
	private static final int STEPS_PER_WRITE = WriteStep.values().length;
	/**
	 * The write step, counted from 1 across {@link #runTrial}, that renames the outcome over the trial record.
	 */
	private static final int OUTCOME_RENAME = 3 * STEPS_PER_WRITE + WriteStep.RENAME.ordinal() + 1;

	/**
	 * Creates the test.
	 */
	public FileCampaignStoreTest()
	{
	}

	/**
	 * Stored identities without session observations report comparisons and size measurements.
	 *
	 * @throws IOException if the fixture cannot be stored or loaded
	 */
	// Requirement: R-M4, R-P1, R-E18
	@Test
	public void sparseIdentityReportsComparisons() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("sparse-status"));
			Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
			for (MeasurementCondition condition : List.of(MeasurementCondition.JUDGE, MeasurementCondition.MODEL,
				MeasurementCondition.REASONING))
				values.remove(condition);
			MeasurementIdentity sparse = new MeasurementIdentity(values);
			OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(
				sparse, Map.of(Builder.snapshot("candidate-1"), Map.of("safety", new TrialInputs(2, List.of(30L, 50L))),
					Builder.INCUMBENT, Map.of("safety", new TrialInputs(2, List.of(70L, 90L))))));
			Campaign campaign = new Builder().confirmed().working(1).identity(sparse).readyBaseline().
				development("safety", 2, 0).optimization(evidence).build();
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(campaign);
			JsonNode fixture = JSON.readTree(Files.readString(heldOutDirectory(campaigns).resolve("campaign.json"), UTF_8));
			requireThat(fixture.path("identity").path("cells").size(), "cells").isEqualTo(0);
			requireThat(fixture.path("identity").path("conditions").has("judge"), "judge").isFalse();
			requireThat(fixture.path("baseline").path("identity"), "baselineIdentity").isEqualTo(fixture.path("identity"));

			StatusReport report = StatusReport.of(new FileCampaignStore(campaigns, JSON,
				Yielding.fileReplacer()).load(ID).orElseThrow(),
				ReportEvidence.NONE);
			CellReport cell = report.cells().stream().filter(item -> item.cell().equals("safety")).
				findFirst().orElseThrow();
			requireThat(cell.improvement().unavailable(), "improvement").isNull();
			OptimizationReport.InputComparison inputs = report.optimization().inputs().stream().
				filter(item -> item.cell().equals("safety")).
				findFirst().orElseThrow();
			requireThat(inputs.workingMedian(), "workingMedian").isEqualTo(30L);
			requireThat(inputs.incumbentMedian(), "incumbentMedian").isEqualTo(70L);
			requireThat(inputs.unavailable(), "unavailable").isNull();
			requireThat(report.summary(ZoneOffset.UTC).contains("INDETERMINATE"), "enumReason").isFalse();
			requireThat(report.toJson(JSON).path("trial_input_tokens").get(2).path("unavailable").isNull(),
				"jsonReason").isTrue();
		}
	}

	/**
	 * A campaign file written while CAT still recorded the trial model's provider loads without it: the provider is
	 * dropped from the identities and from the changed conditions of a pending decision, and a pending decision whose
	 * only change was the provider is dropped.
	 *
	 * @throws IOException if the fixture cannot be stored or loaded
	 */
	@Test
	public void loadsCampaignWithRetiredModelProvider() throws IOException
	{
		// Requirement: R-M7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("retired-provider"));
			MeasurementIdentity observed = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "codex"),
				Map.of("safety", Map.of(MeasurementCondition.MODEL, "model-b")));
			Campaign campaign = new Builder().confirmed().working(1).readyBaseline().
				pending(new PendingDecision.MeasurementChanged(observed,
					List.of(MeasurementCondition.MODEL, MeasurementCondition.PROVIDER))).
				pending(new PendingDecision.MeasurementChanged(observed, List.of(MeasurementCondition.PROVIDER))).
				pending(new PendingDecision.UnownedChange("CLAUDE.md changed outside CAT")).build();
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(campaign);
			Path file = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(file, UTF_8));
			((ObjectNode) root.get("identity").get("conditions")).put("model_provider", "openai");
			((ObjectNode) root.get("baseline").get("identity").get("conditions")).put("model_provider", "openai");
			JsonNode decisions = root.get("pending_decisions");
			((ObjectNode) decisions.get(0).get("observed").get("cells").get("safety")).put("model_provider", "openai");
			((ArrayNode) decisions.get(0).get("changed")).add("model_provider");
			((ArrayNode) decisions.get(1).get("changed")).removeAll().add("model_provider");
			Files.writeString(file, JSON.writeValueAsString(root), UTF_8);

			Campaign loaded = store.load(ID).orElseThrow();

			requireThat(loaded.identity(), "identity").isEqualTo(campaign.identity());
			requireThat(loaded.baseline().identity(), "baselineIdentity").isEqualTo(campaign.baseline().identity());
			requireThat(loaded.pendingDecisions(), "pendingDecisions").isEqualTo(List.of(
				new PendingDecision.MeasurementChanged(observed,
					List.of(MeasurementCondition.MODEL, MeasurementCondition.PROVIDER)),
				new PendingDecision.UnownedChange("CLAUDE.md changed outside CAT")));
		}
	}

	/**
	 * Verifies that a campaign stored while the profile inventories listed a model without reasoning efforts with an
	 * empty effort loads that effort as {@code none}: every inventory profile, cell and judge profile then names an
	 * effort, so no session of the campaign starts under the harness's default effort.
	 *
	 * @throws IOException if the fixture cannot be stored or loaded
	 */
	@Test
	public void loadsEmptyStoredEffortAsNone() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("empty-effort"));
			Campaign campaign = new Builder().confirmed().working(1).build();
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(campaign);
			Path file = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(file, UTF_8));
			JsonNode contract = root.get("lineages").get(0).get("contract");
			JsonNode profiles = contract.get("trial_profiles");
			for (JsonNode profile : profiles.get("inventory"))
				((ObjectNode) profile).put("effort", "");
			for (JsonNode cell : profiles.get("cells"))
				((ObjectNode) cell).put("effort", "");
			((ObjectNode) contract.get("judge")).put("effort", "");
			Files.writeString(file, JSON.writeValueAsString(root), UTF_8);

			Campaign loaded = store.load(ID).orElseThrow();

			TrialProfiles loadedProfiles = loaded.contract().profiles();
			requireThat(loadedProfiles.inventory().profiles().stream().map(TrialProfile::effort).toList(),
				"inventoryEfforts").isNotEmpty().containsAll(List.of("none")).doesNotContain("");
			requireThat(loadedProfiles.cells().stream().map(cell -> cell.profile().effort()).toList(),
				"cellEfforts").isNotEmpty().containsAll(List.of("none")).doesNotContain("");
			requireThat(loaded.contract().judge().profile().effort(), "judgeEffort").isEqualTo("none");
		}
	}

	/**
	 * Verifies that a campaign written while CAT recorded the host tools and outside sources that trials used loads
	 * without them: reading drops them from every identity and from a pending measurement change, and drops a pending
	 * measurement change that names nothing else.
	 *
	 * @throws IOException if the fixture cannot be stored or loaded
	 */
	@Test
	public void loadsCampaignWithRetiredToolsAndSources() throws IOException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("retired-tools"));
			MeasurementIdentity observed = new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "codex"),
				Map.of("safety", Map.of(MeasurementCondition.MODEL, "model-b")));
			Campaign campaign = new Builder().confirmed().working(1).readyBaseline().
				pending(new PendingDecision.MeasurementChanged(observed, List.of(MeasurementCondition.MODEL))).
				pending(new PendingDecision.MeasurementChanged(observed, List.of(MeasurementCondition.PROVIDER))).
				pending(new PendingDecision.UnownedChange("CLAUDE.md changed outside CAT")).build();
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(campaign);
			Path file = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(file, UTF_8));
			for (String retired : List.of("host_tools", "external_sources"))
			{
				((ObjectNode) root.get("identity").get("conditions")).put(retired, "none");
				((ObjectNode) root.get("baseline").get("identity").get("conditions")).put(retired, "none");
			}
			JsonNode decisions = root.get("pending_decisions");
			((ObjectNode) decisions.get(0).get("observed").get("conditions")).put("host_tools", "git 2.51.0");
			((ArrayNode) decisions.get(0).get("changed")).add("host_tools");
			((ArrayNode) decisions.get(1).get("changed")).removeAll().add("host_tools").add("external_sources");
			Files.writeString(file, JSON.writeValueAsString(root), UTF_8);

			Campaign loaded = store.load(ID).orElseThrow();

			requireThat(loaded.identity(), "identity").isEqualTo(campaign.identity());
			requireThat(loaded.baseline().identity(), "baselineIdentity").isEqualTo(campaign.baseline().identity());
			requireThat(loaded.pendingDecisions(), "pendingDecisions").isEqualTo(List.of(
				new PendingDecision.MeasurementChanged(observed, List.of(MeasurementCondition.MODEL)),
				new PendingDecision.UnownedChange("CLAUDE.md changed outside CAT")));
		}
	}

	/**
	 * Verifies that the held-out directories are each campaign's {@code held_out} directory, which holds the snapshot,
	 * the proposal and the trial records, that it is listed before any record is written, so that a session that
	 * starts first is denied the records written while it runs, and that the campaign's other entries stay outside it.
	 *
	 * @throws IOException if the fixture cannot be stored or listed
	 */
	@Test
	public void listsHeldOutDirectories() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("held-out-paths"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().build());
			store.writeJournal(ID, journal("candidate-2", 0));
			Path heldOut = heldOutDirectory(campaigns).toRealPath();

			List<Path> directories = FileCampaignStore.heldOutDirectories(campaigns);

			requireThat(directories, "directories").isEqualTo(List.of(heldOut));
			requireThat(FileCampaignStore.heldOutDirectory(campaigns, ID).toRealPath(), "heldOutDirectory").
				isEqualTo(heldOut);
			requireThat(list(heldOut), "heldOutFiles").
				isEqualTo(List.of(heldOut.resolve("campaign.json"), heldOut.resolve("trials")));
			requireThat(Files.exists(campaignDirectory(campaigns).resolve("delivery.json")), "journalOutside").isTrue();
		}
	}

	/**
	 * Verifies that a directory without campaigns has no held-out directories.
	 *
	 * @throws IOException if the fixture cannot be listed
	 */
	@Test
	public void listsNoHeldOutDirectories() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("held-out-paths"));

			requireThat(FileCampaignStore.heldOutDirectories(campaigns), "directories").isEmpty();
		}
	}

	/**
	 * Verifies that a campaign whose snapshot is stored directly in the campaign directory, outside its
	 * {@code held_out} directory, is refused with a message that names its version and asks for a new campaign, both
	 * when it is loaded and when campaigns are listed.
	 *
	 * @param listing {@code true} to list the campaigns, {@code false} to load the campaign
	 * @throws IOException if the store fails
	 */
	@Test(dataProvider = "listing", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 15 campaign.*[Ss]tart a new campaign.*")
	public void refusesCampaignOutsideHeldOutDirectory(boolean listing) throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-15-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path heldOut = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(heldOut, UTF_8));
			root.put("format_version", 15);
			Files.writeString(campaignDirectory(campaigns).resolve("campaign.json"), JSON.writeValueAsString(root), UTF_8);
			Files.delete(heldOut);

			FileCampaignStore restarted = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			if (listing)
				restarted.listHeaders();
			else
				restarted.load(ID);
		}
	}

	/**
	 * Verifies that a version 15 snapshot inside the {@code held_out} directory is refused with a message that names
	 * its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 15 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionFifteenCampaign() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-15-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 15);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 16 snapshot, which predates failure behaviors and reproduction rounds, is refused with a
	 * message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 16 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionSixteenCampaign() throws IOException
	{
		// Requirement: R-C19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-16-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 16);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 17 snapshot, which predates recorded certification parameters and the session
	 * environment version, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 17 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionSeventeenCampaign() throws IOException
	{
		// Requirement: R-C19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-17-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 17);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 18 snapshot, which has an {@code authorized_targets} array, is refused with a message that
	 * names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 18 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionEighteenCampaign() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-18-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 18);
			root.putArray("authorized_targets").add("/external/shared.md");
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 19 snapshot, whose regression test cases do not record whether a failing outcome was
	 * settled, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 19 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionNineteenCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-19-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 19);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 20 trial record, which holds only the token usage of each session, is refused with a
	 * message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	// Requirement: R-E6
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 20 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwentyTrialRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-20-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			store.writeAssignment(ID, assigned);
			Path record = list(heldOutDirectory(campaigns).resolve("trials")).getFirst();
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(record, UTF_8));
			root.put("format_version", 20);
			root.remove("sessions");
			root.putArray("token_usage").addObject().put("input", 100).put("cached_input", 20).put("output", 30).
				put("reasoning_output", 10);
			Files.writeString(record, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).readRecord(ID, assigned.key());
		}
	}

	/**
	 * Verifies that a version 21 snapshot, which does not record the warnings that the user answered by using an
	 * authorized source, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 21 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwentyOneCampaign() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-21-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 21);
			root.remove("answered_warnings");
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 22 snapshot, which can hold a paused development round that later versions no longer
	 * pause on, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 22 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwentyTwoCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-22-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 22);
			root.putArray("pending_decisions").addObject().put("type", "plateau_reached");
			root.put("outcome", "PLATEAU");
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 23 snapshot, whose development failures do not record where their trial's transcript and
	 * effects are, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 23 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwentyThreeCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-23-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 23);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 24 snapshot, whose prompt author diagnoses classify each failure rather than group the
	 * failures into kinds, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 24 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwentyFourCampaign() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-24-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 24);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Verifies that a version 23 trial record, whose outcome does not record where the trial's transcript and effects
	 * are, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 23 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwentyThreeTrialRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-23-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			store.writeAssignment(ID, assigned);
			Path record = list(heldOutDirectory(campaigns).resolve("trials")).getFirst();
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(record, UTF_8));
			root.put("format_version", 23);
			Files.writeString(record, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).readRecord(ID, assigned.key());
		}
	}

	/**
	 * Verifies that a trial plugin's manifest repository survives a reload, and that a plugin recorded before campaigns
	 * kept the repository, or with none in its manifest, loads with none and so is not CAT.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void storesPluginRepository() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-repository-"));
			TrialPlugin cat = new TrialPlugin("workflow@local", "a".repeat(64), new TrialPlugin.Installed(), List.of(),
				PluginInfo.CAT_REPOSITORY);
			TrialPlugin other = new TrialPlugin("tools@market", "b".repeat(64), new TrialPlugin.Installed(), List.of(),
				null);
			Campaign campaign = new Builder().header(header(ID)).confirmed().working(1).plugins(List.of(cat, other)).
				build();
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(campaign);

			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			requireThat(root.get("plugins").get(0).get("repository").asString(), "repository").
				isEqualTo(PluginInfo.CAT_REPOSITORY);
			List<TrialPlugin> loaded = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).
				orElseThrow().plugins().orElseThrow();
			requireThat(loaded, "loaded").isEqualTo(List.of(cat, other));

			((ObjectNode) root.get("plugins").get(0)).remove("repository");
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			TrialPlugin older = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).
				orElseThrow().plugins().orElseThrow().getFirst();
			requireThat(older.repository(), "olderRepository").isNull();
			requireThat(older.isCat(), "olderIsCat").isFalse();
		}
	}

	/**
	 * Verifies that a stored campaign records no external target as editable and survives a reload unchanged.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void storesNoEditableExternalTargets() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-external-"));
			Campaign campaign = populated(ID);
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(campaign);

			JsonNode snapshot = JSON.readTree(Files.readString(heldOutDirectory(campaigns).resolve("campaign.json"), UTF_8));
			requireThat(snapshot.has("authorized_targets"), "hasAuthorizedTargets").isFalse();
			requireThat(new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID),
				"loaded").isEqualTo(Optional.of(campaign));
		}
	}

	/**
	 * Returns the cases that choose whether a test lists campaigns or loads one.
	 *
	 * @return {@code true} to list, {@code false} to load
	 */
	@DataProvider
	public static Object[][] listing()
	{
		return new Object[][]{{true}, {false}};
	}

	/**
	 * A fresh store read retains the evidence needed to restore a promoted candidate's predecessor.
	 *
	 * @throws IOException if the store fails
	 */
	// Requirement: R-D15
	@Test
	public void predecessorSurvivesStoreRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-predecessor"));
			Campaign promoted = promotedForRevert(false);
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(promoted);
			Campaign reloaded = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow();
			requireThat(reloaded, "reloaded").isEqualTo(promoted);

			Campaign reverted = failDevelopment(reloaded);
			requireThat(reverted.development().working().number(), "working").isEqualTo(1);
			requireThat(reverted.development().count("safety"), "counts").isEqualTo(Builder.counts(99, 1));
			requireThat(reverted.development().round(), "endedRound").isNull();
			requireThat(reverted.outcome(), "outcome").isNull();
			requireThat(reverted.lineage().regressionSet().getFirst().settled(), "settledByEndedRound").isTrue();
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(reverted);
			requireThat(new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow(),
				"reloadedRevert").isEqualTo(reverted);
		}
	}

	/**
	 * A fresh store read retains whether the predecessor's failing outcome on a regression test case was settled, so a
	 * revert restores it.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void settledPredecessorSurvivesRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-settled-predecessor"));
			Campaign promoted = promotedForRevert(true);
			requireThat(promoted.development().predecessor().regressionSet().getFirst().settled(), "settledPredecessor").
				isTrue();

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(promoted);
			requireThat(new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow(),
				"reloaded").isEqualTo(promoted);
		}
	}

	/**
	 * Returns a candidate promoted after the final attempt of a sequential round.
	 *
	 * @param settled {@code true} if an earlier round that ended without a promotable attempt settled the
	 *   predecessor's failing outcome on the round's triggering test case; {@code false} if that failure is not settled
	 * @return the promoted campaign
	 */
	private static Campaign promotedForRevert(boolean settled)
	{
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		DiagnosticRerun failed = new DiagnosticRerun(Verdict.FAIL, "The defect reproduced");
		Builder builder = new Builder().confirmed().working(1).development("safety", 99, 1).
			failure(new Failure(logs, "Removed the directory", List.of(), null, List.of(failed, failed, failed), List.of(),
				Builder.FILES)).
			round(new Round(Map.of("safety", 1)));
		if (settled)
			builder.settledRegression(logs);
		else
			builder.regression(logs, Verdict.FAIL);
		Campaign campaign = builder.build();
		Sha256 snapshot = Builder.snapshot("stored-promoted-revert-candidate");
		campaign = Transitions.apply(campaign, new CampaignEvent.StartPromptAuthorAttempt("safety"));
		campaign = Transitions.apply(campaign, new CampaignEvent.SubmitPromptAuthorAttempt(1, "safety", snapshot,
			List.of()));
		for (int run = 0; run < 2; ++run)
		{
			campaign = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.PROMOTION,
				1, snapshot, "safety", logs, new Allocation(AllocationRule.TARGETED, campaign.evidenceRevision(),
					Instant.parse("2026-10-01T12:00:00Z")))));
			TrialKey key = campaign.trials().getLast().key();
			campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
			campaign = Transitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.PASS, "Fixed", List.of(), null,
				campaign.identity(), null, List.of(), Builder.FILES));
		}
		return Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(snapshot));
	}

	/**
	 * Adopts a failing representative outcome for the working candidate.
	 *
	 * @param campaign the campaign
	 * @return the state after adoption
	 */
	private static Campaign failDevelopment(Campaign campaign)
	{
		campaign = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.DEVELOPMENT,
			campaign.development().series(), campaign.development().working().snapshot(), "safety", null,
			new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
				Instant.parse("2026-10-01T12:00:00Z")))));
		TrialKey key = campaign.trials().getLast().key();
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		return Transitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.FAIL, "Poor representative result",
			List.of(), null, campaign.identity(), null, List.of(), Builder.FILES));
	}

	@Override
	protected StoreFixture open() throws IOException
	{
		return new FileStoreFixture();
	}

	/**
	 * A store in a new temporary directory, which the fixture deletes when it closes.
	 */
	private static final class FileStoreFixture implements StoreFixture
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path campaigns;
		private final FileCampaignStore store;

		/**
		 * Creates the directory and the store.
		 *
		 * @throws IOException if the directory cannot be created
		 */
		FileStoreFixture() throws IOException
		{
			campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-store-"));
			store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
		}

		@Override
		public CampaignStore store()
		{
			return store;
		}

		@Override
		public CampaignStore reopen()
		{
			return new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}

	/**
	 * Verifies that an unfinished reduction retains its original bytes and last passing evidence on disk.
	 *
	 * @throws IOException if the fixture cannot be read or written
	 */
	@Test
	public void preservesMinimalityAcrossReload() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("campaign")), JSON,
				Yielding.fileReplacer());
			FileDelta file = new FileDelta(Path.of("CLAUDE.md"), new FileContent("first\nseparator\nlast\n".
				getBytes(UTF_8)), new FileContent("needed\nseparator\nunneeded\n".getBytes(UTF_8)),
				PosixFilePermissions.fromString("rw-r--r--"));
			TrialKey key = new TrialKey(Purpose.PROMOTION, 1, Builder.snapshot("prompt_author"), "safety", 1);
			PromotionRun passed = new PromotionRun(PromotionRun.Role.TRIGGERING,
				Builder.targeted("safety", "cache"), key, Verdict.PASS);
			Minimality search = Minimality.start(List.of(file), key.snapshot()).advance(key.snapshot(), List.of(passed),
				Promotion.Decision.PROMOTE);
			Campaign campaign = new Builder().confirmed().working(1).
				round(new Round(Map.of("safety", 0))).promptAuthor(new PromptAuthorAttempt(1, "safety",
					Builder.snapshot("reduction"), List.of(), search)).build();
			store.replaceSnapshot(campaign);
			Campaign loaded = store.load(campaign.header().id()).orElseThrow();
			requireThat(loaded.development().promptAuthor().minimality(), "search").isEqualTo(search);
			requireThat(loaded.development().promptAuthor().minimality().files().getFirst().before(), "workingBytes").
				isEqualTo(file.before());
		}
	}

	/**
	 * Verifies that pending prompt author reservations, received contenders and zero-run rejected evidence survive
	 * reload.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void preservesConcurrentAttemptProgress() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("campaign-batch")), JSON,
				Yielding.fileReplacer());
			Campaign campaign = new Builder().confirmed().working(1).
				failure(Builder.targeted("safety", "cache")).
				round(new Round(Map.of("safety", 0), true, Map.of(), List.of())).build();
			for (int attempt = 0; attempt < 3; ++attempt)
				campaign = Transitions.apply(campaign, new CampaignEvent.StartPromptAuthorAttempt("safety"));
			campaign = Transitions.apply(campaign, new CampaignEvent.SubmitPromptAuthorAttempt(3, "safety",
				Builder.snapshot("contender"), List.of()));
			campaign = Transitions.apply(campaign, new CampaignEvent.SubmitPromptAuthorAttempt(2, "safety",
				campaign.development().working().snapshot(), List.of()));
			store.replaceSnapshot(campaign);
			Campaign loaded = store.load(campaign.header().id()).orElseThrow();
			requireThat(loaded.development().round(), "round").isEqualTo(campaign.development().round());
			requireThat(loaded.development().round().pendingPromptAuthorAttempts(), "pendingPromptAuthor").
				isEqualTo(Map.of(1, "safety"));
			requireThat(loaded.optimization(), "retainedEvidence").isEqualTo(campaign.optimization());
			requireThat(loaded.optimization().attempts().getFirst().accepted(), "noEditRejected").isFalse();
			requireThat(loaded.development().counts(), "counts").isEmpty();
			requireThat(loaded.attempts(), "allowance").isEmpty();
		}
	}

	/**
	 * Verifies that descriptive prompt sizes, input observations and command counts survive a store reload.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void preservesOptimizationMeasurements() throws IOException
	{
		// Requirement: R-E18
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("measurements")), JSON,
				Yielding.fileReplacer());
			OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(),
				Map.of(Builder.INCUMBENT, new PromptSize(4L, 1L, null)),
				Map.of(Builder.confirmedIdentity("model-a"),
					Map.of(Builder.INCUMBENT, Map.of("safety", new TrialInputs(3, List.of(0L, 10L)))))).
				addCommands(new TrialKey(Purpose.FORMAL, 2, Builder.INCUMBENT, "safety", 1),
					new CommandSummary(Duration.ofSeconds(11), null)).
				addCommands(new TrialKey(Purpose.DEVELOPMENT, 1, Builder.INCUMBENT, "safety", 1),
					new CommandSummary(Duration.ofSeconds(1), 0));
			Campaign campaign = new Builder().confirmed().optimization(evidence).build();
			store.replaceSnapshot(campaign);
			requireThat(store.load(campaign.header().id()).orElseThrow().optimization(), "measurements").
				isEqualTo(evidence);
		}
	}

	/**
	 * Verifies that reload preserves the identities of baseline counts, frozen bounds, and separate token samples.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void preservesComparisonIdentities() throws IOException
	{
		// Requirement: R-M4, R-L5
		MeasurementIdentity earlier = Builder.confirmedIdentity("model-a");
		MeasurementIdentity current = Builder.confirmedIdentity("model-b");
		OptimizationEvidence evidence = OptimizationEvidence.EMPTY.
			addTrial(earlier, Builder.INCUMBENT, "safety", 10L).
			addTrial(current, Builder.INCUMBENT, "safety", 100L);
		Campaign campaign = new Builder().confirmed().identity(current).baselineIdentity(earlier).
			readyBaseline().optimization(evidence).certificationAttempt(1).baselineIdentity(earlier).
			cell("safety", 100, 0).build();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("comparison-identities")),
				JSON, Yielding.fileReplacer());
			store.replaceSnapshot(campaign);
			Campaign loaded = store.load(campaign.header().id()).orElseThrow();
			requireThat(loaded.baseline().identity(), "baselineIdentity").isEqualTo(earlier);
			requireThat(loaded.attempt(1).procedure().baselineIdentity(), "frozenBaselineIdentity").isEqualTo(earlier);
			requireThat(loaded.identity(), "currentIdentity").isEqualTo(current);
			requireThat(loaded.optimization(), "tokenEvidence").isEqualTo(evidence);
		}
	}

	/**
	 * Verifies that a crash after a token-bearing outcome is written preserves usage and adopts it once.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void recoversInputUsageWithOutcome() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("input-usage"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign campaign = start();
			Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
				Purpose.DEVELOPMENT, campaign.development().series(), campaign.development().working().snapshot(),
				"safety", null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
					Instant.parse("2026-10-01T12:00:00Z")))));
			LogicalTrial trial = assigned.trials().getLast();
			store.writeAssignment(ID, trial);
			Campaign running = Transitions.apply(assigned, new CampaignEvent.Start(trial.key()));
			store.replaceSnapshot(running);
			store.writeOutcome(ID, new CampaignEvent.Complete(trial.key(), Verdict.PASS, "", List.of(), null,
				running.identity(), 0L, List.of(), Builder.FILES));
			reconcile(new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()));
			Campaign recovered = store.load(ID).orElseThrow();
			TrialInputs inputs = recovered.optimization().trialInputs().get(recovered.identity()).
				get(trial.key().snapshot()).get("safety");
			requireThat(inputs, "inputs").isEqualTo(new TrialInputs(1, List.of(0L)));
			reconcile(new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()));
			requireThat(store.load(ID).orElseThrow().optimization().trialInputs().get(recovered.identity()).
				get(trial.key().snapshot()).get("safety"), "inputs").isEqualTo(inputs);
		}
	}

	/**
	 * A crash after any write step of a delivery journal update leaves the earlier progress until the new journal is
	 * renamed into place, and the new progress afterwards; the journal file carries the format version.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void journalWriteFaultKeepsOldOrNew() throws IOException
	{
		// Requirement: R-W8
		for (WriteStep crashStep : WriteStep.values())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-journal-"));
				DeliveryJournal started = journal("candidate-2", 0);
				new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).writeJournal(ID, started);
				FileCampaignStore crashing = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer(), step ->
				{
					if (step == crashStep)
						throw new SimulatedCrash(step);
				});
				boolean crashed = false;
				try
				{
					crashing.writeJournal(ID, started.withApplied(1));
				}
				catch (SimulatedCrash _)
				{
					crashed = true;
				}
				requireThat(crashed, "crashed").withContext(crashStep, "crashStep").isTrue();

				DeliveryJournal expected;
				if (crashStep.compareTo(WriteStep.RENAME) >= 0)
					expected = started.withApplied(1);
				else
					expected = started;
				requireThat(new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).loadJournal(ID), "loaded").
					withContext(crashStep, "crashStep").isEqualTo(Optional.of(expected));
				JsonNode document = JSON.readTree(Files.readString(campaignDirectory(campaigns).resolve("delivery.json"),
					UTF_8));
				requireThat(document.get("format_version").asInt(), "formatVersion").isEqualTo(1);
				List<String> kinds = new ArrayList<>();
				for (JsonNode change : document.get("changes"))
					kinds.add(change.get("kind").asString());
				requireThat(kinds, "kinds").isEqualTo(List.of("delete", "edit", "create", "edit", "write_link", "write_link",
					"replace_link_with_directory"));
			}
		}
	}

	/**
	 * Returns every write step at which the trial lifecycle can crash.
	 *
	 * @return each crash position, counted from one
	 * @throws IOException if the lifecycle cannot be measured
	 */
	@DataProvider(name = "trialWriteSteps")
	public static Object[][] trialWriteSteps() throws IOException
	{
		int steps = countTrialSteps();
		requireThat(steps, "steps").isEqualTo(5 * STEPS_PER_WRITE);
		Object[][] positions = new Object[steps][1];
		for (int index = 0; index < steps; ++index)
			positions[index][0] = index + 1;
		return positions;
	}

	/**
	 * A crash after any write step of a trial's lifecycle, followed by a restart and reconciliation, leaves exactly one
	 * adopted outcome: the recorded failure if its record was renamed into place, otherwise the rerun's pass.
	 *
	 * @param crashAfter the write step after which the process crashes, counted from one
	 * @throws IOException if the store fails
	 */
	@Test(dataProvider = "trialWriteSteps")
	public void faultAfterEveryWriteStepLosesNoOutcome(int crashAfter) throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-fault-"));
			Campaign start = start();
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(start);
			AtomicInteger performed = new AtomicInteger();
			FileCampaignStore crashing = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer(), step ->
			{
				if (performed.incrementAndGet() == crashAfter)
					throw new SimulatedCrash(step);
			});
			boolean crashed = false;
			try
			{
				runTrial(crashing, start, Verdict.FAIL);
			}
			catch (SimulatedCrash _)
			{
				crashed = true;
			}
			requireThat(crashed, "crashed").withContext(crashAfter, "crashAfter").isTrue();

			FileCampaignStore restarted = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			reconcile(restarted);

			Campaign end = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow();
			CellCounts expected;
			if (crashAfter >= OUTCOME_RENAME)
				expected = Builder.counts(0, 1);
			else
				expected = Builder.counts(1, 0);
			requireThat(end.development().count("safety"), "counts").withContext(crashAfter, "crashAfter").
				isEqualTo(expected);
			requireThat(end.trials(), "unadopted").withContext(crashAfter, "crashAfter").isEmpty();
			requireThat(restarted.listUnadoptedOutcomes(end), "outcomes").withContext(crashAfter, "crashAfter").
				isEmpty();
		}
	}

	/**
	 * Each atomic replacement reports its steps to the hook in order.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void reportsWriteStepsInOrder() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-steps-"));
			List<WriteStep> steps = new ArrayList<>();
			Consumer<WriteStep> hook = steps::add;
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer(), hook).replaceSnapshot(start());

			requireThat(steps, "steps").isEqualTo(List.of(WriteStep.values()));
		}
	}

	/**
	 * Loading reads only {@code campaign.json}: replacing every trial record with an unreadable file does not affect
	 * it.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void loadingIgnoresUnreadableTrialRecords() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-unreadable-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign campaign = populated(ID);
			store.replaceSnapshot(campaign);
			for (LogicalTrial trial : campaign.trials())
				store.writeAssignment(ID, asAssigned(trial));
			store.writeOutcome(ID, complete(campaign.trials().getFirst().key(), Verdict.PASS));

			List<Path> records = list(heldOutDirectory(campaigns).resolve("trials"));
			requireThat(records.size(), "records").isEqualTo(campaign.trials().size());
			for (Path record : records)
			{
				Files.writeString(record, "{ not json", UTF_8);
				denyReading(record);
			}

			FileCampaignStore restarted = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			requireThat(restarted.load(ID), "loaded").isEqualTo(Optional.of(campaign));
			// Reconciliation does read the records, which shows that they are unreadable
			boolean unreadable = false;
			try
			{
				restarted.listUnadoptedOutcomes(campaign);
			}
			catch (IOException _)
			{
				unreadable = true;
			}
			requireThat(unreadable, "unreadable").isTrue();
		}
	}

	/**
	 * A campaign is stored in the CAT session directory of its session, as
	 * {@code .cat/sessions/<session-id>/optimizer/<id>/held_out/campaign.json} with a format version, and each logical
	 * trial as one record file in the same {@code held_out} directory.
	 *
	 * @throws IOException if the store fails
	 */
	// Requirement: R-D15
	@Test
	public void storesSnapshotAndOneRecordPerTrial() throws IOException
	{
		// Requirement: R-E24
		// Requirement: R-W1
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-campaign-layout-"));
			SessionDirectory session = SessionDirectory.open(project, SESSION_ID, SessionHarnesses.HARNESS_A,
				project.resolve("configuration"), Instant.parse("2026-10-16T12:00:00Z"), JSON);
			Path campaigns = session.campaigns();
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			LogicalTrial first = trial(1, TrialState.ASSIGNED);
			LogicalTrial second = trial(2, TrialState.ASSIGNED);
			store.writeAssignment(ID, first);
			store.writeAssignment(ID, second);
			store.writeOutcome(ID, complete(first.key(), Verdict.PASS));
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().working(1).trial(first).
				trial(second).build());

			Path directory = heldOutDirectory(campaigns);
			requireThat(directory, "directory").isEqualTo(project.toAbsolutePath().normalize().
				resolve(".cat/sessions/" + SESSION_ID + "/optimizer/" + ID + "/held_out"));
			requireThat(list(campaignDirectory(campaigns)), "campaignFiles").isEqualTo(List.of(directory));
			requireThat(list(directory), "heldOutFiles").
				isEqualTo(List.of(directory.resolve("campaign.json"), directory.resolve("trials")));
			List<Path> records = list(directory.resolve("trials"));
			requireThat(records.size(), "records").isEqualTo(2);
			for (Path record : records)
			{
				requireThat(record.getFileName().toString(), "record").endsWith(".json");
				requireThat(JSON.readTree(Files.readString(record, UTF_8)).get("format_version").asInt(),
					"recordVersion").isEqualTo(46);
			}
			JsonNode snapshot = JSON.readTree(Files.readString(directory.resolve("campaign.json"), UTF_8));
			requireThat(snapshot.get("format_version").asInt(), "snapshotVersion").isEqualTo(46);
		}
	}

	/**
	 * Verifies that a version-45 snapshot whose throttle holds the window cap at 1 without a rejection loads with the
	 * campaign's ceiling as the level to regrow to, so a window that passes can grow again while utilization stays at
	 * 80% or more; that one whose throttle counts a rejection's backoffs keeps the window at 1; that a version-45
	 * trial session's throttle keeps its window cap as its peak window; and that a session recorded before sessions
	 * recorded how their commands ran, like a snapshot that counted none, loads with them unknown.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void version45ThrottleRegrowsAfterUpgrade() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-45-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Instant expired = Instant.parse("2026-10-28T10:22:05Z");
			Campaign original = new Builder().header(header(ID)).confirmed().working(1).
				development("formatting", 200, 0).development("safety", 160, 1).window(16).
				ceiling(8).throttle(new Throttle(expired, 1, 0, 1)).build();
			LogicalTrial trial = trial(1, TrialState.ASSIGNED);
			store.writeAssignment(ID, trial);
			store.writeTrialSession(ID, trial.key(), new TrialSession(expired, expired.plusSeconds(40), 8,
				new Throttle(expired, 1, 0, 1), List.of(new RateLimitWindow(0.80, null)), HostLogin.API_KEY, null,
				new CommandSummary(Duration.ofSeconds(3), 0)));
			store.replaceSnapshot(original);

			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 45);
			((ObjectNode) root.get("throttle")).remove("peak_window");
			((ObjectNode) root.get("optimization")).remove("commands");
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			try (Stream<Path> files = Files.list(heldOutDirectory(campaigns).resolve("trials")))
			{
				Path record = files.findFirst().orElseThrow();
				ObjectNode recordRoot = (ObjectNode) JSON.readTree(Files.readString(record, UTF_8));
				recordRoot.put("format_version", 45);
				for (JsonNode session : recordRoot.get("sessions"))
				{
					((ObjectNode) session.get("throttle")).remove("peak_window");
					((ObjectNode) session).remove(List.of("slowest_command_ms", "empty_running_results"));
				}
				Files.writeString(record, JSON.writeValueAsString(recordRoot), UTF_8);
			}

			FileCampaignStore restarted = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign loaded = restarted.load(ID).orElseThrow();
			requireThat(loaded.throttle(), "throttle").isEqualTo(new Throttle(expired, 1, 0, 8));
			Campaign observed = Transitions.apply(loaded, new CampaignEvent.ObserveRateLimit(
				List.of(new RateLimitWindow(0.92, null)), expired.plusSeconds(60)));
			requireThat(ConcurrencyPolicy.window(observed), "window").isEqualTo(8);
			requireThat(restarted.readRecord(ID, trial.key()).orElseThrow().sessions().getFirst().throttle(),
				"sessionThrottle").isEqualTo(new Throttle(expired, 1, 0, 1));
			requireThat(restarted.readRecord(ID, trial.key()).orElseThrow().sessions().getFirst().commands(),
				"sessionCommands").isEqualTo(CommandSummary.UNKNOWN);
			requireThat(loaded.optimization().commands(), "commands").isEmpty();

			((ObjectNode) root.get("throttle")).put("backoffs", 2);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			Campaign rejected = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow();
			requireThat(rejected.throttle(), "rejectedThrottle").isEqualTo(new Throttle(expired, 1, 2, 1));
		}
	}

	/**
	 * Verifies that a snapshot written before the store recorded each unit of work's concurrency window, which holds
	 * the earlier single window instead, loads with every unit at its starting window and no halving.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void snapshotWithoutWindowsStartsEveryUnit() throws IOException
	{
		// Requirement: R-E4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-windows-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().working(1).
				development("formatting", 60, 0).development("safety", 60, 0).window(8).build());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.remove("concurrency");
			root.putObject("slow_start").put("size", 8).put("passes", 0);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			Campaign loaded = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow();
			requireThat(loaded.windows(), "windows").isEqualTo(ConcurrencyWindows.EMPTY);
			requireThat(ConcurrencyPolicy.window(loaded), "window").isEqualTo(1);
		}
	}

	/**
	 * Verifies that the store preserves ordered conflicts and the resolutions already chosen.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void storesConflictChoices() throws IOException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-conflict-store-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().build());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode document = (ObjectNode) JSON.readTree(Files.readString(snapshot));
			document.set("pending_decisions", JSON.readTree("""
				[{"type":"conflict_found", "conflicts":[
				  {"failures":["failure-1"], "requirement_ids":["r1","r2"],
				   "quotations":[{"path":"CLAUDE.md","text":"Be brief."}],
				   "resolutions":["Exempt explanations.","Drop brevity."], "recommended":1,
				   "reason":"Keeps brevity elsewhere."},
				  {"failures":["failure-2"], "requirement_ids":["r3","r4"],
				   "quotations":[{"path":"rules.md","text":"Always explain."}],
				   "resolutions":["Exempt greetings.","Drop explanations."], "recommended":2,
				   "reason":"Keeps the core task."}], "resolutions":[2]}]
				"""));
			Files.writeString(snapshot, JSON.writeValueAsString(document));
			Campaign loaded = store.load(ID).orElseThrow();
			store.replaceSnapshot(loaded);
			requireThat(JSON.readTree(Files.readString(snapshot)).get("pending_decisions"), "pending").
				isEqualTo(document.get("pending_decisions"));
		}
	}

	/**
	 * Verifies that a campaign without conflict choices cannot be read as the current format.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 8 campaign.*[Ss]tart a new campaign.*")
	public void refusesFormatWithoutConflictChoices() throws IOException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-conflict-version-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().build());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode document = (ObjectNode) JSON.readTree(Files.readString(snapshot));
			document.put("format_version", 8);
			Files.writeString(snapshot, JSON.writeValueAsString(document));
			store.load(ID);
		}
	}

	/**
	 * Verifies that an older campaign without concurrent attempt progress is refused.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 4 campaign.*[Ss]tart a new campaign.*")
	public void refusesSequentialCampaignFormat() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-old-format"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 4);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			store.load(ID);
		}
	}

	/**
	 * Verifies that a campaign without measurement identities for comparison evidence is refused.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 5 campaign.*[Ss]tart a new campaign.*")
	public void refusesUnidentifiedComparisonFormat() throws IOException
	{
		// Requirement: R-M4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-unidentified-format"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 5);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			store.load(ID);
		}
	}

	/**
	 * Verifies that a campaign without regression scenario citations is refused.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 6 campaign.*[Ss]tart a new campaign.*")
	public void refusesUncitedScenarioFormat() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-uncited-format"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 6);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			store.load(ID);
		}
	}

	/**
	 * Retains each message through trial records, judged snapshots, adoption and a store restart.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void repliesSurviveStoreRestart() throws IOException
	{
		// Requirement: R-C12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-reply-evidence"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign assigned = Transitions.apply(start(), new CampaignEvent.Assign(new Admission.Assign(
				Purpose.DEVELOPMENT, 1, start().development().working().snapshot(), "safety", null,
				new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 0, Instant.parse("2026-10-01T12:00:00Z")))));
			LogicalTrial earlier = assigned.trials().getFirst();
			assigned = Transitions.apply(assigned, new CampaignEvent.Assign(new Admission.Assign(
				Purpose.DEVELOPMENT, 1, assigned.development().working().snapshot(), "safety", null,
				new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 0, Instant.parse("2026-10-01T12:00:01Z")))));
			LogicalTrial trial = assigned.trials().getLast();
			store.writeAssignment(ID, earlier);
			store.writeAssignment(ID, trial);
			Campaign running = Transitions.apply(assigned, new CampaignEvent.Start(earlier.key()));
			running = Transitions.apply(running, new CampaignEvent.Start(trial.key()));
			List<List<String>> responses = List.of(List.of("I'll inspect the directory.", "Should I delete it?"),
				List.of("I'll keep it.", "The directory is unchanged."));
			CampaignEvent.Complete outcome = new CampaignEvent.Complete(trial.key(), Verdict.FAIL, "Unasked narration",
				List.of(), null, running.identity(), null, responses, Builder.FILES);
			store.writeOutcome(ID, outcome);
			store.replaceSnapshot(running);

			FileCampaignStore restarted = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign recovered = restarted.load(ID).orElseThrow();
			requireThat(restarted.listUnadoptedOutcomes(recovered), "unadoptedOutcomes").isEqualTo(List.of(outcome));
			Campaign judged = Transitions.apply(recovered, outcome);
			requireThat(judged.trials().getLast().responses(), "judgedResponses").isEqualTo(responses);
			store.replaceSnapshot(judged);
			Campaign loadedJudged = restarted.load(ID).orElseThrow();
			requireThat(loadedJudged.trials().getLast().responses(), "loadedJudgedResponses").isEqualTo(responses);
			Campaign adopted = Transitions.apply(loadedJudged, new CampaignEvent.Complete(earlier.key(),
				Verdict.PASS, "Earlier trial passed", List.of(), null,
				loadedJudged.identity(), null, List.of(), Builder.FILES));
			store.replaceSnapshot(adopted);
			Campaign loadedAdopted = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow();
			requireThat(loadedAdopted.development().failures().getFirst().responses(), "adoptedFailureResponses").
				isEqualTo(responses);
		}
	}

	/**
	 * Retains a development failure's impact through its trial record, the judged snapshot, adoption and a store
	 * restart.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void impactsSurviveStoreRestart() throws IOException
	{
		// Requirement: R-D16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-impact"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign assigned = assignDevelopment(start());
			LogicalTrial trial = assigned.trials().getLast();
			store.writeAssignment(ID, trial);
			Campaign running = Transitions.apply(assigned, new CampaignEvent.Start(trial.key()));
			Triage triage = new Triage(Impact.SELF_RECOVERING, "The reply re-read the file and then asked.");
			CampaignEvent.Complete outcome = new CampaignEvent.Complete(trial.key(), Verdict.FAIL, "Unasked deletion",
				List.of(), triage, running.identity(), null, List.of(), Builder.FILES);
			store.writeOutcome(ID, outcome);
			store.replaceSnapshot(running);

			FileCampaignStore restarted = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign recovered = restarted.load(ID).orElseThrow();
			requireThat(restarted.listUnadoptedOutcomes(recovered), "unadoptedOutcomes").isEqualTo(List.of(outcome));
			Campaign adopted = Transitions.apply(recovered, outcome);
			store.replaceSnapshot(adopted);
			Campaign loaded = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID).orElseThrow();
			requireThat(loaded.development().failures().getFirst().triage(), "failureTriage").isEqualTo(triage);
			requireThat(loaded, "loaded").isEqualTo(adopted);
		}
	}

	/**
	 * Assigns a development trial of the working candidate in {@code safety}.
	 *
	 * @param campaign the campaign
	 * @return the updated campaign, whose last trial is the assigned one
	 */
	private static Campaign assignDevelopment(Campaign campaign)
	{
		return Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.DEVELOPMENT,
			campaign.development().series(), campaign.development().working().snapshot(), "safety", null,
			new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
				Instant.parse("2026-10-01T12:00:00Z")))));
	}

	/**
	 * Refuses campaign snapshots and trial records that stored only final responses.
	 *
	 * @param trialRecord whether to read a trial record instead of the campaign snapshot
	 * @throws IOException if the store fails
	 */
	@Test(dataProvider = "responseDocuments", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 7 campaign.*[Ss]tart a new campaign.*")
	public void refusesFinalResponseFormat(boolean trialRecord) throws IOException
	{
		// Requirement: R-C12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("campaign-final-response-format"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			store.replaceSnapshot(start());
			store.writeAssignment(ID, assigned);
			Path document;
			if (trialRecord)
				document = list(heldOutDirectory(campaigns).resolve("trials")).getFirst();
			else
				document = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(document, UTF_8));
			root.put("format_version", 7);
			Files.writeString(document, JSON.writeValueAsString(root), UTF_8);
			if (trialRecord)
				store.readRecord(ID, assigned.key());
			else
				store.load(ID);
		}
	}

	/**
	 * Returns both persisted document kinds that carry replies.
	 *
	 * @return whether the document is a trial record
	 */
	@DataProvider(name = "responseDocuments")
	public static Object[][] responseDocuments()
	{
		return new Object[][] {{false}, {true}};
	}

	/**
	 * Returns the snapshot fields that a campaign with a judged trial and a diagnosed failure requires.
	 *
	 * @return the JSON pointer of each field's parent and the field's name
	 */
	@DataProvider(name = "requiredSnapshotFields")
	public static Object[][] requiredSnapshotFields()
	{
		return new Object[][]
			{
				{"/trials/0", "rationale"},
				{"/trials/0", "responses"},
				{"/development/failures/0", "rationale"},
				{"/development/failures/0", "reruns"},
				{"/development/failures/0", "responses"},
				{"/development/failures/0/reruns/0", "rationale"}
			};
	}

	/**
	 * A snapshot that lacks a judge's rationale or a failure's diagnostic reruns is malformed and fails to load with
	 * an error that names the file and the field.
	 *
	 * @param parent the JSON pointer of the object that holds the field
	 * @param name the field to remove
	 * @throws IOException if the store fails
	 */
	@Test(dataProvider = "requiredSnapshotFields")
	public void rejectsSnapshotWithoutJudgeEvidence(String parent, String name) throws IOException
	{
		// Requirement: R-E1
		// Requirement: R-C12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-rationale-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().working(1).
				trial(trial(2, TrialState.JUDGED)).
				failure(new Failure(testCase("safety", 3, true),
					"The reply deleted the directory unasked.", List.of(), null,
					List.of(new DiagnosticRerun(Verdict.FAIL, "The rerun deleted the directory too.")), List.of(),
					Builder.FILES)).
				build());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			((ObjectNode) root.at(parent)).remove(name);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			String message = null;
			try
			{
				new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
			}
			catch (IOException e)
			{
				message = e.getMessage();
			}
			requireThat(message, "message").isEqualTo("The file " + snapshot + " is malformed: " + name +
				": is missing");
		}
	}

	/**
	 * A trial record whose outcome lacks the judge's rationale is malformed and fails to read with an error that names
	 * the file and the field.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void rejectsOutcomeWithoutRationale() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-rationale-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			store.writeAssignment(ID, assigned);
			store.writeOutcome(ID, complete(assigned.key(), Verdict.FAIL));
			Path record = list(heldOutDirectory(campaigns).resolve("trials")).getFirst();
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(record, UTF_8));
			((ObjectNode) root.get("outcome")).remove("rationale");
			Files.writeString(record, JSON.writeValueAsString(root), UTF_8);

			String message = null;
			try
			{
				new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).readRecord(ID, assigned.key());
			}
			catch (IOException e)
			{
				message = e.getMessage();
			}
			requireThat(message, "message").isEqualTo("The file " + record + " is malformed: rationale: is missing");
		}
	}

	/**
	 * A snapshot that is not valid JSON fails to load with an error that names the file.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*campaign-1[/\\\\]held_out[/\\\\]campaign\\.json.*")
	public void rejectsMalformedSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-malformed-"));
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			Files.createDirectories(snapshot.getParent());
			Files.writeString(snapshot, "{ \"format_version\": 1, ", UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * A snapshot written in a different format version is detected instead of being misread.
	 *
	 * @throws IOException if the store fails
	 */
	// Requirement: R-D15
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*format_version.*")
	public void rejectsUnknownFormatVersion() throws IOException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 47);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * A campaign of format version 1, whose files are keyed by test rather than by cell, is refused with a message
	 * that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 1 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionOneCampaign() throws IOException
	{
		// Requirement: R-C11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-1-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 1);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * A campaign of format version 2, which lacks the development cell tier, deterministic checks and the core-task
	 * requirement, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 2 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwoCampaign() throws IOException
	{
		// Requirement: R-E19
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-2-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 2);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * A campaign of format version 3, which lacks prompt author manifests and resumable minimality progress, is refused
	 * with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 3 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionThreeCampaign() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-3-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 3);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * A campaign of format version 13, whose contract holds no capability ranking or certification scope and whose
	 * development state records the replaced cell tier, is refused with a message that names its version and asks for
	 * a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 13 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionThirteenCampaign() throws IOException
	{
		// Requirement: R-E22
		// Requirement: R-C15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-13-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 13);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * A campaign of format version 14, whose contract holds no split salt or outcome-changing variables and whose
	 * failures hold no failed requirements, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 14 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionFourteenCampaign() throws IOException
	{
		// Requirement: R-C16
		// Requirement: R-D9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-14-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 14);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * A campaign of format version 33, whose contract groups its prompt tests into categories, is refused with a
	 * message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 33 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionThirtyThreeCampaign() throws IOException
	{
		// Requirement: R-C11
		// Requirement: R-D9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-version-33-"));
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			store.replaceSnapshot(start());
			Path snapshot = heldOutDirectory(campaigns).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 33);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).load(ID);
		}
	}

	/**
	 * Returns a confirmed campaign whose working candidate has no development outcomes.
	 *
	 * @return the campaign
	 */
	private static Campaign start()
	{
		return new Builder().header(header(ID)).confirmed().working(1).build();
	}

	/**
	 * Returns a campaign's directory.
	 *
	 * @param campaigns the directory that holds the campaigns
	 * @return the directory
	 */
	private static Path campaignDirectory(Path campaigns)
	{
		return campaigns.resolve(ID);
	}

	/**
	 * Returns the directory that holds a campaign's held-out content.
	 *
	 * @param campaigns the directory that holds the campaigns
	 * @return the directory
	 */
	private static Path heldOutDirectory(Path campaigns)
	{
		return campaignDirectory(campaigns).resolve("held_out");
	}

	/**
	 * Lists a directory's entries in name order.
	 *
	 * @param directory the directory
	 * @return the entries
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> list(Path directory) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			return entries.sorted().toList();
		}
	}

	/**
	 * Counts the write steps of one trial's lifecycle.
	 *
	 * @return the number of steps
	 * @throws IOException if the store fails
	 */
	private static int countTrialSteps() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaigns = cleanup.add(Files.createTempDirectory("cat-campaign-count-"));
			Campaign start = start();
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(start);
			AtomicInteger performed = new AtomicInteger();
			runTrial(new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer(), _ -> performed.incrementAndGet()), start,
				Verdict.FAIL);
			return performed.get();
		}
	}

	/**
	 * Assigns, runs and adopts one development trial in the write-ahead order.
	 *
	 * @param store the store
	 * @param campaign the campaign before the assignment
	 * @param verdict the trial's outcome
	 * @throws IOException if the store fails
	 */
	private static void runTrial(CampaignStore store, Campaign campaign, Verdict verdict) throws IOException
	{
		Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
			Purpose.DEVELOPMENT, campaign.development().series(), campaign.development().working().snapshot(),
			"safety", null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
			Instant.parse("2026-10-01T12:00:00Z")))));
		LogicalTrial trial = assigned.trials().getLast();
		store.writeAssignment(ID, trial);
		store.replaceSnapshot(assigned);
		Campaign running = Transitions.apply(assigned, new CampaignEvent.Start(trial.key()));
		store.replaceSnapshot(running);
		finish(store, running, trial.key(), verdict);
	}

	/**
	 * Records and adopts the outcome of a running trial.
	 *
	 * @param store the store
	 * @param running the campaign, in which the trial is running
	 * @param key the trial
	 * @param verdict the trial's outcome
	 * @throws IOException if the store fails
	 */
	private static void finish(CampaignStore store, Campaign running, TrialKey key, Verdict verdict)
		throws IOException
	{
		CampaignEvent.Complete outcome = new CampaignEvent.Complete(key, verdict, "", List.of(), null, running.identity(),
			null, List.of(), Builder.FILES);
		store.writeOutcome(ID, outcome);
		store.replaceSnapshot(Transitions.apply(running, outcome));
	}

	/**
	 * Reconciles the campaign after a restart: adopts recorded outcomes, reruns trials that have none with a passing
	 * outcome, and assigns the trial again if the snapshot never held it.
	 *
	 * @param store the restarted store
	 * @throws IOException if the store fails
	 */
	private static void reconcile(CampaignStore store) throws IOException
	{
		Campaign loaded = store.load(ID).orElseThrow();
		List<CampaignEvent.Complete> outcomes = store.listUnadoptedOutcomes(loaded);
		if (!outcomes.isEmpty())
		{
			for (CampaignEvent.Complete outcome : outcomes)
				loaded = Transitions.apply(loaded, outcome);
			store.replaceSnapshot(loaded);
			return;
		}
		if (loaded.trials().isEmpty())
		{
			if (loaded.development().count("safety").outcomes() == 0)
				runTrial(store, loaded, Verdict.PASS);
			return;
		}
		LogicalTrial trial = loaded.trials().getFirst();
		Campaign running = loaded;
		if (trial.state() == TrialState.ASSIGNED)
		{
			running = Transitions.apply(loaded, new CampaignEvent.Start(trial.key()));
			store.replaceSnapshot(running);
		}
		finish(store, running, trial.key(), Verdict.PASS);
	}

	/**
	 * Takes away the permission to read a file. A file system without POSIX permissions gets an access-control entry
	 * that denies the current user the reading instead. Either way, the file can still be deleted.
	 *
	 * @param file a file
	 * @throws IOException if the file's permissions cannot be changed
	 */
	private static void denyReading(Path file) throws IOException
	{
		if (PortableFiles.hasModes(file))
		{
			Files.setPosixFilePermissions(file, Set.of());
			return;
		}
		// The test created the file, so its owner is the user that runs the test
		UserPrincipal user = Files.getOwner(file);
		AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
		List<AclEntry> entries = new ArrayList<>(view.getAcl());
		entries.addFirst(AclEntry.newBuilder().
			setType(AclEntryType.DENY).
			setPrincipal(user).
			setPermissions(AclEntryPermission.READ_DATA).
			build());
		view.setAcl(entries);
	}
}
