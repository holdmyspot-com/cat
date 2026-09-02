package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.Fingerprints;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable type-safe dispatch registry for optimization operation handlers.
 */
public final class OptimizationHandlers
{
	private final Map<OperationKind, OptimizationHandler<?>> handlers;

	/**
	 * Builds a registry containing exactly one handler for each required operation.
	 *
	 * @param requiredKinds the operation kinds that must have handlers
	 * @param handlers the handler implementations
	 */
	OptimizationHandlers(Set<OperationKind> requiredKinds, Collection<OptimizationHandler<?>> handlers)
	{
		Objects.requireNonNull(requiredKinds, "requiredKinds must not be null");
		Objects.requireNonNull(handlers, "handlers must not be null");
		if (requiredKinds.isEmpty())
			throw new IllegalArgumentException("requiredKinds must not be empty");
		if (handlers.isEmpty())
			throw new IllegalArgumentException("handlers must not be empty");
		Set<OperationKind> required = EnumSet.copyOf(requiredKinds);
		if (required.size() != requiredKinds.size() || required.contains(null))
			throw new NullPointerException("requiredKinds must contain no null elements");
		Map<OperationKind, OptimizationHandler<?>> map = new EnumMap<>(OperationKind.class);
		for (OptimizationHandler<?> handler : handlers)
		{
			Objects.requireNonNull(handler, "handlers must contain no null elements");
			OperationKind kind = Objects.requireNonNull(handler.operationKind(),
				"handler operationKind must not be null");
			Objects.requireNonNull(handler.inputType(), "handler inputType must not be null");
			if (!required.contains(kind))
				throw new IllegalArgumentException("Handler is outside required operation set: " + kind);
			if (map.put(kind, handler) != null)
				throw new IllegalArgumentException("Duplicate handler for operation: " + kind);
		}
		if (!map.keySet().equals(required))
			throw new IllegalArgumentException("Handlers must contain exactly the required operation set");
		this.handlers = new EnumMap<>(map);
	}

	/**
	 * Dispatches one typed input and verifies its retained evidence.
	 *
	 * @param context the operation context
	 * @param input the typed operation input
	 * @return the verified operation result
	 * @throws IOException if dispatch or evidence verification fails
	 */
	OptimizationResult execute(OptimizationContext context, OptimizationInput input) throws IOException
	{
		Objects.requireNonNull(context, "context must not be null");
		Objects.requireNonNull(input, "input must not be null");
		OperationKind inputKind;
		String inputFingerprint;
		try
		{
			inputKind = input.operationKind();
			inputFingerprint = input.inputFingerprint();
		}
		catch (RuntimeException e)
		{
			throw new IOException("Optimization input cannot be inspected", e);
		}
		if (inputKind == null || !isSha256(inputFingerprint))
			throw new IOException("Optimization input has invalid operation identity");
		if (context.operationKind() != inputKind)
			throw new IOException("Context and input operation kinds differ");
		OptimizationHandler<?> handler = handlers.get(context.operationKind());
		if (handler == null)
			throw new IOException("No handler for operation: " + context.operationKind());
		return dispatch(handler, context, input);
	}

	/**
	 * Dispatches an input after its runtime type has been checked.
	 *
	 * @param <I> the handler input type
	 * @param handler the selected handler
	 * @param context the operation context
	 * @param input the untyped operation input
	 * @return the verified operation result
	 * @throws IOException if dispatch or evidence verification fails
	 */
	private static <I extends OptimizationInput> OptimizationResult dispatch(OptimizationHandler<I> handler,
		OptimizationContext context, OptimizationInput input) throws IOException
	{
		if (!handler.inputType().isInstance(input))
			throw new IOException("Input type does not match handler for operation: " + context.operationKind());
		OptimizationResult result = handler.execute(context, handler.inputType().cast(input));
		if (result == null)
			throw new IOException("Optimization handler returned no result");
		if (result.operationKind() != context.operationKind())
			throw new IOException("Handler result operation kind differs from context");
		Set<OperationKind> successors;
		try
		{
			successors = OptimizationOperationRegistry.successors(result.operationKind(), result.resultKind());
		}
		catch (RuntimeException e)
		{
			throw new IOException("Handler result kind is not registered", e);
		}
		if (result.successorKind().isPresent() != !successors.isEmpty() ||
			result.successorKind().filter(successors::contains).isEmpty() && !successors.isEmpty())
			throw new IOException("Handler result successor is not permitted");
		if (!Files.isRegularFile(result.evidencePath()))
			throw new IOException("Optimization evidence is not a regular file: " + result.evidencePath());
		String actual = Fingerprints.ofFile(result.evidencePath());
		if (!actual.equals(result.evidenceFingerprint()))
			throw new IOException("Optimization evidence fingerprint does not match: " + result.evidencePath());
		return result;
	}

	/**
	 * Determines whether a value is a lowercase SHA-256 fingerprint.
	 *
	 * @param value the candidate fingerprint
	 * @return whether the candidate is valid
	 */
	private static boolean isSha256(String value)
	{
		return value != null && value.matches("[0-9a-f]{64}");
	}
}
