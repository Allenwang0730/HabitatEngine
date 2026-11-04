# Habitat MCP stdio bridge (scripts/habitat_mcp_stdio.mjs). The MCP server itself runs on the phone;
# this image forwards stdio JSON-RPC to it. Without a reachable phone it still answers initialize / ping /
# tools/list from scripts/habitat_mcp_tools.json, and tools/call returns an error.
#
#   docker build -t habitat-mcp .
#   docker run -i --rm -e HABITAT_TOKEN=<token> -e HABITAT_URL=http://host.docker.internal:8931/mcp habitat-mcp
FROM node:22-alpine
WORKDIR /app
COPY scripts/habitat_mcp_stdio.mjs scripts/habitat_mcp_tools.json ./
USER node
ENTRYPOINT ["node", "habitat_mcp_stdio.mjs"]
