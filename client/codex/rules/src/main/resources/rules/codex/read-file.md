# Reading Command Output

`${CAT_PLUGIN_ROOT}` stands for the CAT plugin's root directory, `<!-- cat:plugin-root -->`.
The shell does not define it, so wherever this guide uses `${CAT_PLUGIN_ROOT}`, write that path in its place.

<!-- cat:include platform/{platform}/read-file/run-part.md -->

<!-- cat:include platform/{platform}/read-file/command.md -->

run-part saves and segments the output into multiple parts. When you invoke it, it will return the first part of the
output followed by an instruction in square brackets on the last line that explain how to fetch the rest of the
output. Each later part ends the same way, so keep running the instruction on the last line of each part until a
part's last line starts with `[End of `; that part is the last one, and you may continue your work.

The last line of every part is that instruction, even when the line before it ends with `\` or otherwise looks
unfinished: the instruction is never part of the command's output.

Give each tool call only the command, for example `tools.exec_command({cmd: "<the run-part command>"})`, and no
`max_output_tokens`. run-part already makes each part small enough for Codex to show whole; a `max_output_tokens`
value makes Codex silently remove lines from the middle of the part, and the lines you lose can hold a rule you must
follow or the answer you need.
