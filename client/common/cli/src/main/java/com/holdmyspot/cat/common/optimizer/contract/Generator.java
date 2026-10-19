package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Locale;

/**
 * Draws one of a cell's test cases from a contract to produce a trial: its concrete turns, expected result and draw
 * identity. Rejects trials that their prompt test does not permit.
 * <p>
 * A cell pairs a prompt test with a model and reasoning-effort profile. A draw picks one of the cell's test's test
 * cases in the part that the stream's kind draws, each with equal probability. A development draw of a test that has
 * {@link AddedTestCases added test cases} gives them, together, half of the test's draws in equal shares, and the
 * confirmed development test cases the other half. The draw depends only on the stream's kind, seed and position and on
 * the added test cases, never on candidate identity; formal and baseline draws never use added test cases.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent draws from one stream are serialized and use
 * consecutive positions.
 */
public final class Generator
{
	/**
	 * The identity that every test case records, which changes whenever a draw would select differently for the same
	 * stream position and added test cases.
	 */
	public static final String IDENTITY = "contract-generator/4";
	private final Contract contract;
	private final AddedTestCases added;

	/**
	 * Creates a generator of a contract with no added development test cases.
	 *
	 * @param contract the confirmed contract
	 * @throws NullPointerException if {@code contract} is null
	 */
	public Generator(Contract contract)
	{
		this(contract, AddedTestCases.NONE);
	}

	/**
	 * Creates a generator.
	 *
	 * @param contract the confirmed contract
	 * @param added the test cases added to the development part of the contract's tests
	 * @throws NullPointerException if any argument is null
	 */
	public Generator(Contract contract, AddedTestCases added)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(added, "added").isNotNull();
		this.contract = contract;
		this.added = added;
	}

	/**
	 * Draws one randomly sampled test case of a cell's prompt test, eligible to measure the pass rate.
	 *
	 * @param cellId the identifier of the test/profile pair chosen before the draw
	 * @param stream the stream to draw from
	 * @return the validated test case
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the contract has no such cell
	 * @throws IllegalStateException if {@code stream} is closed
	 * @throws GeneratorValidationException if the drawn test has no test case in the stream's part, or the drawn test
	 *   case is not permitted by its prompt test
	 */
	public DrawnTestCase draw(String cellId, DrawStream stream)
	{
		requireThat(cellId, "cellId").isNotNull();
		requireThat(stream, "stream").isNotNull();
		Cell cell = contract.findCell(cellId).orElseThrow(() -> new IllegalArgumentException(
			"The contract has no cell '" + cellId + "'"));
		PromptTest test = contract.testOf(cell);

		DrawnTestCase drawn;
		// Holding the stream's lock keeps the recorded start state paired with the value that the draw reads.
		synchronized (stream)
		{
			DrawStream.State start = stream.state();
			Part part = start.kind().part();
			List<TestCase> confirmed = contract.split().testCases(test, part);
			if (confirmed.isEmpty())
			{
				throw new GeneratorValidationException("The test '" + test.id() + "' has no test case in the " + part +
					" part");
			}
			List<AddedTestCase> testAdded;
			if (part == Part.DEVELOPMENT)
				testAdded = added.of(test.id());
			else
				testAdded = List.of();
			double uniform = stream.nextDouble();
			TestCase testCase;
			if (testAdded.isEmpty())
				testCase = pick(confirmed, uniform);
			else if (uniform < 0.5)
				testCase = pick(confirmed, uniform * 2);
			else
				testCase = pick(testAdded, (uniform - 0.5) * 2).testCase();
			drawn = instantiate(cell, test, testCase, start);
		}
		validate(drawn);
		return drawn;
	}

	/**
	 * Draws one of a test's added test cases, for a trial that tries whether those test cases reproduce a failure.
	 * The draw picks one of them with equal probability.
	 *
	 * @param cellId the identifier of the cell that the trial runs in
	 * @param testId the identifier of the cell's prompt test
	 * @param testCases the test's test cases that this generator's added test cases hold
	 * @param stream the development stream to draw from
	 * @return the validated test case
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract has no such cell, the cell belongs to another test,
	 *   {@code testCases} is empty or holds a test case of another test, or {@code stream} is not a development stream
	 * @throws IllegalStateException if {@code stream} is closed
	 * @throws GeneratorValidationException if the drawn test case is not permitted by its prompt test
	 */
	public DrawnTestCase drawAdded(String cellId, String testId, List<AddedTestCase> testCases, DrawStream stream)
	{
		requireThat(cellId, "cellId").isNotNull();
		requireThat(testId, "testId").isNotNull();
		requireThat(testCases, "testCases").isNotEmpty();
		requireThat(stream, "stream").isNotNull();
		Cell cell = contract.findCell(cellId).orElseThrow(() -> new IllegalArgumentException(
			"The contract has no cell '" + cellId + "'"));
		if (!cell.testId().equals(testId))
		{
			throw new IllegalArgumentException("The cell '" + cellId + "' belongs to the test '" + cell.testId() +
				"', not to '" + testId + "'");
		}
		PromptTest test = contract.testOf(cell);
		for (AddedTestCase testCase : testCases)
			requireThat(testCase.testId(), "testCase.testId()").isEqualTo(testId, "testId");

		DrawnTestCase drawn;
		synchronized (stream)
		{
			DrawStream.State start = stream.state();
			requireThat(start.kind(), "stream.kind()").isEqualTo(DrawStream.Kind.DEVELOPMENT);
			TestCase testCase = pick(testCases, stream.nextDouble()).testCase();
			drawn = instantiate(cell, test, testCase, start);
		}
		validate(drawn);
		return drawn;
	}

	/**
	 * Picks the element whose equal share of [0, 1) contains a uniform draw.
	 *
	 * @param <T> the type of element
	 * @param elements the elements, which must not be empty
	 * @param uniform a uniform value in [0, 1)
	 * @return the picked element, which is the last one if rounding leaves the draw past the last share
	 */
	private static <T> T pick(List<T> elements, double uniform)
	{
		return elements.get(Math.min((int) (uniform * elements.size()), elements.size() - 1));
	}

	/**
	 * Builds the trial of a test case.
	 *
	 * @param cell the cell that the test case runs in
	 * @param test the cell's prompt test
	 * @param testCase the drawn test case
	 * @param start the stream's state before the draw
	 * @return the trial, not yet validated
	 */
	private static DrawnTestCase instantiate(Cell cell, PromptTest test, TestCase testCase, DrawStream.State start)
	{
		return new DrawnTestCase(cell.id(), test.id(), testCase, test.expandTurns(testCase),
			test.expectedBehaviour(), IDENTITY, start.kind(), start.position(), false);
	}

	/**
	 * Marks a test case for a selected rerun, without drawing. Such targeted work is excluded from the randomly
	 * sampled evidence used to measure the pass rate, and uses development test cases only.
	 *
	 * @param testCase a test case that was drawn earlier
	 * @return the same test case, marked targeted
	 * @throws NullPointerException if {@code testCase} is null
	 * @throws GeneratorValidationException if the test case is held-out or not permitted by its prompt test
	 */
	public DrawnTestCase markTargeted(DrawnTestCase testCase)
	{
		validate(testCase);
		Part part = testCase.streamKind().part();
		if (part != Part.DEVELOPMENT)
		{
			throw new GeneratorValidationException("A targeted run uses development test cases only, but the test case " +
				"is " + part + ": a " + testCase.streamKind().name().toLowerCase(Locale.ROOT) + " stream drew it");
		}
		return new DrawnTestCase(testCase.cellId(), testCase.testId(), testCase.testCase(), testCase.turns(),
			testCase.expectedBehaviour(), testCase.generator(), testCase.streamKind(), testCase.streamPosition(), true);
	}

	/**
	 * Checks that a test case is one its prompt test permits.
	 *
	 * @param drawn the test case to check
	 * @throws NullPointerException if {@code drawn} is null
	 * @throws GeneratorValidationException if the test case is not one of its test's confirmed or added test cases, or
	 *   belongs to a part that its stream does not draw, or its turns or expected behaviour differ from its test's
	 */
	public void validate(DrawnTestCase drawn)
	{
		requireThat(drawn, "drawn").isNotNull();
		if (!drawn.generator().equals(IDENTITY))
		{
			throw new GeneratorValidationException("The test case was drawn by '" + drawn.generator() +
				"', not by '" + IDENTITY + "'");
		}
		Cell cell = contract.findCell(drawn.cellId()).orElseThrow(() ->
			new GeneratorValidationException("The contract has no cell '" + drawn.cellId() + "'"));
		if (!cell.testId().equals(drawn.testId()))
		{
			throw new GeneratorValidationException("The cell '" + cell.id() + "' belongs to the test '" +
				cell.testId() + "', not to '" + drawn.testId() + "'");
		}
		PromptTest test = contract.testOf(cell);

		TestCase testCase = drawn.testCase();
		Part streamPart = drawn.streamKind().part();
		String streamName = drawn.streamKind().name().toLowerCase(Locale.ROOT);
		if (test.allTestCases().contains(testCase))
		{
			Part part = contract.split().partOf(test, testCase);
			if (part != streamPart)
			{
				throw new GeneratorValidationException("The test case belongs to the " + part + " part, but a " +
					streamName + " stream draws from the " + streamPart + " part");
			}
		}
		else if (added.of(test.id()).stream().anyMatch(candidate -> candidate.testCase().equals(testCase)))
		{
			if (streamPart != Part.DEVELOPMENT)
			{
				throw new GeneratorValidationException("The test case is an added development test case, which " +
					"belongs to the development part only, but a " + streamName + " stream draws from the " + streamPart +
					" part");
			}
		}
		else
		{
			throw new GeneratorValidationException("The test case is neither a test case of the test '" + test.id() +
				"' nor one added to it");
		}
		if (!drawn.turns().equals(test.expandTurns(testCase)))
		{
			throw new GeneratorValidationException("The turns of the test case are not the test '" + test.id() +
				"' turns with the test case's values inserted");
		}
		if (!drawn.expectedBehaviour().equals(test.expectedBehaviour()))
		{
			throw new GeneratorValidationException("The test case's expected behaviour differs from the test '" +
				test.id() + "' expected behaviour");
		}
	}
}
