package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A supplied trial example that a check must accept or reject. The agent that derives the proposal writes these
 * examples, called controls, to test its proposed checks (design &sect;6.4). A control records everything that a
 * check can observe, including its tool calls.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param replies the final reply to each turn, in turn order
 * @param files the files in the project after the trial
 * @param calls the tool calls that the trial made, in order
 * @param choices the label of the variation value that the trial drew for each dimension that the check names, by
 *   dimension. It iterates in dimension order, so that the contract's JSON form and its identity do not depend on
 *   the order in which the deriver listed them.
 */
public record ControlEvidence(List<String> replies, List<EvidenceFile> files, List<ToolCall> calls,
	Map<String, String> choices) implements CheckEvidence
{
	/**
	 * Creates the evidence of a control.
	 *
	 * @param replies the final reply to each turn, in turn order
	 * @param files the files in the project after the trial
	 * @param calls the tool calls that the trial made, in order
	 * @param choices the label of the variation value that the trial drew for each dimension that the check names,
	 *   by dimension
	 * @throws NullPointerException if any argument is null or any list or {@code choices} contains a null
	 * @throws IllegalArgumentException if two files share a path, or a dimension or label is blank
	 */
	public ControlEvidence
	{
		requireThat(replies, "replies").isNotNull();
		requireThat(files, "files").isNotNull();
		requireThat(calls, "calls").isNotNull();
		requireThat(choices, "choices").isNotNull();
		replies = List.copyOf(replies);
		files = List.copyOf(files);
		calls = List.copyOf(calls);
		choices = Collections.unmodifiableSortedMap(new TreeMap<>(choices));
		Set<String> paths = new HashSet<>();
		for (EvidenceFile file : files)
		{
			if (!paths.add(file.path()))
				throw new IllegalArgumentException("The control lists the file '" + file.path() + "' twice");
		}
		for (Map.Entry<String, String> choice : choices.entrySet())
		{
			requireThat(choice.getKey(), "dimension").isNotBlank();
			requireThat(choice.getValue(), "label").isNotBlank();
		}
	}

	@Override
	public boolean exists(String path)
	{
		requireThat(path, "path").isNotNull();
		String directory = path + "/";
		return files.stream().anyMatch(file -> file.path().equals(path) || file.path().startsWith(directory));
	}

	@Override
	public Optional<String> content(String path)
	{
		requireThat(path, "path").isNotNull();
		return files.stream().filter(file -> file.path().equals(path)).map(EvidenceFile::content).findFirst();
	}

	/**
	 * Returns the tool calls recorded by the control, including an empty list when it called no tools.
	 *
	 * @return the recorded calls, always present
	 */
	@Override
	public Optional<List<ToolCall>> toolCalls()
	{
		return Optional.of(calls);
	}
}
