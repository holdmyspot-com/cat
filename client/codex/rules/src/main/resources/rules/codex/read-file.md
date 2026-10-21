# Reading Command Output

`${CAT_PLUGIN_ROOT}` stands for the CAT plugin's root directory, `<!-- cat:plugin-root -->`.
The shell does not define it, so wherever this guide uses `${CAT_PLUGIN_ROOT}`, write that path in its place.

<!-- cat:include platform/{platform}/read-file/run-part.md -->

<!-- cat:include platform/{platform}/read-file/command.md -->

run-part saves and segments the output into multiple parts. When you invoke it, it will return the first part of the
output followed by an instruction in square brackets on the last line that says how many of the output's bytes remain,
in at least how many more parts, and explains how to fetch the rest of the output. Each later part ends the same way,
until a part's last line starts with `[End of `; that part is the last one.

Read every part of guidance that you must follow, and of a document or command output that the user asked you to read
in full. Read other output while its parts serve your task. When the parts that you have read show that the rest will
not, such as a search whose matches are mostly generated, minified or dependency files, or a long log whose failure a
search can find, stop and run a narrower command instead: a search limited to the files that can hold the answer, with
long lines cut; a query that extracts only the fields that you need from JSON; or a search of the saved output, whose
path the instruction names, for the lines that report the failure, then the instruction's command with a matching line
number in place of its position, to read on from that line. Stop only because of what the parts that you have read
show: the remaining size tells you how much reading the rest takes, and is not a reason to stop on its own.

The last line of every part is that instruction, even when the line before it ends with `\` or otherwise looks
unfinished: the instruction is never part of the command's output.

Give each tool call only the command, for example `tools.exec_command({cmd: "<the run-part command>"})`, and no
`max_output_tokens`. run-part already makes each part small enough for Codex to show whole; a `max_output_tokens`
value makes Codex silently remove lines from the middle of the part, and the lines you lose can hold a rule you must
follow or the answer you need.
