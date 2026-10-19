package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * A seeded source of random draws whose seed and position survive a restart.
 * <p>
 * Development, formal and baseline draws each come from their own stream, so that one stream's use never moves
 * another's position. A stream has no input from candidate identity or outcomes: its values depend only on its kind,
 * seed and position.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class DrawStream implements RandomGenerator
{
	/**
	 * The purpose of a stream.
	 */
	public enum Kind
	{
		/**
		 * Randomly sampled trials for the prompt-system version being developed.
		 */
		DEVELOPMENT(0x5DEECE66DA3C4F17L),
		/**
		 * Trials used to certify a pass rate, after the prompt-system version and measurement procedure are fixed.
		 */
		FORMAL(0x2545F4914F6CDD1DL),
		/**
		 * Trials that measure the pass rate of the currently retained prompt-system version for comparison.
		 */
		BASELINE(0x6A09E667F3BCC909L);

		private final long salt;

		/**
		 * Creates a kind.
		 *
		 * @param salt the value that keeps streams of different kinds apart when they share a seed
		 */
		Kind(long salt)
		{
			this.salt = salt;
		}

		/**
		 * Returns the part of the contract whose test cases a stream of this kind draws.
		 *
		 * @return {@link Part#DEVELOPMENT} for development draws, {@link Part#HELD_OUT} for formal and baseline draws
		 */
		public Part part()
		{
			return switch (this)
			{
				case DEVELOPMENT -> Part.DEVELOPMENT;
				case FORMAL, BASELINE -> Part.HELD_OUT;
			};
		}
	}

	/**
	 * The values needed to resume a stream.
	 *
	 * @param kind the purpose of the stream
	 * @param seed the seed that the stream was created with
	 * @param position the number of random values that the stream has produced
	 * @param open {@code true} if draws are allowed
	 */
	public record State(Kind kind, long seed, long position, boolean open)
	{
		/**
		 * Creates a stream state.
		 *
		 * @param kind the purpose of the stream
		 * @param seed the seed that the stream was created with
		 * @param position the number of random values that the stream has produced
		 * @param open {@code true} if draws are allowed
		 * @throws NullPointerException if {@code kind} is null
		 * @throws IllegalArgumentException if {@code position} is negative
		 */
		public State
		{
			requireThat(kind, "kind").isNotNull();
			requireThat(position, "position").isNotNegative();
		}
	}

	/**
	 * Creates a stream at position 0.
	 *
	 * @param kind the purpose of the stream
	 * @param seed the seed
	 * @return the new stream, which is closed if {@code kind} is {@link Kind#FORMAL}
	 * @throws NullPointerException if {@code kind} is null
	 */
	public static DrawStream create(Kind kind, long seed)
	{
		requireThat(kind, "kind").isNotNull();
		return new DrawStream(new State(kind, seed, 0, kind != Kind.FORMAL));
	}

	/**
	 * Resumes a stream at the position that its state records.
	 *
	 * @param state the persisted state
	 * @return a stream that continues with the value that followed the recorded position
	 * @throws NullPointerException if {@code state} is null
	 */
	public static DrawStream resume(State state)
	{
		requireThat(state, "state").isNotNull();
		return new DrawStream(state);
	}

	private final Kind kind;
	private final long seed;
	private final SplittableRandom random;
	private long position;
	private boolean open;

	/**
	 * Creates a stream and advances it to the recorded position.
	 *
	 * @param state the state to start from
	 */
	private DrawStream(State state)
	{
		kind = state.kind();
		seed = state.seed();
		// Hashing the salted seed first keeps streams whose seeds differ by a small amount, such as 1 and 2, from
		// producing shifted copies of each other.
		random = new SplittableRandom(new SplittableRandom(seed ^ kind.salt).nextLong());
		for (long skipped = 0; skipped < state.position(); ++skipped)
			random.nextLong();
		position = state.position();
		open = state.open();
	}

	/**
	 * Returns the values to persist.
	 *
	 * @return the kind, seed, position and whether draws are allowed
	 */
	public State state()
	{
		synchronized (this)
		{
			return new State(kind, seed, position, open);
		}
	}

	/**
	 * Allows draws from a closed stream, which a campaign does when it freezes the candidate and procedure.
	 */
	public void open()
	{
		synchronized (this)
		{
			open = true;
		}
	}

	/**
	 * Produces the next 64 random bits and advances the position by one.
	 *
	 * @return the next value
	 * @throws IllegalStateException if the stream is closed. Callers can check {@link State#open()} first.
	 */
	@Override
	public long nextLong()
	{
		synchronized (this)
		{
			if (!open)
			{
				throw new IllegalStateException("The " + kind + " stream is closed. A formal stream opens only after " +
					"the candidate and procedure are frozen.");
			}
			++position;
			return random.nextLong();
		}
	}

	/**
	 * Produces a uniform value in [0, 1) from one {@link #nextLong()}, so that every draw advances the position by
	 * exactly one.
	 *
	 * @return the next value
	 * @throws IllegalStateException if the stream is closed. Callers can check {@link State#open()} first.
	 */
	@Override
	public double nextDouble()
	{
		return (nextLong() >>> 11) * 0x1.0p-53;
	}
}
