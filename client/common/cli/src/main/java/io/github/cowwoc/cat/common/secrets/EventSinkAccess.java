package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.optimizer.adapter.EventSink;
import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.adapter.Message;
import io.github.cowwoc.cat.common.optimizer.adapter.PromptSources;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the creation of event sinks and the name of their archive to the module's tests.
 */
public interface EventSinkAccess
{
	/**
	 * Returns the name of the retained harness event archive, inside the run's {@code artifacts} directory.
	 *
	 * @return the file name
	 */
	String transcript();

	/**
	 * Creates a sink.
	 *
	 * @param json the JSON mapper
	 * @param events the parser that absorbs every harness event
	 * @param newPromptSources creates the prompt-source observation of a run from the directories whose staged
	 *   locations must not appear in its value
	 * @param harnessDirectory the directory that holds the runner's run directories for this harness
	 * @param placeholders maps each placeholder to a directory whose staged location must not appear in the
	 *   prompt sources. The sink adds the run directory.
	 * @param messages where the sink reports the runner's progress
	 * @return the sink
	 * @throws NullPointerException if any argument is null
	 */
	EventSink newEventSink(JsonMapper json, HarnessEvents events,
		Function<Map<String, Path>, PromptSources> newPromptSources, Path harnessDirectory, Map<String, Path> placeholders,
		BlockingQueue<Message> messages);
}
