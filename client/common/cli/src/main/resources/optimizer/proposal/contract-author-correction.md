CAT refused the proposal in `{{proposal_file}}` and the draft directory `{{draft_directory}}` because of these
problems:

{{problems}}

Each problem starts with the file and line, or the JSON path of the proposal file, that it concerns, such as
`$.tests[2].assertions[0]`. Correct every problem by editing only the files and values it concerns, and keep the rest
unless a correction requires changing it. Read only the parts of a file that you need. Then run
`{{launcher}} contract check {{context_file}}` and correct every problem that it prints. CAT reads the proposal only
from these files, so do not repeat it in your reply.
