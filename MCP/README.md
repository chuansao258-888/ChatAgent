# Local MCP Sandbox

This folder contains local MCP server helpers used to smoke-test ChatAgent's MCP client integration without depending on a separately managed remote deployment.

Current contents:

1. `weather-server/`
   Local setup for [`mcp_weather_server`](https://github.com/isdaniel/mcp_weather_server), a weather MCP server that supports both SSE and streamable HTTP.

Recommended first smoke test:

1. Open PowerShell in this repository root.
2. Run `.\MCP\weather-server\install.ps1`
3. Run `.\MCP\weather-server\start-http.ps1`
4. In ChatAgent admin, open `/admin/mcp`
5. Add a server with:
   - `slug`: `weather`
   - `name`: `Local Weather MCP`
   - `protocol`: `HTTP`
   - `authType`: `NONE`
   - `endpointUrl`: `http://localhost:8090/mcp`
6. Click `Test`
7. Click `Sync`
