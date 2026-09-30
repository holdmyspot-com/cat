package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Optional;
import java.util.List;
import java.nio.file.Path;
import org.testng.annotations.Test;

/** Tests strict presence and schema shape for durable delivery transactions. */
public final class DeliveryTransactionSchemaTest
{
	/** An absent path carries neither a kind nor a hash. */
	@Test
	public void representsAbsentPathWithoutContent()
	{
		DeliveryFileState state = new DeliveryFileState(DeliveryPresence.ABSENT, Optional.empty(),
			Optional.empty());
		requireThat(state.presence(), "presence").isEqualTo(DeliveryPresence.ABSENT);
	}

	/** A regular file must carry its exact content identity. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*REGULAR.*")
	public void rejectsRegularFileWithoutHash()
	{
		new DeliveryFileState(DeliveryPresence.REGULAR, Optional.of(PromptSnapshot.FileKind.REGULAR),
			Optional.empty());
	}

	/** A durable NO_OP has no path or directory mutations but still reaches APPLIED. */
	@Test
	public void representsAppliedNoOp()
	{
		DeliveryTransaction transaction = new DeliveryTransaction(2, "transaction", 1,
			DeliveryKind.NO_OP, DeliveryPhase.APPLIED, "0".repeat(64), Optional.empty(), "1".repeat(64),
			List.of(new EditableRoot(Path.of("prompts/rule.md"), EditableRoot.Kind.FILE)), List.of(), List.of());
		requireThat(transaction.paths().isEmpty(), "noOpPaths").isTrue();
	}

	/** A no-op marker cannot hide a user-path mutation. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*NO_OP.*")
	public void rejectsNoOpWithPath()
	{
		DeliveryPath path = new DeliveryPath(0, Path.of("prompts/rule.md"), Path.of("prompts/stage"),
			Path.of("prompts/predecessor"), Path.of("prompts/rollback"), DeliveryFileState.absent(),
			DeliveryFileState.regular("1".repeat(64)), Optional.empty(), Optional.empty(), Optional.empty(),
			DeliveryPathPhase.PLANNED);
		new DeliveryTransaction(2, "transaction", 1, DeliveryKind.NO_OP, DeliveryPhase.PREPARING,
			"0".repeat(64), Optional.empty(), "1".repeat(64), List.of(new EditableRoot(
				Path.of("prompts/rule.md"), EditableRoot.Kind.FILE)), List.of(), List.of(path));
	}

	/** A no-change reconciliation still has an archived matching transaction receipt. */
	@Test
	public void representsNoOpReceipt()
	{
		DeliveryReceipt receipt = new DeliveryReceipt(1, "transaction", "0".repeat(64), Optional.empty(),
			"1".repeat(64), DeliveryReceiptOutcome.ALREADY_APPLIED, List.of(), "2".repeat(64));
		requireThat(receipt.outcome(), "receiptOutcome").isEqualTo(DeliveryReceiptOutcome.ALREADY_APPLIED);
	}

	/** A linked phase must retain the hash used for publication before private cleanup. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*linked.*hash.*")
	public void rejectsLinkedPhaseWithoutHash()
	{
		new DeliveryPath(0, Path.of("prompts/rule.md"), Path.of("prompts/.stage"),
			Path.of("prompts/.predecessor"), Path.of("prompts/.rollback"), DeliveryFileState.absent(),
			DeliveryFileState.regular("1".repeat(64)), Optional.empty(), Optional.empty(), Optional.empty(),
			DeliveryPathPhase.SELECTED_LINKED);
	}

	/** A forged journal cannot name a user file as a transaction-owned private stage. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*private.*")
	public void rejectsForgedPrivateStage()
	{
		DeliveryPath path = new DeliveryPath(0, Path.of("prompts/rule.md"), Path.of("prompts/user.md"),
			Path.of("prompts/.predecessor"), Path.of("prompts/.rollback"), DeliveryFileState.absent(),
			DeliveryFileState.regular("1".repeat(64)), Optional.empty(), Optional.empty(), Optional.empty(),
			DeliveryPathPhase.PLANNED);
		new DeliveryTransaction(2, "transaction", 0, DeliveryKind.MUTATING, DeliveryPhase.PREPARING,
			"0".repeat(64), Optional.empty(), "1".repeat(64), List.of(new EditableRoot(
				Path.of("prompts/rule.md"), EditableRoot.Kind.FILE)), List.of(), List.of(path));
	}
}
