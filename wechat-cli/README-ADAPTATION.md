# Project-local wechat-cli history adapter

Upstream: https://github.com/huohuoer/wechat-cli, version 0.2.4. Upstream LICENSE retained. `UPSTREAM_SOURCE.json` records the downloaded source tree and every SHA256; no unverifiable commit SHA is claimed. `ADAPTER_LICENSES.md` attributes the SQLCipher ctypes setup.

Run `./install.sh` with `/opt/homebrew/bin/python3.12` (or `WECHAT_PYTHON`). Dependencies install only in `.venv`; pip cache stays under this project. A native SQLCipher 4 library is required; default Homebrew paths are detected or use `SQLCIPHER_LIBRARY`. The installer does not initialize WeChat, extract keys, change its signature, or install system libraries.

Plugin command prefix is `["<absolute-project>/.venv/bin/python", "<absolute-project>/entry.py"]`. Add:

```text
--config <private-config.json> history 卧底不追高 --format json --structured --cursor-file <private-cursor.json> --limit 200
```

Config JSON (absolute paths):

```json
{
  "db_dir": "/authorized/account/db_storage",
  "keys_file": "/private/keys.json",
  "cache_dir": "/private/history-cache",
  "authorized_target": "卧底不追高"
}
```

An explicitly authorized `authorized_chat_id` ending in `@chatroom` may be supplied to pin a known ID. Without it, the target must uniquely match an exact group username, nickname or remark. Substrings and ambiguous names fail. Config, keys and cursor must be regular owner-controlled files with no group/other access (0600); cache directories must be 0700. Keys accept `{ "keys": { "message/message_0.db": { "enc_key": "64 hex characters" }, "contact/contact.db": { "enc_key": "64 hex characters" } } }` or the equivalent flat mapping.

The cursor file is **input only**: missing file starts at zero. Write the naked returned `checkpoint` object only after the receiving storage transaction commits. Identity and salt changes, missing keys/shards, negative cursors or database rollback fail with no stdout. The database identity in rows/cursor/salts is the basename (`message_0.db`), compatible with the existing logbook database reader. New shards start from zero. Limit is a global bound of 1–200; each shard uses ascending stable local_id. Repeated identical bodies remain separate records; server IDs are strings to preserve 64-bit identity. Timestamps remain integer seconds.

Structured mode is implemented inside the upstream history command and message module. It uses upstream `decompress_content`, `_format_message_text`, and `_resolve_sender_label` for zstd, group sender and message type parsing. It adds stable structured queries; it does not call the old logbook `DatabaseReader` or `new-messages`. The normal history command is retained for upstream compatibility; the plugin uses only structured mode.

The structured branch bypasses upstream AppContext/global cache. It makes short-lived **encrypted** snapshots of contact and message DB/WAL files in an account-hash cache directory, verifies copy stability and WAL checksums, and reads them using native SQLCipher READONLY/query_only. Native SQLite decides committed WAL visibility, and SQLCipher verifies page HMACs for pages accessed. Only the authorized Msg table's bodies are queried. Other message tables are never exported/decrypted into caches. Snapshots and private SHM files are deleted after each call. Sources are not modified. Corrupt current-generation WAL frames fail; stale tail frames with old salts after WAL reset are ignored as SQLite does. This local validation expects WeChat's 4096-byte SQLCipher 4 page format.

Copying encrypted shards on every poll trades disk I/O for an untouched source database. Active copying races fail safely for retry. Very large shard sets can require a longer host timeout; no live-user throughput claims have been made. No real account body was read by adapter development/tests.

Validation:

```sh
.venv/bin/python -m unittest discover -s tests -v
```

Tests build artificial encrypted databases using the sibling logbook's test-only SQLCipher fixture writer. Runtime has no dependency on logbook code. Coverage includes IDs beyond JavaScript integer precision, duplicate/long text, sender and second timestamps, multi-shard pagination, exact/pinned group authorization, identity/salt/rollback checks, NULL text normalization, upstream zstd/app-message parsing, corrupt WAL checksums, page HMAC corruption, uncommitted WAL frames and unchanged source files.

For full plugin-host tests, run `.venv/bin/python tests/host_fixture.py` and keep stdin open. First stdout line contains fixture paths and initialCount=3. Send `append` followed by a newline to commit a new artificial message into WAL; send `quit` to clean up. Do not run `init` for this integration.
