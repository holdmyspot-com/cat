# Remote sessions on CI runners

Start a pipeline with the variable `REMOTE_SESSION_PUBLIC_KEY` set to your public SSH key to get a shell on the runner
in the job's workspace. The job prints the SSH command between `=== REMOTE SESSION ===` markers; follow the live log with
`glab ci trace <job>`. Jobs that extend `.remote-session` wait for `.remote-session/client-ready` before running their
script, and the runner stays up until you exit the shell. Connect with `ci/upterm_known_hosts`.
