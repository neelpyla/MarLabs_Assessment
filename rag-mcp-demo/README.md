# Local RAG + MCP demo

A standalone Java 17 / Spring Boot project demonstrating retrieval-augmented generation (RAG) and an actual Model Context Protocol (MCP) client/server boundary. It does not modify or depend on the application in the repository root.

## Architecture

```text
Client
  └─ POST /answer → Spring Boot API (8089)
       ├─ derives tenant and role from X-Caller-Id
       ├─ calls the separate MCP server's search_policies tool
       │    └─ Spring AI embedding model → Qdrant vector search
       └─ Ollama chat model generates an answer from retrieved quotes

Docker Compose also runs Ollama and Qdrant with persistent named volumes.
```

The MCP server exposes the `search_policies` tool over Streamable HTTP. Its policy corpus is indexed at startup. The search tool restricts retrieval by trusted tenant and role and filters results by the requested effective date. The API returns the source IDs and exact retrieved quotes as citations. An empty search result returns `INSUFFICIENT_EVIDENCE` without calling the chat model.

This is a local demonstration, not production authentication. The sample caller IDs are synthetic. In Compose, the API and MCP server use host networking but bind to `127.0.0.1` so they can reach the local Ollama and Qdrant ports in environments where container-to-container bridge traffic is unavailable. Keep those bindings on loopback; do not expose the MCP server or Qdrant publicly without adding authentication and network controls.

## Stack and images

- Spring Boot 3.5 / Spring AI 1.1; Java 17 for both application modules.
- Ollama in `ollama/ollama:latest`; chat model `qwen2.5:3b`, embedding model `nomic-embed-text`.
- Qdrant in `qdrant/qdrant:latest`.
- Maven 3.9 for building; application runtime image `eclipse-temurin:17-jre`.
- No hosted LLM, API key, or paid vector database. Downloading images/models and running them locally uses disk, memory, compute, and electricity; Docker image tags can change, so pin tags for a repeatable deployment.

## Prerequisites

- Docker Engine with Compose v2.
- Internet access for the initial container-image and model downloads.
- Enough disk and memory for Qdrant, Java, and Ollama. The first model download can take several minutes.

## Start everything

From this folder:

```bash
mvn -DskipTests package
sh ./start-compose.sh
```

```bash
docker compose ps
docker compose logs --tail=100 api mcp-server ollama-models
```

The Maven command builds both Java modules. The launcher keeps retrying the Compose service images and Java runtime image every 10 seconds until all pulls succeed, then builds the application images and starts the stack. The Ollama model service skips models already present and retries missing model downloads every 10 seconds. The API does not start until both models are available and its dependencies are healthy. The Qdrant and Ollama data are stored in named Docker volumes and persist across container restarts.

API: `http://localhost:8089`  
Qdrant dashboard/API: `http://localhost:6333/dashboard`  
Ollama API: `http://localhost:11434`

The API and MCP server are reachable only on host loopback (`127.0.0.1`); the API calls the MCP server at `http://localhost:8090`. To stop the services while keeping stored data:

```bash
docker compose down
```

To remove the locally stored vectors and downloaded models as well:

```bash
docker compose down --volumes
```

## Ask a policy question

```bash
curl -sS http://localhost:8089/answer -H 'Content-Type: application/json' -H 'X-Caller-Id: atlas-employee-01' --data @examples/answer-request.json
````

Available demo caller IDs:

| Caller ID | Tenant | Role |
| --- | --- | --- |
| `atlas-employee-01` | atlas | employee |
| `atlas-contractor-01` | atlas | contractor |
| `boreal-employee-01` | boreal | employee |

Example answer shape:

```json
{
  "status": "ANSWERED",
  "answer": "Atlas employees may be reimbursed up to INR 25,000 per calendar year for approved job-related certifications.",
  "citations": [
    {
      "policyId": "atlas-cert-2026",
      "quote": "Atlas employees may be reimbursed up to INR 25,000 per calendar year for approved job-related certifications. Submit proof of payment and course completion.",
      "effectiveFrom": "2026-01-01",
      "effectiveTo": "2027-01-01"
    }
  ]
}
```

## Run the Java modules without Docker

Build both modules from this folder:

```bash
mvn -DskipTests package
```

Start Qdrant and Ollama separately, pull the models with `ollama pull qwen2.5:3b` and `ollama pull nomic-embed-text`, then run the MCP server:

```bash
mvn -pl mcp-server spring-boot:run
```

In another terminal:

```bash
mvn -pl api spring-boot:run
```

Configuration can be overridden with `OLLAMA_BASE_URL`, `OLLAMA_CHAT_MODEL`, `OLLAMA_EMBEDDING_MODEL`, `QDRANT_HOST`, `QDRANT_GRPC_PORT`, `QDRANT_COLLECTION`, `MCP_SERVER_URL`, and `SERVER_PORT`.
