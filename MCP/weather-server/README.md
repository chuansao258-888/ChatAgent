# Weather MCP Server

This folder configures a local copy of [`mcp_weather_server`](https://github.com/isdaniel/mcp_weather_server) for ChatAgent smoke tests.

## What This Gives You

1. A local MCP server that can run in `HTTP` mode for ChatAgent's `/admin/mcp` page.
2. Optional `SSE` mode if you want to test ChatAgent's SSE path later.
3. A Windows-friendly setup that uses a local `.venv` under `MCP/`.

## One-Time Setup

From the repository root:

```powershell
.\MCP\weather-server\install.ps1
```

If your local PowerShell profile has startup errors, use the wrapper instead:

```cmd
MCP\weather-server\install.cmd
```

This script:

1. Creates `MCP/.venv` if it does not exist.
2. Upgrades `pip`.
3. Installs the Python packages listed in `requirements.txt`.

## Start In HTTP Mode

From the repository root:

```powershell
.\MCP\weather-server\start-http.ps1
```

Profile-safe wrapper:

```cmd
MCP\weather-server\start-http.cmd
```

Default endpoint:

```text
http://localhost:8090/mcp
```

### ChatAgent Admin Form Values

Use these values in `/admin/mcp`:

1. `slug`: `weather`
2. `name`: `Local Weather MCP`
3. `description`: `Local smoke-test weather server`
4. `protocol`: `HTTP`
5. `authType`: `NONE`
6. `endpointUrl`: `http://localhost:8090/mcp`
7. `credentials`: leave empty

Then:

1. Click `Test`
2. Click `Sync`

## Verify The Local MCP Server Before Using ChatAgent

After the server is running, you can validate it independently:

```powershell
.\MCP\weather-server\smoke-test-http.ps1
```

Profile-safe wrapper:

```cmd
MCP\weather-server\smoke-test-http.cmd
```

This script performs:

1. `initialize`
2. `tools/list`

against the local streamable HTTP endpoint and prints the raw responses.

## Start In SSE Mode

From the repository root:

```powershell
.\MCP\weather-server\start-sse.ps1
```

Profile-safe wrapper:

```cmd
MCP\weather-server\start-sse.cmd
```

Default endpoints:

1. `GET http://localhost:8090/sse`
2. `POST http://localhost:8090/messages/`

If you want to test ChatAgent's SSE path instead of HTTP, use:

1. `protocol`: `SSE`
2. `endpointUrl`: `http://localhost:8090/sse`

## Optional Custom Port

```powershell
.\MCP\weather-server\start-http.ps1 -Port 8091
```

Then update ChatAgent's endpoint URL to:

```text
http://localhost:8091/mcp
```

## Troubleshooting

1. If PowerShell blocks script execution, run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

2. If the install script says `python` is missing, install Python 3.10+ and ensure it is on `PATH`.
3. If ChatAgent `Test` fails, confirm the weather server is still running in the PowerShell window.
4. If port `8090` is already in use, rerun the script with another free port.
