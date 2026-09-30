package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/** Exercises pinned authority and semantic receipt checks during delivery recovery. */
public final class DeliveryRecoveryIntegrityTest
{
	/** A pending journal cannot replace the campaign's confirmed editable roots. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*roots.*")
	public void rejectsForeignPendingRoots() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String id = "foreign-pending";
			Path target = Path.of("unconfirmed/rule.md");
			DeliveryPath path = new DeliveryPath(0, target, DeliveryPath.privateSibling(target, id, 0, "stage"),
				DeliveryPath.privateSibling(target, id, 0, "predecessor"),
				DeliveryPath.privateSibling(target, id, 0, "rollback"), DeliveryFileState.absent(),
				DeliveryFileState.regular(PromptSnapshot.sha256("foreign".getBytes(StandardCharsets.UTF_8))), Optional.empty(),
				Optional.empty(), Optional.empty(), DeliveryPathPhase.PLANNED);
			DeliveryTransaction forged = new DeliveryTransaction(DeliveryTransaction.SCHEMA_VERSION, id, 0,
				DeliveryKind.MUTATING, DeliveryPhase.PREPARING, fixture.baseline.id(), Optional.empty(),
				fixture.selected.id(), List.of(new EditableRoot(target, EditableRoot.Kind.FILE)), List.of(),
				List.of(path));
			new DeliveryJournal(fixture.store.directory()).append(forged);
			fixture.delivery.recoverPending();
		}
	}

	/** A hash-valid indexed receipt cannot substitute false per-path before and after states. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*receipt.*")
	public void rejectsFalseIndexedPathResult() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CandidateApplication application = fixture.delivery.reconcile(fixture.baseline.id(), null,
				fixture.selected.id());
			DeliveryReceipt original = fixture.evidence.read(new EvidenceRef("delivery-receipts",
				application.receiptId()), "delivery-receipts", DeliveryReceipt.class);
			DeliveryPathReceipt actual = original.paths().getFirst();
			DeliveryPathReceipt falseResult = new DeliveryPathReceipt(actual.target(), actual.after(),
				actual.after(), actual.after(), DeliveryPathReceipt.Outcome.APPLIED);
			DeliveryReceipt forged = new DeliveryReceipt(DeliveryReceipt.SCHEMA_VERSION, original.transactionId(),
				original.baselineId(), original.previousAppliedCandidateId(), original.selectedCandidateId(),
				original.outcome(), List.of(falseResult), original.finalTransactionSha256());
			EvidenceRef forgedRef = fixture.evidence.publish("delivery-receipts", forged);
			DeliveryReceiptIndex index = new DeliveryReceiptIndex(DeliveryReceiptIndex.SCHEMA_VERSION,
				application.transactionId(), forgedRef, original.finalTransactionSha256(), original.outcome());
			Files.write(fixture.store.directory().resolve("delivery-receipt-indexes").resolve(
				application.transactionId() + ".json"), SnapshotJson.mapper().writeValueAsBytes(index));
			requireThat(Files.readString(fixture.prompt), "visibleSelected").isEqualTo("after");
			fixture.delivery.recoverPending();
		}
	}

	/** Owns one campaign, its snapshots, and a selected worktree. */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final CampaignStore store;
		private final CandidateEvidenceRepository evidence;
		private final CandidateDelivery delivery;
		private final PromptSnapshot baseline;
		private final PromptSnapshot selected;
		private final Path prompt;

		/** Creates a selected snapshot and pinned delivery authority. */
		private Fixture() throws IOException
		{
			Path owner = cleanup.add(Files.createTempDirectory("delivery-recovery-integrity-"));
			Path project = Files.createDirectory(owner.resolve("project"));
			Path selectedProject = Files.createDirectory(owner.resolve("selected"));
			Files.createDirectory(project.resolve("prompts"));
			Files.createDirectory(selectedProject.resolve("prompts"));
			prompt = project.resolve("prompts/rule.md");
			Files.writeString(prompt, "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
			baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts/rule.md")));
			selected = PromptSnapshot.capture(selectedProject, List.of(Path.of("prompts/rule.md")));
			store = CampaignStore.initialize(owner.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(baseline);
			snapshots.save(selected);
			evidence = new CandidateEvidenceRepository(store.directory(), store.binding());
			EditableRoot root = new EditableRoot(Path.of("prompts/rule.md"), EditableRoot.Kind.FILE);
			EvidenceRef roots = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
				project.toRealPath(), CandidateTestRoots.contract(store.directory()),
				CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			delivery = new CandidateDelivery(project, store.directory(), store.binding(), snapshots, roots);
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
