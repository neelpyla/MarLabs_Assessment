# Employee Support Assistant

A local employee policy and reimbursement triage application built with Java 17 / Spring Boot and a Python evidence service. Spring owns the public API, caller lookup, validation, and batch assembly. Python extracts text, applies tenant/role/date policy filters, retrieves evidence, and returns deterministic answers. No hosted model, API key, database, OCR, or approval/payment action is used.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the end-to-end answer and batch flows, technology choices, Mermaid diagrams, and the distinction between the current deterministic evidence service and a future vector-search RAG design.

## Prerequisites

- JDK 17 or newer and Maven 3.8+
- Python 3.10+ (3.11 recommended)

## Run locally

From the repository root, create a Python environment and install its single runtime dependency:

```bash
python -m venv .venv
source .venv/bin/activate
python -m pip install -r python_service/requirements.txt
python -m python_service.app
```

In another terminal, start Spring Boot:

```bash
mvn spring-boot:run
```

The public API is at `http://localhost:8089`; the Python service listens on port `8081`. Configure `SERVER_PORT`, `PYTHON_PORT`, `PYTHON_HOST`, or `PYTHON_SERVICE_URL` to change the defaults. `python.service.connect-timeout` and `python.service.read-timeout` in `src/main/resources/application.properties` bound downstream calls. There are no automatic retries.

Synthetic caller identities:

| `X-Caller-Id` | Tenant | Role |
| --- | --- | --- |
| `atlas-employee-01` | Atlas | employee |
| `atlas-contractor-01` | Atlas | contractor |
| `boreal-employee-01` | Boreal | employee |

The header stands in for authentication only for this exercise. Tenant and role are looked up by Spring; values in a question or uploaded document never override them.

## API

### Policy question
Success scenario:

```bash
curl -sS http://localhost:8089/answer \
  -H 'Content-Type: application/json' \
  -H 'X-Caller-Id: atlas-employee-01' \
  --data @examples/answer-request.json
```

`as_of` must be a real `YYYY-MM-DD` date. A business result uses `{ "status", "answer", "citations" }`; citation `chunk_id` is the policy ID and `quote` is verbatim source text. `ANSWERED` has a supported string and citations; `INSUFFICIENT_EVIDENCE` has a null answer and an empty citation array; `CONFLICT` has a null answer and all distinct conflicting eligible quotations.

Error scenario- Invalid tenant/role
```bash
curl -sS http://localhost:8089/answer \
  -H 'Content-Type: application/json' \
    -H 'X-Caller-Id: atlas8xyz' \
      --data @examples/answer-request.json
```
Error scenario: Invalid date

```bash
curl -sS http://localhost:8089/answer \
 -H 'Content-Type: application/json' \
  -H 'X-Caller-Id: atlas-employee-01' \
   --data @examples/answer-request-error-date.json
```
Error scenario: Invalid topic

```bash
curl -sS http://localhost:8089/answer \
 -H 'Content-Type: application/json' \
   -H 'X-Caller-Id: atlas-employee-01' \
      --data @examples/answer-request-error-topic.json
```
### Reimbursement batch

The multipart `metadata` part must be JSON; add repeated `files` parts with the exact manifest filenames:

Success scenario:

```bash
curl -sS http://localhost:8089/batches \
  -H 'X-Caller-Id: atlas-employee-01' \
  -F 'metadata=@examples/batch-metadata.json;type=application/json' \
  -F 'files=@data/requests/request-01.txt;filename=request-01.txt' \
  -F 'files=@data/requests/request-02.pdf;filename=request-02.pdf' \
  -F 'files=@data/requests/request-03.txt;filename=request-03.txt' \
  -F 'files=@data/requests/request-04.txt;filename=request-04.txt' \
  -F 'files=@data/requests/request-05.txt;filename=request-05.txt' \
  -F 'files=@data/requests/request-06.txt;filename=request-06.txt' \
  -F 'files=@data/requests/request-07.txt;filename=request-07.txt' \
  -F 'files=@data/requests/request-08.txt;filename=request-08.txt'
```
Error scenario- Invalid date

```bash
curl -sS http://localhost:8089/batches \
  -H 'X-Caller-Id: atlas-employee-01' \
    -F 'metadata=@examples/batch-metadata-error-date.json;type=application/json' \
      -F 'files=@data/requests/request-01.txt;filename=request-01.txt' \
        -F 'files=@data/requests/request-02.pdf;filename=request-02.pdf' \
          -F 'files=@data/requests/request-03.txt;filename=request-03.txt' \
            -F 'files=@data/requests/request-04.txt;filename=request-04.txt' \
              -F 'files=@data/requests/request-05.txt;filename=request-05.txt' \
                -F 'files=@data/requests/request-06.txt;filename=request-06.txt' \
                  -F 'files=@data/requests/request-07.txt;filename=request-07.txt' \
                    -F 'files=@data/requests/request-08.txt;filename=request-08.txt'

```

Results remain in manifest order. Each item has `document_id`, `processing_status`, `extracted`, `field_evidence`, `policy`, `review_required`, `issues`, `duplicate_of`, and `error`. `extracted` contains `benefit`, numeric `amount`, `currency`, and `reference`; unresolved and absent values are null. Each `field_evidence` entry has `status` (`SUPPORTED`, `AMBIGUOUS`, or `MISSING`) and source `quotes`. Per-file failures use `FAILED`, a stable `{code,message}` error, empty field evidence, null policy, and do not prevent later items. Whole-batch metadata or filename mismatches are HTTP 400. Exact byte duplicates point to the first matching manifest `document_id`.

Every completed request requires human review. Policy limits do not establish remaining balance, expense eligibility, or payment. The application never approves, pays, or contacts employees.

Typical public error codes: `UNKNOWN_CALLER`, `INVALID_AS_OF`, `INVALID_REQUEST`, `INVALID_MANIFEST`, `FILE_PART_MISMATCH`, `DEPENDENCY_TIMEOUT`, and `DEPENDENCY_FAILURE`. Item-level document errors include `EMPTY_DOCUMENT`, `UNREADABLE_TEXT`, `UNREADABLE_PDF`, `UNREADABLE_DOCUMENT`, and `DOCUMENT_READ_FAILURE`.

## Data and evidence rules

The complete synthetic source corpus is `python_service/data/policies.json`; its records and passage text are unchanged from the assessment. Only records with exact tenant and role match, `Approved` state, and `effective_from <= as_of < effective_to` are eligible. Retrieval excludes instruction-like passages, including the provided prompt-injection example. Policy is data, not application logic. Duplicate identical passages are collapsed; distinct simultaneously applicable passages produce a conflict without an invented precedence rule.

TXT is decoded as strict UTF-8 and PDFs are text-extracted with pypdf. There is no OCR. The offline answer generator returns an exact eligible source passage and output is checked before response. Provider timeout, unavailable provider, and malformed output are technical failures, not insufficient evidence. The provider adapter is injectable in Python tests; no external model is enabled.

## Tests and demonstration

```bash
python -m unittest discover -s python_service/tests -v
mvn test
```

The mixed request set is in `data/requests/` and its manifest in `examples/batch-metadata.json`; request 02 is a text-based PDF, request 06 is byte-for-byte identical to request 01, and request 08 is empty. Regenerate the text PDF with `python scripts/create_request_pdf.py`. Representative API responses are saved in `examples/responses/`. Tests cover policy eligibility, effective dates, citations, conflicts, injection exclusion, extraction ambiguity, duplicates, PDF/text parsing, and provider failure behavior.

**Known test gap:** the suite does not exercise concurrent load or production identity/authentication; neither is part of this local assessment.

## Design notes

- [Decision note](examples/decision-note.md) explains the evidence-first offline model choice and limitations.
- [Production design note](examples/production-design-note.md) outlines a 10,000-request/day design and open questions.
