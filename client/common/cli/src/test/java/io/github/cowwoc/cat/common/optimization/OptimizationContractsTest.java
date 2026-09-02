package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tests the typed optimization handler contracts. */
public final class OptimizationContractsTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/** Creates the test fixture. */
	public OptimizationContractsTest()
	{
	}

	/** Verifies record normalization and strict fingerprint validation. */
	@Test
	public void normalizesContextAndResult()
	{
		Path root = Path.of("target/optimization-contract-test");
		OptimizationContext context = context(root, OperationKind.DISCOVER_PROMPT_SYSTEM);
		requireThat(context.certificationProcessDirectory().isAbsolute(), "certificationProcessDirectory").isTrue();
		requireThat(context.operationDirectory().isAbsolute(), "operationDirectory").isTrue();
		OptimizationResult result = new OptimizationResult(OperationKind.DISCOVER_PROMPT_SYSTEM,
			OperationResultKind.DISCOVERED, Path.of("evidence.json"), SHA_A,
			Optional.of(OperationKind.ANALYZE_PROMPT_SYSTEM));
		requireThat(result.evidencePath().isAbsolute(), "evidencePath").isTrue();
	}

	/** Verifies records reject directories outside the process and malformed fingerprints. */
	@Test
	public void rejectsInvalidRecordValues()
	{
		Path root = Path.of("target/optimization-contract-test").toAbsolutePath();
		requireRuntime(IllegalArgumentException.class, () -> new OptimizationContext(new JsonMapper(), Clock.systemUTC(),
			root, root.resolve("other"), OperationKind.DISCOVER_PROMPT_SYSTEM, SHA_A, SHA_B));
		requireRuntime(IllegalArgumentException.class, () -> new OptimizationContext(new JsonMapper(), Clock.systemUTC(),
			root, root.resolve("operations/one"), OperationKind.DISCOVER_PROMPT_SYSTEM, "bad", SHA_B));
		requireRuntime(IllegalArgumentException.class, () -> new OptimizationResult(
			OperationKind.DISCOVER_PROMPT_SYSTEM, OperationResultKind.DISCOVERED, Path.of("evidence.json"), "BAD",
			Optional.of(OperationKind.ANALYZE_PROMPT_SYSTEM)));
	}

	/** Verifies registry construction rejects every incomplete or ambiguous handler mapping. */
	@Test
	public void rejectsInvalidHandlerRegistries()
	{
		OptimizationHandler<OptimizationInput> discover = handler(OperationKind.DISCOVER_PROMPT_SYSTEM,
			(_context, _input) -> null);
		OptimizationHandler<OptimizationInput> analyze = handler(OperationKind.ANALYZE_PROMPT_SYSTEM,
			(_context, _input) -> null);
		requireRuntime(NullPointerException.class, () -> new OptimizationHandlers(null, List.of(discover)));
		requireRuntime(NullPointerException.class,
			() -> new OptimizationHandlers(Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM), null));
		requireRuntime(IllegalArgumentException.class, () -> new OptimizationHandlers(Set.of(), List.of(discover)));
		requireRuntime(IllegalArgumentException.class,
			() -> new OptimizationHandlers(Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM), List.of()));

		Set<OperationKind> nullKind = Collections.newSetFromMap(new IdentityHashMap<>());
		nullKind.add(null);
		requireRuntime(NullPointerException.class, () -> new OptimizationHandlers(nullKind, List.of(discover)));
		Collection<OptimizationHandler<?>> nullHandler = new ArrayList<>();
		nullHandler.add(null);
		requireRuntime(NullPointerException.class,
			() -> new OptimizationHandlers(Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM), nullHandler));
		requireRuntime(NullPointerException.class, () -> new OptimizationHandlers(
			Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM), List.of(handler(null, (_context, _input) -> null))));
		requireRuntime(NullPointerException.class, () -> new OptimizationHandlers(
			Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM), List.of(handlerWithNullInputType())));
		requireRuntime(IllegalArgumentException.class,
			() -> new OptimizationHandlers(Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM), List.of(analyze)));
		requireRuntime(IllegalArgumentException.class, () -> new OptimizationHandlers(
			Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM), List.of(discover, discover)));
		requireRuntime(IllegalArgumentException.class, () -> new OptimizationHandlers(
			Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM, OperationKind.ANALYZE_PROMPT_SYSTEM), List.of(discover)));
	}

	/** Verifies null dispatch arguments fail at the public dispatch boundary. */
	@Test
	public void rejectsNullDispatchArguments()
	{
		OptimizationHandlers handlers = handlers((_context, _input) -> null);
		Path root = Path.of("target/optimization-contract-test").toAbsolutePath();
		requireRuntime(NullPointerException.class, () -> handlers.execute(null, validInput()));
		requireRuntime(NullPointerException.class,
			() -> handlers.execute(context(root, OperationKind.DISCOVER_PROMPT_SYSTEM), null));
	}

	/** Verifies invalid input identity is rejected before invoking a handler. */
	@Test
	public void rejectsInvalidInputIdentity() throws IOException
	{
		OptimizationHandlers handlers = handlers((_context, _input) ->
		{
			throw new AssertionError("Invalid input must not reach its handler");
		});
		Path root = Path.of("target/optimization-contract-test").toAbsolutePath();
		OptimizationContext context = context(root, OperationKind.DISCOVER_PROMPT_SYSTEM);
		requireIo(() -> handlers.execute(context, input(OperationKind.ANALYZE_PROMPT_SYSTEM, SHA_A)));
		requireIo(() -> handlers.execute(context, input(OperationKind.DISCOVER_PROMPT_SYSTEM, null)));
		requireIo(() -> handlers.execute(context, input(OperationKind.DISCOVER_PROMPT_SYSTEM, "not-a-sha")));
		requireIo(() -> handlers.execute(context, input(null, SHA_A)));
	}

	/** Verifies an input type mismatch is rejected before invoking a handler. */
	@Test
	public void rejectsInputTypeMismatch() throws IOException
	{
		OptimizationHandler<SpecificInput> handler = new OptimizationHandler<>()
		{
			@Override public OperationKind operationKind()
			{
				return OperationKind.DISCOVER_PROMPT_SYSTEM;
			}

			@Override public Class<SpecificInput> inputType()
			{
				return SpecificInput.class;
			}

			@Override public OptimizationResult execute(OptimizationContext context, SpecificInput input)
			{
				throw new AssertionError("Wrong input type must not reach its handler");
			}
		};
		OptimizationHandlers handlers = new OptimizationHandlers(Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM),
			List.of(handler));
		Path root = Path.of("target/optimization-contract-test").toAbsolutePath();
		requireIo(() -> handlers.execute(context(root, OperationKind.DISCOVER_PROMPT_SYSTEM), validInput()));
	}

	/** Verifies handler failures retain their original exception semantics for the executor. */
	@Test
	public void propagatesHandlerFailures() throws IOException
	{
		IOException expectedIo = new IOException("fixture I/O");
		OptimizationHandlers ioHandlers = handlers((_context, _input) ->
		{
			throw expectedIo;
		});
		Path root = Path.of("target/optimization-contract-test").toAbsolutePath();
		try
		{
			ioHandlers.execute(context(root, OperationKind.DISCOVER_PROMPT_SYSTEM), validInput());
			throw new AssertionError("Expected the handler I/O failure");
		}
		catch (IOException actual)
		{
			requireThat(actual, "actual").isEqualTo(expectedIo);
		}

		ProtocolFailure expectedRuntime = new ProtocolFailure();
		OptimizationHandlers runtimeHandlers = handlers((_context, _input) ->
		{
			throw expectedRuntime;
		});
		try
		{
			runtimeHandlers.execute(context(root, OperationKind.DISCOVER_PROMPT_SYSTEM), validInput());
			throw new AssertionError("Expected the handler protocol failure");
		}
		catch (ProtocolFailure actual)
		{
			requireThat(actual, "actual").isEqualTo(expectedRuntime);
		}
	}

	/** Verifies invalid handler results fail before they can advance the operation. */
	@Test
	public void rejectsInvalidHandlerResults() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimization-result-"));
			Path evidence = Files.writeString(root.resolve("evidence.json"), "evidence");
			String fingerprint = Fingerprints.ofFile(evidence);
			OptimizationContext context = context(root, OperationKind.DISCOVER_PROMPT_SYSTEM);
			requireIo(() -> handlers((_context, _input) -> null).execute(context, validInput()));
			requireIo(() -> handlers((_context, _input) -> new OptimizationResult(
				OperationKind.ANALYZE_PROMPT_SYSTEM, OperationResultKind.ANALYZED, evidence, fingerprint,
				Optional.of(OperationKind.EXTRACT_CONTRACT))).execute(context, validInput()));
			requireIo(() -> handlers((_context, _input) -> new OptimizationResult(
				OperationKind.DISCOVER_PROMPT_SYSTEM, OperationResultKind.ANALYZED, evidence, fingerprint,
				Optional.of(OperationKind.ANALYZE_PROMPT_SYSTEM))).execute(context, validInput()));
			requireIo(() -> handlers((_context, _input) -> new OptimizationResult(
				OperationKind.DISCOVER_PROMPT_SYSTEM, OperationResultKind.DISCOVERED, evidence, fingerprint,
				Optional.empty())).execute(context, validInput()));
			requireIo(() -> handlers((_context, _input) -> new OptimizationResult(
				OperationKind.DISCOVER_PROMPT_SYSTEM, OperationResultKind.DISCOVERED, evidence, fingerprint,
				Optional.of(OperationKind.START_CERTIFICATION_PROCESS))).execute(context, validInput()));

			OptimizationContext finishContext = context(root, OperationKind.FINISH_CERTIFICATION_PROCESS);
			OptimizationHandlers finishHandlers = new OptimizationHandlers(
				Set.of(OperationKind.FINISH_CERTIFICATION_PROCESS), List.of(handler(
				OperationKind.FINISH_CERTIFICATION_PROCESS, (_context, _input) -> new OptimizationResult(
					OperationKind.FINISH_CERTIFICATION_PROCESS, OperationResultKind.CERTIFIED, evidence, fingerprint,
					Optional.of(OperationKind.REPORT)))));
			requireIo(() -> finishHandlers.execute(finishContext,
				input(OperationKind.FINISH_CERTIFICATION_PROCESS, SHA_A)));
		}
	}

	/** Verifies missing, nonregular, and changed evidence is rejected. */
	@Test
	public void rejectsInvalidEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimization-evidence-"));
			OptimizationContext context = context(root, OperationKind.DISCOVER_PROMPT_SYSTEM);
			Path missing = root.resolve("missing.json");
			requireIo(() -> handlers((_context, _input) -> validResult(missing, SHA_A)).execute(context, validInput()));
			requireIo(() -> handlers((_context, _input) -> validResult(root, SHA_A)).execute(context, validInput()));
			Path evidence = Files.writeString(root.resolve("evidence.json"), "evidence");
			requireIo(() -> handlers((_context, _input) -> validResult(evidence, SHA_A)).execute(context, validInput()));
		}
	}

	/** Verifies successful dispatch validates and returns retained evidence. */
	@Test
	public void dispatchesAndVerifiesEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimization-contract-"));
			Path evidence = Files.writeString(root.resolve("evidence.json"), "evidence");
			String fingerprint = Fingerprints.ofFile(evidence);
			AtomicBoolean invoked = new AtomicBoolean();
			OptimizationHandlers handlers = handlers((_context, _input) ->
			{
				invoked.set(true);
				return validResult(evidence, fingerprint);
			});
			OptimizationResult result = handlers.execute(context(root, OperationKind.DISCOVER_PROMPT_SYSTEM),
				validInput());
			requireThat(invoked.get(), "invoked").isTrue();
			requireThat(result.evidenceFingerprint(), "evidenceFingerprint").isEqualTo(fingerprint);
		}
	}

	/**
	 * Creates a valid operation context for the specified test operation.
	 *
	 * @param root the fixture process directory
	 * @param operationKind the selected operation
	 * @return the valid operation context
	 */
	private static OptimizationContext context(Path root, OperationKind operationKind)
	{
		return new OptimizationContext(new JsonMapper(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), root,
			root.resolve("operations/one"), operationKind, SHA_A, SHA_B);
	}

	/**
	 * Creates the standard valid discovery input.
	 *
	 * @return the valid discovery input
	 */
	private static OptimizationInput validInput()
	{
		return input(OperationKind.DISCOVER_PROMPT_SYSTEM, SHA_A);
	}

	/**
	 * Creates a fixture input with the supplied operation identity and fingerprint.
	 *
	 * @param operationKind the input operation
	 * @param fingerprint the input fingerprint
	 * @return the fixture input
	 */
	private static OptimizationInput input(OperationKind operationKind, String fingerprint)
	{
		return new OptimizationInput()
		{
			@Override public OperationKind operationKind()
			{
				return operationKind;
			}

			@Override public String inputFingerprint()
			{
				return fingerprint;
			}
		};
	}

	/**
	 * Creates a registry containing the standard discovery handler.
	 *
	 * @param action the handler behavior
	 * @return the fixture registry
	 */
	private static OptimizationHandlers handlers(HandlerAction action)
	{
		return new OptimizationHandlers(Set.of(OperationKind.DISCOVER_PROMPT_SYSTEM),
			List.of(handler(OperationKind.DISCOVER_PROMPT_SYSTEM, action)));
	}

	/**
	 * Creates a handler for one fixture operation.
	 *
	 * @param operation the owned operation
	 * @param action the handler behavior
	 * @return the fixture handler
	 */
	private static OptimizationHandler<OptimizationInput> handler(OperationKind operation, HandlerAction action)
	{
		return new OptimizationHandler<>()
		{
			@Override public OperationKind operationKind()
			{
				return operation;
			}

			@Override public Class<OptimizationInput> inputType()
			{
				return OptimizationInput.class;
			}

			@Override public OptimizationResult execute(OptimizationContext context, OptimizationInput input)
				throws IOException
			{
				return action.execute(context, input);
			}
		};
	}

	/**
	 * Creates an invalid handler that reports no input type.
	 *
	 * @return the invalid handler
	 */
	private static OptimizationHandler<OptimizationInput> handlerWithNullInputType()
	{
		return new OptimizationHandler<>()
		{
			@Override public OperationKind operationKind()
			{
				return OperationKind.DISCOVER_PROMPT_SYSTEM;
			}

			@Override public Class<OptimizationInput> inputType()
			{
				return null;
			}

			@Override public OptimizationResult execute(OptimizationContext context, OptimizationInput input)
			{
				return null;
			}
		};
	}

	/**
	 * Creates a valid discovery result backed by the supplied evidence.
	 *
	 * @param evidence the retained evidence file
	 * @param fingerprint the evidence fingerprint
	 * @return the valid result
	 */
	private static OptimizationResult validResult(Path evidence, String fingerprint)
	{
		return new OptimizationResult(OperationKind.DISCOVER_PROMPT_SYSTEM, OperationResultKind.DISCOVERED,
			evidence, fingerprint, Optional.of(OperationKind.ANALYZE_PROMPT_SYSTEM));
	}

	/**
	 * Verifies an action fails with the exact expected runtime type.
	 *
	 * @param type the expected exception type
	 * @param action the action expected to fail
	 */
	private static void requireRuntime(Class<? extends RuntimeException> type, RuntimeAction action)
	{
		try
		{
			action.run();
			throw new AssertionError("Expected " + type.getSimpleName());
		}
		catch (RuntimeException actual)
		{
			requireThat(actual.getClass().getName(), "exceptionType").isEqualTo(type.getName());
		}
		catch (IOException actual)
		{
			throw new AssertionError("Expected " + type.getSimpleName(), actual);
		}
	}

	/**
	 * Verifies an action fails with I/O and returns the retained exception.
	 *
	 * @param action the action expected to fail
	 * @return the caught I/O exception
	 */
	private static IOException requireIo(IoAction action)
	{
		try
		{
			action.run();
			throw new AssertionError("Expected IOException");
		}
		catch (IOException actual)
		{
			return actual;
		}
	}

	@FunctionalInterface
	private interface HandlerAction
	{
		/**
		 * Executes the fixture handler.
		 *
		 * @param context the operation context
		 * @param input the operation input
		 * @return the fixture result
		 * @throws IOException if the fixture models an I/O failure
		 */
		OptimizationResult execute(OptimizationContext context, OptimizationInput input) throws IOException;
	}

	@FunctionalInterface
	private interface IoAction
	{
		/** Executes fixture I/O. */
		void run() throws IOException;
	}

	@FunctionalInterface
	private interface RuntimeAction
	{
		/** Executes fixture code that may fail. */
		void run() throws IOException;
	}

	/** Input subtype used to verify dispatch type checking. */
	private interface SpecificInput extends OptimizationInput
	{
	}

	/** Runtime failure used to verify executor-visible protocol failure semantics. */
	private static final class ProtocolFailure extends RuntimeException
	{
		private static final long serialVersionUID = 1;
	}
}
