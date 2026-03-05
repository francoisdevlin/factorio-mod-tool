# Factorio Mod Tool (`fmod`)

A comprehensive CLI and MCP server for Factorio mod development. Validates mod structure, parses Lua, runs lint rules, packages mods, connects to running Factorio instances via RCON, and provides a browser-based GUI for project browsing and diagnostics.

Built in ClojureScript targeting Node.js.

## Quick Start

```bash
# Install dependencies
npm install

# Build everything (CLI, MCP server, HTTP server, GUI)
npm run build

# Create a new mod project
fmod new-project my-awesome-mod
cd my-awesome-mod

# Validate, lint, and pack
fmod all
```

## CLI Commands

### Standalone Commands

| Command | Description |
|---------|-------------|
| `fmod new-project <name>` | Scaffold a new Factorio mod with `src/`, `test/`, `.fmod.json`, and git init |
| `fmod validate [mod-path]` | Validate `info.json` and mod structure (defaults to `.fmod.json` src path) |
| `fmod parse <file.lua>` | Parse a Lua file and print the AST as JSON |
| `fmod parse -` | Parse Lua from stdin |
| `fmod check <files...>` | Check Lua files for syntax errors offline (via luaparse) |
| `fmod check --live <files>` | Check Lua files against a running Factorio instance via RCON |
| `fmod repl` | Interactive Lua REPL connected to a running Factorio instance |
| `fmod doctor` | Detect available capabilities and show install guidance |
| `fmod serve` | Start the HTTP + WebSocket server |
| `fmod ui` | Start the server and open the browser GUI |

### Pipeline Targets

These commands run through the dependency DAG, automatically executing prerequisite targets first:

| Command | Description |
|---------|-------------|
| `fmod check` | Offline Lua syntax check (no file args = pipeline mode) |
| `fmod lint` | Run lint rules (depends on: check) |
| `fmod check-live` | RCON-based Lua validation (depends on: check) |
| `fmod test` | Run mod unit tests via busted (depends on: lint) |
| `fmod pack` | Bundle mod into a distributable `.zip` (depends on: test) |
| `fmod deploy` | Deploy mod to Factorio mods folder (depends on: pack) |
| `fmod test-live` | Run live integration tests (depends on: pack) |
| `fmod all` | Run the full default pipeline |

### Pipeline Options

```bash
fmod pack --only          # Run only pack, skip its dependencies
fmod pack --from lint     # Start the pipeline from lint forward to pack
```

### RCON Options (for `check --live`, `repl`)

```bash
--host <host>       # RCON host (default: localhost)
--port <port>       # RCON port (default: 27015)
--password <pass>   # RCON password (prefer FMOD_RCON_PASSWORD env var)
```

## Pipeline DAG

`fmod` runs targets in dependency order using a topological sort. The default DAG:

```
check ──→ lint ──→ test ──→ pack ──→ deploy
  │                                    │
  └──→ check-live              test-live ←─┘
```

Targets with unmet capability requirements are automatically skipped (e.g., `check-live` is skipped if RCON is not configured). Custom targets and hooks can extend the DAG via `.fmod.json`.

## Configuration: `.fmod.json`

The project configuration file. `fmod` walks up from the current directory to find it.

```json
{
  "name": "my-factorio-mod",
  "version": "0.1.0",
  "structure": {
    "src": "src",
    "test": "test",
    "dist": "dist"
  },
  "rcon": {
    "host": "localhost",
    "port": 27015
  },
  "pack": {
    "exclude": ["*.test.lua", "debug/"]
  },
  "pipeline": {
    "targets": {
      "my-check": { "deps": ["check"], "run": "luacheck src/" },
      "lint": { "extra-deps": ["my-check"] }
    },
    "hooks": {
      "pack": {
        "pre": ["echo 'Packing...'"],
        "post": ["echo 'Done!'"]
      }
    }
  }
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Mod name (used for `info.json` and zip filename) |
| `version` | Yes | Mod version (used in zip filename) |
| `structure.src` | No | Source directory (default: `src`) |
| `structure.test` | No | Test directory (default: `test`) |
| `structure.dist` | No | Output directory for packed zips (default: `dist`) |
| `rcon.host` | No | RCON host (default: `localhost`) |
| `rcon.port` | No | RCON port (default: `27015`) |
| `pack.exclude` | No | Glob patterns to exclude from the zip |
| `pipeline.targets` | No | Custom targets or extra deps for built-in targets |
| `pipeline.hooks` | No | Pre/post shell commands for any target |

The RCON password is **never** stored in `.fmod.json`. Set it via the `FMOD_RCON_PASSWORD` environment variable.

## GUI: `fmod ui`

`fmod ui` starts an HTTP + WebSocket server and opens a browser-based dashboard built with Reagent (React). The GUI provides:

- **File tree** — Browse your mod's project structure
- **File viewer** — View file contents
- **Pipeline toolbar** — Run check, lint, test, and pack with one click
- **Diagnostics panel** — See validation errors and warnings, click to jump to file
- **Console** — Send RCON commands to a running Factorio instance
- **Capability badges** — See which tools are detected at a glance
- **Live updates** — WebSocket pushes diagnostics and pipeline status in real time

```bash
fmod ui                   # Start server on port 3000 and open browser
fmod serve                # Start server only (no browser)
fmod serve --port 8080    # Custom port
```

<!-- Screenshot placeholder: add a screenshot of the GUI here -->

## RCON Setup

To use live features (`check --live`, `repl`, RCON console in GUI), enable RCON in Factorio:

1. Launch Factorio with RCON enabled:
   ```
   factorio --rcon-port 27015 --rcon-password your-secret
   ```

2. Set the password in your environment:
   ```bash
   export FMOD_RCON_PASSWORD=your-secret
   ```

3. Optionally configure host/port in `.fmod.json`:
   ```json
   { "rcon": { "host": "localhost", "port": 27015 } }
   ```

The REPL provides built-in inspection commands:

| Command | Description |
|---------|-------------|
| `.entities` | List entity prototypes |
| `.recipes` | List recipes |
| `.forces` | List forces |
| `.surface` | Inspect current surface |
| `.history` | Show command history |
| `.clear` | Clear history |
| `.exit` | Quit REPL |

## Capability Detection: `fmod doctor`

`fmod doctor` checks for external tools that pipeline targets depend on:

| Capability | Used By | Detection |
|------------|---------|-----------|
| `lua` | General | `which lua` |
| `luarocks` | Test runner | `which luarocks` |
| `busted` | `test` target | `luarocks show busted` |
| `factorio` | Game binary | PATH, `.fmod.json` override, or common install paths |
| `factorio-rcon` | `check-live`, `repl` | `FMOD_RCON_PASSWORD` env var is set |
| `factorio-test` | `test-live` target | factorio-test mod present in mods folder |

Missing capabilities cause dependent pipeline targets to be **skipped** (not failed), so `fmod all` gracefully degrades.

## MCP Server

The MCP server exposes the same functionality to AI coding assistants (e.g., Claude Code) via stdio JSON-RPC:

```bash
node out/server.js
```

Available MCP tools: `validate-mod`, `parse-lua`, `lint-mod`, `pack-mod`, `rcon-exec`, `rcon-inspect`, `repl-eval`, `repl-history`, `repl-inspect`.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | ClojureScript (Node.js target) |
| Build | shadow-cljs |
| Async | Promesa (promise-based) |
| Lua parsing | luaparse (npm) |
| RCON | rcon-client (npm) |
| Zip packaging | archiver (npm) |
| GUI framework | Reagent (ClojureScript React wrapper) |
| GUI transport | WebSocket (ws npm) + HTTP |
| HTTP server | Node.js built-in `http` module |

## Development

```bash
# Watch mode (auto-rebuild on save)
npx shadow-cljs watch cli          # CLI
npx shadow-cljs watch server       # MCP server
npx shadow-cljs watch gui          # GUI (browser)
npx shadow-cljs watch test         # Tests

# Run tests
npx shadow-cljs compile test

# ClojureScript REPL
npx shadow-cljs node-repl
```

## Project Structure

```
src/factorio_mod_tool/
  cli.cljs              CLI entry point and command dispatch
  server.cljs           MCP server (stdio JSON-RPC)
  state.cljs            Shared atoms (mod-state, rcon-connections)
  scaffold.cljs         Project scaffolding (fmod new-project)
  repl.cljs             Interactive REPL engine with history and inspect
  analysis/
    validate.cljs       Mod structure and info.json validation
    lint.cljs           Lint rule engine
    diagnostic.cljs     Diagnostic data helpers (severity, filtering)
  bundle/
    pack.cljs           Zip packaging via archiver
  pipeline/
    dag.cljs            DAG definition, topo sort, execution planning
    runner.cljs         Pipeline executor with hooks and capability checks
    targets.cljs        Built-in target implementations
  rcon/
    client.cljs         RCON connection management (connect, exec, inspect)
  testing/
    harness.cljs        Test runner harness
  util/
    config.cljs         .fmod.json reader with defaults and validation
    capabilities.cljs   External tool detection (doctor)
    fs.cljs             Filesystem promise wrappers
    lua.cljs            Lua parsing via luaparse
    mod.cljs            Mod directory reading helpers
  gui/
    app.cljs            GUI entry point (Reagent mount, WebSocket setup)
    components.cljs     UI components (file tree, diagnostics, console)
    state.cljs          GUI state atoms
    ws.cljs             WebSocket client
  http/
    server.cljs         HTTP + WebSocket server
    routes.cljs         API route table
    static.cljs         Static file serving for GUI assets
```

## Acknowledgments

Shoutout to [FactorioTest](https://github.com/Bilka2/FactorioTest) for pioneering automated testing of Factorio mods.
