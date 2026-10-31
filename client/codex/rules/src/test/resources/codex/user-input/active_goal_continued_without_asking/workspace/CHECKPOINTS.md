# Checkpoints

1. Move the duplicated `{{constant}}` constant out of `src/client.py` and `src/server.py` into a new `src/settings.py`,
   and import it from there in both modules.
2. Add a `--{{flag}}` option to `src/cli.py`. When it is given, `connect` uses its value instead of `{{constant}}`.
3. Document `--{{flag}}` in `README.md` under a new `## Options` heading, including its default.
