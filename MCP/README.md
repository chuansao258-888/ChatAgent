# MCP Test Strategy

MCP protocol tests use the repository-owned deterministic fixture at
`ui/e2e/fixtures/mcp-protocol-fixture.mjs`. It is pinned with the UI dependencies and
does not install a separate Python environment or execute an unpinned third-party server.

Local smoke test:

1. Run `npm.cmd run e2e:mcp-fixture` from `ui/`.
2. Require `http://127.0.0.1:8090/__mcp-fixture/health` to return healthy.
3. Start the rebuilt backend in the local/test profile.
4. Run `npm.cmd run e2e:headed -- --grep '@mcp'`.
5. Stop only the fixture and backend processes started for the test.

The former unpinned weather-server scripts and repository-local Python virtual environment
are retired. Production MCP endpoints are configured through the authenticated admin API.
