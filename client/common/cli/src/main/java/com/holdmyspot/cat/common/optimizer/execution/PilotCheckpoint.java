package com.holdmyspot.cat.common.optimizer.execution;

import java.io.IOException;
import java.util.Map;

/**
 * Keeps the progress of a guard pilot while it runs, so that a process that ends before the pilot does, such as one
 * that a failed host turn or a killed command stops, loses only the trials in flight.
 */
@FunctionalInterface
public interface PilotCheckpoint
{
	/**
	 * Keeps the pilots as they stand after a trial was decided. A pilot that has not run every trial holds the trials
	 * that it decided and says why it is unfinished, so that a later run keeps them.
	 *
	 * @param pilots the pilot of each prompt test that has one, by prompt test, in contract order
	 * @throws NullPointerException if {@code pilots} is null
	 * @throws IOException          if the pilots cannot be kept
	 */
	void save(Map<String, PilotResult> pilots) throws IOException;
}
