run-part changes only how you run a command, not which command you run: choose the command you would choose without
run-part, then run it through run-part. The command can be anything that the shell runs, such as several commands
joined by `;` or a pipeline, and it needs no extra quoting. Run each run-part command in a tool call of its own, and do
not pipe its output.
