package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests the bounded, typed native edit input boundary. */
public final class GenerationEditOperationTest
{
	/** CREATE accepts only an absent source and bounded content. */
	@Test
	public void acceptsCreateWithAbsentPrestateAndBytes()
	{
		byte[] content = {0, (byte) 0xff, 10};
		GenerationEditOperation operation = new GenerationEditOperation("operation-1",
			GenerationEditOperation.Kind.CREATE, Path.of("prompts/new.md"), Optional.empty(),
			GenerationExpectedState.absent(), Optional.empty(), Optional.of(Base64.getEncoder().encodeToString(content)));

		requireThat(operation.decodedContent(), "decodedContent").isEqualTo(content);
	}

	/** RENAME requires an absent destination and forbids content payload. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*destination.*")
	public void rejectsRenameWithoutDestination()
	{
		new GenerationEditOperation("operation-1", GenerationEditOperation.Kind.RENAME,
			Path.of("prompts/old.md"), Optional.empty(), GenerationExpectedState.regular("0".repeat(64)),
			Optional.empty(), Optional.empty());
	}

	/** DELETE cannot carry bytes. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*content.*")
	public void rejectsDeleteContent()
	{
		new GenerationEditOperation("operation-1", GenerationEditOperation.Kind.DELETE,
			Path.of("prompts/old.md"), Optional.empty(), GenerationExpectedState.regular("0".repeat(64)),
			Optional.empty(), Optional.of("eA=="));
	}

	/** A raw path with dot traversal cannot become an authorized pathname through normalization. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*path.*")
	public void rejectsDotdotPath()
	{
		new GenerationEditOperation("operation-1", GenerationEditOperation.Kind.CREATE,
			Path.of("prompts/../outside.md"), Optional.empty(), GenerationExpectedState.absent(),
			Optional.empty(), Optional.of("eA=="));
	}
}
