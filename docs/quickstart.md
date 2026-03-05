# Quickstart: Factorio Mod Tool

Get from zero to checking your mod files in under 5 minutes.

## Prerequisites

- [Node.js](https://nodejs.org/) (18+)
- npm (comes with Node.js)

Verify your setup:

```bash
node --version
npm --version
```

## 1. Install the CLI

Clone the repository, build, and link the `fmod` command:

```bash
git clone https://github.com/francoisdevlin/factorio-mod-tool.git
cd factorio-mod-tool
npm install
npm run build:cli
npm link
```

Verify the install:

```bash
fmod --help
```

You should see the list of available commands. If `fmod` is not found, you can
always use `node out/cli.js` instead.

## 2. Check Lua Syntax (Offline)

The `check` command validates Lua files for syntax errors using a built-in
parser. No Factorio server needed.

```bash
fmod check data.lua control.lua
```

**Example — valid files:**

```
Checking (offline via luaparse):

  ✓ data.lua
  ✓ control.lua

  2 passed, 0 failed
```

**Example — file with a syntax error:**

```
Checking (offline via luaparse):

  ✓ data.lua
  ✗ control.lua: [1:8] '=' expected near 'oops'

  1 passed, 1 failed
```

Exit code is `0` when all files pass, `1` when any file fails.

## 3. Validate a Mod Directory

The `validate` command checks a Factorio mod directory for structural
correctness — required files, entry points, load order, and info.json fields.

```bash
fmod validate ./my-mod
```

A typical Factorio mod directory looks like:

```
my-mod/
├── info.json          # Mod metadata (name, version, dependencies)
├── data.lua           # Prototype definitions
├── control.lua        # Runtime event handlers
├── settings.lua       # Mod settings (optional)
└── locale/
    └── en/
        └── locale.cfg
```

**Example — valid mod:**

```
Validating: ./my-mod

  ✓ No issues found
```

**Example — mod with issues:**

```
Validating: ./my-mod

  ✗ Required file missing: info.json
    → Create info.json in the mod root directory
  ⚠ Mod has no data.lua or control.lua — it won't affect the game
    → Create data.lua (for prototypes) or control.lua (for runtime scripts)

  1 error(s), 1 warning(s), 0 info
```

## 4. Setting Up Factorio for RCON

RCON (Remote Console) lets `fmod` send Lua code to a running Factorio instance
for live validation. This catches errors that offline parsing misses — like
references to undefined globals or Factorio-specific API issues.

### Option A: Headless Server (Recommended for CI/Automation)

Start a dedicated Factorio headless server with RCON enabled:

```bash
./factorio --start-server save.zip \
  --rcon-port 27015 \
  --rcon-password secret
```

### Option B: Regular Game with RCON

Add RCON flags when launching Factorio for play:

```bash
./factorio --rcon-port 27015 --rcon-password secret
```

Or edit your Factorio launch options in Steam:
`--rcon-port 27015 --rcon-password secret`

### Verify RCON is Working

Once Factorio is running with RCON, test the connection:

```bash
fmod check --live --password secret data.lua
```

If you see `Checking (live via RCON):` followed by results, RCON is working.
If you see `RCON connection failed`, double-check that:

- Factorio is running and fully loaded (past the main menu / map loaded)
- The port (`27015`) and password (`secret`) match your launch flags
- No firewall is blocking the port

## 5. Live Syntax Checking via RCON

With RCON connected, `fmod check --live` sends each file's source to Factorio's
built-in `load()` function for validation:

```bash
fmod check --live --password secret data.lua control.lua
```

**Example — valid files:**

```
Checking (live via RCON):

  ✓ data.lua
  ✓ control.lua

  2 passed, 0 failed
```

**Example — file with an error caught by Factorio:**

```
Checking (live via RCON):

  ✓ data.lua
  ✗ control.lua: [string "..."]:3: unexpected symbol near 'funtion'

  1 passed, 1 failed
```

### RCON Connection Options

| Flag | Default | Description |
|------|---------|-------------|
| `--host <host>` | `localhost` | RCON host address |
| `--port <port>` | `27015` | RCON port |
| `--password <pass>` | *(none)* | RCON password |

**Example with all options:**

```bash
fmod check --live --host 192.168.1.10 --port 27015 --password mypass *.lua
```

## 6. Copy-Paste Examples

Try these snippets to see both passing and failing results.

### Valid Lua — Factorio control script

Save as `test_control.lua`:

```lua
-- control.lua: Register event handlers for a Factorio mod
script.on_event(defines.events.on_player_created, function(event)
  local player = game.get_player(event.player_index)
  if player then
    player.print("Welcome to the server!")
    player.insert({ name = "iron-plate", count = 50 })
  end
end)
```

```bash
fmod check test_control.lua
# ✓ test_control.lua
```

### Valid Lua — Factorio data prototype

Save as `test_data.lua`:

```lua
-- data.lua: Define a custom item
data:extend({
  {
    type = "item",
    name = "super-fuel",
    icon = "__my-mod__/graphics/icons/super-fuel.png",
    icon_size = 64,
    stack_size = 50,
    fuel_category = "chemical",
    fuel_value = "100MJ",
  },
})
```

```bash
fmod check test_data.lua
# ✓ test_data.lua
```

### Invalid Lua — Missing `end` keyword

Save as `test_broken.lua`:

```lua
-- BROKEN: function body never closed
function setup_player(player)
  player.print("Setting up...")
  if player.force.name == "player" then
    player.insert({ name = "pistol", count = 1 })
  -- missing 'end' for the if block
-- missing 'end' for the function
```

```bash
fmod check test_broken.lua
# ✗ test_broken.lua: [8:1] '<eof>' expected near '<eof>'
```

### Invalid Lua — Typo in keyword

Save as `test_typo.lua`:

```lua
-- BROKEN: 'funtion' is not a keyword
funtion on_init()
  game.print("Hello")
end
```

```bash
fmod check test_typo.lua
# ✗ test_typo.lua: [2:9] '=' expected near 'on_init'
```

### Offline vs Live comparison

```bash
# Offline — catches syntax errors only
fmod check test_broken.lua test_typo.lua

# Live — catches syntax errors AND Factorio-specific issues
fmod check --live --password secret test_broken.lua test_typo.lua
```

## Quick Reference

| Task | Command |
|------|---------|
| Check Lua syntax (offline) | `fmod check *.lua` |
| Check Lua syntax (live) | `fmod check --live --password secret *.lua` |
| Validate mod structure | `fmod validate ./my-mod` |
| Parse Lua to AST | `fmod parse data.lua` |
| Parse from stdin | `echo 'local x = 1' \| fmod parse -` |
| Create new mod project | `fmod new-project my-mod` |
| Show help | `fmod --help` |
