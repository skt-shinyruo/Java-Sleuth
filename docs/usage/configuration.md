# Configuration

Java-Sleuth resolves configuration in explicit layers:

1. Defaults from `sleuth-default.properties`
2. External file from `-Dsleuth.config.file=<path>` or `sleuth.properties`
3. Startup system-property overrides from `-Dsleuth.<key>=<value>`
4. Runtime overrides from the `config set/remove/clear` command

Runtime overrides have the highest priority. `-Dsleuth.*` values are captured when `ProductionConfig`
is created or explicitly reloaded; `getString(...)`, typed reads, and `snapshot()` all use that same
captured view. Changing a `sleuth.*` system property later in the target JVM no longer changes existing
configuration reads implicitly.

Attach arguments are still accepted for existing keys, including `server.port` and
`protocol.streaming.enabled`. They are applied as temporary `sleuth.*` system properties before the
agent creates its attach-scope config, then rolled back on startup failure, detach, or shutdown. The
created config keeps the captured attach values, so rollback does not change what the running agent
sees.

## Migration Notes

- To change a running agent, use `config set <key> <value>` instead of mutating target JVM system
  properties.
- If you intentionally change `-Dsleuth.*` properties after startup, call the config reload path before
  expecting file/startup layers to change. Runtime overrides are preserved across reload.
- `snapshot()` is an effective immutable snapshot with the same priority semantics as `getString(...)`.
  It is not a live view of global `System` properties.
- Forbidden removed keys such as `security.mode`, `security.hmac.*`, and legacy `protocol.*` keys are
  still rejected from config files or `-Dsleuth.*` under the default strict policy.
