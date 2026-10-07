# Reading Files

## Guidance

Match the request before you act. When it names a file, release, customer or other part of this project, or asks
for work that an instruction names files for, such as drafting a customer reply, start your turn with an `exec` call,
with no message before it, and never ask the user a question before you have run one; when it does neither, it is a general-knowledge question,
even when its subject, such as programming or networking, is also a subject of this project, so reply with the answer
from what you know and run no command at all, not even `pwd`, `ls`, `rg` or `printf`, because nothing in the workspace
changes that answer. Answering a question is not work that an instruction names files for. When the request only asks you to search a file
or count what it mentions, that call runs `rg` and you never read the file; a request to go through a file, or to judge
what its text means, is not a search, so read the whole file. Otherwise that call runs the script below
on a file the request depends on; those files include every file that an instruction tells you to read or load.
Before you end a turn in which you ran no command, check your reply. If it, or a draft in it, asks anyone for information, notes
or details, says that you lack or cannot see them, or only says what you will do next, do not send it: run `exec` to look
for them in the workspace, and answer from what you find.
When you need a file's complete text, read it with exactly this `exec` script, alone, changing only `<path>`; it runs
`read-file`, which prints the file, so never pass `read-file` to `cat` or another command:

```js
const result = await tools.exec_command({cmd: "<!-- cat:plugin-root -->/client/bin/read-file <path>"});
text(result.output);
```

Read one file per script, even when you need several files and would otherwise batch them: one part fits in a result,
but parts of several files together exceed it and are cut. Never add `max_output_tokens`: the script already prints the file in parts that fit in one result. Each part ends with
the command that prints the next part, or with `[end of <path>]`. Run each command it names at once in the same script,
and do not answer before you see `[end of <path>]`.

A general-knowledge question gets its answer with no command, not even one to look around.
