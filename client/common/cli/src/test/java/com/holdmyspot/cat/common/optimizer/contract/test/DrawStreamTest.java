package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link DrawStream}.
 */
public final class DrawStreamTest
{
	private static final long SEED = 20_260_401L;

	/**
	 * Creates the test fixture.
	 */
	public DrawStreamTest()
	{
	}

	/**
	 * Draws values from a stream.
	 *
	 * @param stream the stream
	 * @param count the number of values
	 * @return the values, in draw order
	 */
	private static List<Long> take(DrawStream stream, int count)
	{
		List<Long> values = new ArrayList<>();
		for (int i = 0; i < count; ++i)
			values.add(stream.nextLong());
		return values;
	}

	/**
	 * Verifies that a stream resumed from its persisted state continues with the value that follows the recorded
	 * position, so a restart neither repeats nor skips a draw.
	 */
	@Test
	public void resumesAtSamePosition()
	{
		// Requirement: R-C4
		DrawStream uninterrupted = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		List<Long> expected = take(uninterrupted, 50);

		DrawStream beforeRestart = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		List<Long> actual = take(beforeRestart, 20);
		DrawStream.State persisted = beforeRestart.state();
		DrawStream afterRestart = DrawStream.resume(persisted);
		actual.addAll(take(afterRestart, 30));

		requireThat(persisted.position(), "persisted.position()").isEqualTo(20L);
		requireThat(persisted.seed(), "persisted.seed()").isEqualTo(SEED);
		requireThat(actual, "actual").isEqualTo(expected);
		requireThat(afterRestart.state().position(), "afterRestart.state().position()").isEqualTo(50L);
	}

	/**
	 * Verifies that marking drawn test cases for targeted reruns leaves the following random draws unchanged.
	 */
	@Test
	public void targetedMarkingDoesNotChangeDraws()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream passing = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		DrawStream failing = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		List<DrawnTestCase> passingRun = new ArrayList<>();
		List<DrawnTestCase> failingRun = new ArrayList<>();
		for (int i = 0; i < 40; ++i)
		{
			passingRun.add(generator.draw("formatting", passing));

			DrawnTestCase failed = generator.draw("formatting", failing);
			// Model three targeted reruns by marking the same test case; no trial outcome is recorded here.
			for (int rerun = 0; rerun < 3; ++rerun)
				generator.markTargeted(failed);
			failingRun.add(failed);
		}
		requireThat(failingRun, "failingRun").isEqualTo(passingRun);
		requireThat(failing.state(), "failing.state()").isEqualTo(passing.state());
	}

	/**
	 * Verifies that drawing from one stream never moves another stream, and that streams with the same seed but
	 * different kinds do not repeat each other.
	 */
	@Test
	public void streamsAreSeparate()
	{
		// Requirement: R-C4
		DrawStream development = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		DrawStream baseline = DrawStream.create(DrawStream.Kind.BASELINE, SEED);
		DrawStream formal = DrawStream.create(DrawStream.Kind.FORMAL, SEED);
		formal.open();

		List<Long> developmentAlone = take(DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED), 10);
		take(formal, 7);
		take(baseline, 3);
		List<Long> developmentBesideOthers = take(development, 10);

		requireThat(developmentBesideOthers, "developmentBesideOthers").isEqualTo(developmentAlone);
		requireThat(development.state().position(), "development.state().position()").isEqualTo(10L);
		requireThat(formal.state().position(), "formal.state().position()").isEqualTo(7L);
		requireThat(baseline.state().position(), "baseline.state().position()").isEqualTo(3L);
		List<Long> formalValues = take(DrawStream.resume(new DrawStream.State(DrawStream.Kind.FORMAL, SEED, 0, true)),
			10);
		requireThat(formalValues, "formalValues").isNotEqualTo(developmentAlone);
		List<Long> baselineValues = take(DrawStream.create(DrawStream.Kind.BASELINE, SEED), 10);
		requireThat(baselineValues, "baselineValues").isNotEqualTo(developmentAlone);
		requireThat(baselineValues, "baselineValues").isNotEqualTo(formalValues);
	}

	/**
	 * Verifies that a formal stream refuses draws until the campaign opens it after the freeze, and that the
	 * development and baseline streams are open at creation.
	 */
	@Test
	public void formalStreamStartsOnlyAfterOpen()
	{
		// Requirement: R-C4
		DrawStream formal = DrawStream.create(DrawStream.Kind.FORMAL, SEED);
		requireThat(formal.state().open(), "formal.state().open()").isFalse();
		boolean refused = false;
		try
		{
			formal.nextLong();
		}
		catch (IllegalStateException _)
		{
			refused = true;
		}
		requireThat(refused, "refused").isTrue();
		requireThat(formal.state().position(), "formal.state().position()").isEqualTo(0L);

		formal.open();
		formal.nextLong();
		DrawStream resumed = DrawStream.resume(formal.state());

		requireThat(resumed.state().open(), "resumed.state().open()").isTrue();
		requireThat(resumed.state().position(), "resumed.state().position()").isEqualTo(1L);
		requireThat(DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED).state().open(),
			"developmentOpen").isTrue();
		requireThat(DrawStream.create(DrawStream.Kind.BASELINE, SEED).state().open(), "baselineOpen").isTrue();
	}

	/**
	 * Verifies that a resumed closed stream stays closed, so a restart before the freeze cannot start formal draws.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void resumedClosedStreamStaysClosed()
	{
		// Requirement: R-C4
		DrawStream formal = DrawStream.create(DrawStream.Kind.FORMAL, SEED);
		DrawStream.resume(formal.state()).nextLong();
	}

	/**
	 * Verifies that a persisted state rejects a negative position.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNegativePosition()
	{
		new DrawStream.State(DrawStream.Kind.DEVELOPMENT, SEED, -1, true);
	}
}
