`CAT_TEMP` is `<!-- cat:project-root -->/.cat/sessions/<!-- cat:session-id -->/temp`, this session's temporary directory, and
the directory exists. Wherever a rule or skill writes `${CAT_TEMP}`, write that directory in its place; never expand
`${CAT_TEMP}` from the environment. Create a temporary directory there with
`mktemp -d -p ${CAT_TEMP} <workflow-name>-XXXXXX`, which prints the new directory's absolute path; use that output as
the directory. Remove it with `rm -rf -- <directory>` once nothing needs it, or move it with
`mv -- <directory> <new-path>` to hand it to a later owner.
