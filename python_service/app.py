from __future__ import annotations

import base64
import binascii
import json
import re
import logging
from dataclasses import dataclass
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import urlparse

from pypdf import PdfReader


ROOT = Path(__file__).resolve().parent
POLICY_PATH = ROOT / "data" / "policies.json"
MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
MAX_JSON_BYTES = 4 * 1024 * 1024


@dataclass(frozen=True)
class Policy:
    id: str
    tenant: str
    role: str
    approval_state: str
    effective_from: date
    effective_to: date
    text: str


class ProviderFailure(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class AnswerGenerator(Protocol):
    def generate(self, question: str, evidence: list[Policy]) -> dict[str, Any]:
        """Return a JSON-shaped answer based only on already-filtered evidence."""


class OfflineAnswerGenerator:
    """A deterministic local double; it never interprets source text as instructions."""

    def generate(self, question: str, evidence: list[Policy]) -> dict[str, Any]:
        del question
        return {"answer": evidence[0].text}


class EvidenceService:
    def __init__(self, policies: list[Policy], generator: AnswerGenerator | None = None):
        self.policies = tuple(policies)
        self.generator = generator or OfflineAnswerGenerator()

    @classmethod
    def from_file(cls, path: Path = POLICY_PATH, generator: AnswerGenerator | None = None) -> EvidenceService:
        records = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(records, list):
            raise ValueError("Policy corpus must be a JSON array.")
        policies = []
        for record in records:
            policies.append(Policy(
                id=record["id"],
                tenant=record["tenant"],
                role=record["role"],
                approval_state=record["approval_state"],
                effective_from=date.fromisoformat(record["effective_from"]),
                effective_to=date.fromisoformat(record["effective_to"]),
                text=record["text"],
            ))
        return cls(policies, generator)

    def answer(self, tenant: str, role: str, as_of: str, question: str) -> dict[str, Any]:
        requested_date = parse_date(as_of)
        if not isinstance(question, str) or not question.strip():
            raise RequestError("INVALID_REQUEST", "question must be a non-empty string.")

        topic = classify_topic(question)
        if topic is None:
            return answer_result("INSUFFICIENT_EVIDENCE", None, [])

        evidence = self._eligible(tenant, role, requested_date, topic)
        if not evidence:
            return answer_result("INSUFFICIENT_EVIDENCE", None, [])

        distinct = unique_evidence(evidence)
        if len(distinct) > 1:
            return answer_result("CONFLICT", None, citations(distinct))

        try:
            # A single call is made; no automatic retry is performed.
            generated = self.generator.generate(question, distinct)
        except TimeoutError as exc:
            raise ProviderFailure("PROVIDER_TIMEOUT", "Answer generation timed out.") from exc
        except ProviderFailure:
            raise
        except Exception as exc:
            raise ProviderFailure("PROVIDER_UNAVAILABLE", "Answer generation is unavailable.") from exc
        answer = generated.get("answer") if isinstance(generated, dict) else None
        if not isinstance(answer, str) or not answer.strip():
            raise ProviderFailure("MALFORMED_MODEL_OUTPUT", "Answer generation returned an invalid response.")
        # The offline provider emits an exact eligible passage; arbitrary model claims are not accepted.
        if answer not in {policy.text for policy in distinct}:
            raise ProviderFailure("MALFORMED_MODEL_OUTPUT", "Answer generation returned unsupported text.")
        return answer_result("ANSWERED", answer, citations(distinct))

    def _eligible(self, tenant: str, role: str, requested_date: date, topic: str) -> list[Policy]:
        candidates = [
            policy for policy in self.policies
            if policy.tenant == tenant
            and policy.role == role
            and policy.approval_state == "Approved"
            and policy.effective_from <= requested_date < policy.effective_to
            and policy_topic(policy.text) == topic
            and not looks_like_instruction(policy.text)
        ]
        return sorted(candidates, key=lambda policy: (policy.id, policy.text))

    def process_document(
        self,
        tenant: str,
        role: str,
        as_of: str,
        filename: str,
        content: bytes,
    ) -> dict[str, Any]:
        try:
            requested_date = parse_date(as_of)
            text = extract_text(filename, content)
        except RequestError as exc:
            return failed_document(exc.code, str(exc))

        extracted, field_evidence = extract_fields(text)
        issues = ["Human review is required for every submitted request."]
        issues.append("Policy limits do not establish remaining balance, expense eligibility, or a payable amount.")
        for field_name, evidence in field_evidence.items():
            if evidence["status"] in {"MISSING", "AMBIGUOUS"}:
                issues.append(f"{field_name.capitalize()} is {evidence['status'].lower()} in the submitted text.")

        benefit = extracted["benefit"]
        policy = None
        if benefit is not None:
            policy_result = self.answer(tenant, role, requested_date.isoformat(), benefit)
            policy = policy_result
            if policy_result["status"] == "CONFLICT":
                issues.append("Applicable policy records conflict; a reviewer must resolve the policy.")
            elif policy_result["status"] == "INSUFFICIENT_EVIDENCE":
                issues.append("No applicable approved policy evidence was found for this benefit.")
            if benefit == "external training" and re.search(
                r"\b(booked|book|reserved)\b.{0,60}\b(no|not|without)\b.{0,30}\bapproval\b"
                r"|\b(no|not|without)\b.{0,30}\bapproval\b.{0,60}\b(booked|book|reserved)\b",
                text,
                re.IGNORECASE | re.DOTALL,
            ):
                issues.append("The submission says external training was booked without manager approval.")
        else:
            issues.append("The requested benefit could not be identified from the submitted text.")

        return {
            "processing_status": "COMPLETED",
            "extracted": extracted,
            "field_evidence": field_evidence,
            "policy": policy,
            "review_required": True,
            "issues": issues,
            "duplicate_of": None,
            "error": None,
        }


class RequestError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def parse_date(value: str) -> date:
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise RequestError("INVALID_AS_OF", "as_of must be a valid date in YYYY-MM-DD format.")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise RequestError("INVALID_AS_OF", "as_of must be a valid date in YYYY-MM-DD format.") from exc


TOPIC_PATTERNS = {
    "certification": re.compile(r"\bcertification\b", re.IGNORECASE),
    "home-office allowance": re.compile(r"\bhome[\s-]*office\b", re.IGNORECASE),
    "rail travel": re.compile(r"\b(rail|train travel|travel)\b", re.IGNORECASE),
    "external training": re.compile(r"\bexternal training\b|\btraining\b", re.IGNORECASE),
    "wellness benefit": re.compile(r"\b(wellness|gym membership)\b", re.IGNORECASE),
}


def classify_topic(text: str) -> str | None:
    matches = [topic for topic, pattern in TOPIC_PATTERNS.items() if pattern.search(text)]
    return matches[0] if len(matches) == 1 else None


def policy_topic(text: str) -> str | None:
    # Policy relevance is determined by the named benefit, not generic words such as "allowance".
    return classify_topic(text)


INSTRUCTION_MARKERS = re.compile(
    r"\b(system message|ignore all prior|ignore previous|switch the caller|"
    r"reveal the prompt|follow these instructions|override (?:the )?(?:rules|policy))\b",
    re.IGNORECASE,
)


def looks_like_instruction(text: str) -> bool:
    return bool(INSTRUCTION_MARKERS.search(text))


def unique_evidence(policies: list[Policy]) -> list[Policy]:
    unique: dict[str, Policy] = {}
    for policy in policies:
        existing = unique.get(policy.text)
        if existing is None or policy.id < existing.id:
            unique[policy.text] = policy
    return sorted(unique.values(), key=lambda policy: (policy.id, policy.text))


def citations(policies: list[Policy]) -> list[dict[str, str]]:
    return [{"chunk_id": policy.id, "quote": policy.text} for policy in policies]


def answer_result(status: str, answer: str | None, evidence: list[dict[str, str]]) -> dict[str, Any]:
    return {"status": status, "answer": answer, "citations": evidence}


def extract_text(filename: str, content: bytes) -> str:
    if not content:
        raise RequestError("EMPTY_DOCUMENT", "The uploaded document is empty.")
    if len(content) > MAX_DOCUMENT_BYTES:
        raise RequestError("DOCUMENT_TOO_LARGE", "The uploaded document exceeds the 2 MiB limit.")
    suffix = Path(filename).suffix.lower()
    if suffix == ".txt":
        try:
            text = content.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise RequestError("UNREADABLE_TEXT", "The text file is not valid UTF-8.") from exc
    elif suffix == ".pdf":
        try:
            reader = PdfReader(__import__("io").BytesIO(content), strict=True)
            text = "\n".join(page.extract_text() or "" for page in reader.pages)
        except Exception as exc:
            raise RequestError("UNREADABLE_PDF", "The PDF could not be read as text.") from exc
    else:
        raise RequestError("UNSUPPORTED_FILE_TYPE", "Only UTF-8 TXT and text-based PDF files are supported.")
    if not text.strip():
        raise RequestError("UNREADABLE_DOCUMENT", "The file contains no readable text.")
    return text


AMOUNT_PATTERN = re.compile(
    r"(?P<currency>INR|Rs\.?|₹)\s*(?P<amount>\d[\d,]*(?:\.\d{1,2})?)",
    re.IGNORECASE,
)
REFERENCE_PATTERN = re.compile(r"(?im)^\s*Reference\s*:\s*(?P<value>[A-Za-z0-9-]+)\s*$")


def extract_fields(text: str) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    benefit = identify_benefit(text)
    amount_matches = list(AMOUNT_PATTERN.finditer(text))
    amount_values = {match.group("amount").replace(",", "") for match in amount_matches}
    currency_values = {normalize_currency(match.group("currency")) for match in amount_matches}
    reference_matches = list(REFERENCE_PATTERN.finditer(text))
    reference_values = {match.group("value") for match in reference_matches}

    amount = parse_amount(next(iter(amount_values))) if len(amount_values) == 1 else None
    currency = next(iter(currency_values)) if len(currency_values) == 1 else None
    reference = next(iter(reference_values)) if len(reference_values) == 1 else None
    benefit_quote = quote_for_benefit(text, benefit) if benefit else None

    field_evidence = {
        "benefit": evidence_entry("SUPPORTED" if benefit else "MISSING", [benefit_quote] if benefit_quote else []),
        "amount": evidence_entry(
            "MISSING" if not amount_matches else "SUPPORTED" if amount is not None else "AMBIGUOUS",
            [match.group(0) for match in amount_matches],
        ),
        "currency": evidence_entry(
            "MISSING" if not amount_matches else "SUPPORTED" if currency is not None else "AMBIGUOUS",
            [match.group(0) for match in amount_matches],
        ),
        "reference": evidence_entry(
            "MISSING" if not reference_matches else "SUPPORTED" if reference is not None else "AMBIGUOUS",
            [match.group(0).strip() for match in reference_matches],
        ),
    }
    return {
        "benefit": benefit,
        "amount": amount,
        "currency": currency,
        "reference": reference,
    }, field_evidence


def identify_benefit(text: str) -> str | None:
    if re.search(r"\b(certification|certification reimbursement)\b", text, re.IGNORECASE):
        return "certification"
    if re.search(r"\b(home[\s-]*office|desk and chair)\b", text, re.IGNORECASE):
        return "home-office allowance"
    if re.search(r"\b(external training|training)\b", text, re.IGNORECASE):
        return "external training"
    if re.search(r"\b(rail travel|train travel|business trip)\b", text, re.IGNORECASE):
        return "rail travel"
    if re.search(r"\b(wellness|gym membership)\b", text, re.IGNORECASE):
        return "wellness benefit"
    return None


def quote_for_benefit(text: str, benefit: str) -> str:
    if benefit == "certification":
        pattern = re.compile(r".{0,80}\bcertification\b.{0,120}", re.IGNORECASE)
    elif benefit == "home-office allowance":
        pattern = re.compile(r".{0,80}\b(home[\s-]*office|desk and chair)\b.{0,120}", re.IGNORECASE)
    elif benefit == "external training":
        pattern = re.compile(r".{0,80}\btraining\b.{0,120}", re.IGNORECASE)
    elif benefit == "rail travel":
        pattern = re.compile(r".{0,80}\b(rail travel|train travel|business trip)\b.{0,120}", re.IGNORECASE)
    else:
        pattern = re.compile(r".{0,80}\b(wellness|gym membership)\b.{0,120}", re.IGNORECASE)
    for sentence in re.finditer(r"[^.!?\r\n]+[.!?]*", text):
        quote = sentence.group(0).strip()
        if pattern.search(quote):
            return quote
    return text.strip()


def normalize_currency(value: str) -> str:
    return "INR" if value.upper().startswith("RS") or value == "₹" else value.upper()


def parse_amount(value: str) -> int | float:
    number = float(value) if "." in value else int(value)
    return number


def evidence_entry(status: str, quotes: list[str]) -> dict[str, Any]:
    return {"status": status, "quotes": quotes}


def failed_document(code: str, message: str) -> dict[str, Any]:
    return {
        "processing_status": "FAILED",
        "extracted": {"benefit": None, "amount": None, "currency": None, "reference": None},
        "field_evidence": {},
        "policy": None,
        "review_required": True,
        "issues": ["Item processing failed; human review is required."],
        "duplicate_of": None,
        "error": {"code": code, "message": message},
    }


class RequestHandler(BaseHTTPRequestHandler):
    server_version = "EmployeeEvidenceService/1.0"
    evidence_service: EvidenceService

    def do_GET(self) -> None:
        if urlparse(self.path).path == "/health":
            self._send(200, {"status": "UP"})
        else:
            self._send(404, {"code": "NOT_FOUND", "message": "Endpoint not found."})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        try:
            payload = self._read_json()
            if path == "/answer":
                self._handle_answer(payload)
            elif path == "/process":
                self._handle_process(payload)
            else:
                self._send(404, {"code": "NOT_FOUND", "message": "Endpoint not found."})
        except RequestError as exc:
            self._send(400, {"code": exc.code, "message": str(exc)})
        except ProviderFailure as exc:
            status = 504 if exc.code == "PROVIDER_TIMEOUT" else 503
            self._send(status, {"code": exc.code, "message": str(exc)})
        except (KeyError, TypeError, ValueError, binascii.Error):
            self._send(400, {"code": "INVALID_REQUEST", "message": "Request body is invalid."})
        except Exception:
            self._send(500, {"code": "INTERNAL_ERROR", "message": "The request could not be processed."})

    def _handle_answer(self, payload: dict[str, Any]) -> None:
        result = self.evidence_service.answer(
            payload["tenant"], payload["role"], payload["as_of"], payload["question"]
        )
        self._send(200, result)

    def _handle_process(self, payload: dict[str, Any]) -> None:
        try:
            content = base64.b64decode(payload["content_base64"], validate=True)
        except (KeyError, binascii.Error, TypeError) as exc:
            raise RequestError("INVALID_REQUEST", "Document content must be valid base64.") from exc
        result = self.evidence_service.process_document(
            payload["tenant"], payload["role"], payload["as_of"], payload["filename"], content
        )
        logging.info(
            "document_processed batch_id=%r document_id=%r status=%s",
            payload.get("batch_id"),
            payload.get("document_id"),
            result["processing_status"],
        )
        self._send(200, result)

    def _read_json(self) -> dict[str, Any]:
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if transfer_encoding == "chunked":
            body = self._read_chunked_body()
        else:
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError as exc:
                raise RequestError("INVALID_REQUEST", "Request body length is invalid.") from exc
            if length <= 0 or length > MAX_JSON_BYTES:
                raise RequestError("INVALID_REQUEST", "Request body is missing or too large.")
            body = self.rfile.read(length)
            if len(body) != length:
                raise RequestError("INVALID_REQUEST", "Request body ended unexpectedly.")
        payload = json.loads(body)
        if not isinstance(payload, dict):
            raise RequestError("INVALID_REQUEST", "Request body must be a JSON object.")
        return payload

    def _read_chunked_body(self) -> bytes:
        body = bytearray()
        while True:
            line = self.rfile.readline(8192)
            if not line or not line.endswith(b"\r\n"):
                raise RequestError("INVALID_REQUEST", "Chunked request body is malformed.")
            try:
                chunk_size = int(line.split(b";", 1)[0].strip(), 16)
            except ValueError as exc:
                raise RequestError("INVALID_REQUEST", "Chunked request body is malformed.") from exc
            if chunk_size < 0 or len(body) + chunk_size > MAX_JSON_BYTES:
                raise RequestError("INVALID_REQUEST", "Request body is too large.")
            if chunk_size == 0:
                while True:
                    trailer = self.rfile.readline(8192)
                    if trailer in {b"\r\n", b"\n", b""}:
                        return bytes(body)
                    if not trailer.endswith(b"\r\n"):
                        raise RequestError("INVALID_REQUEST", "Chunked request body is malformed.")
            chunk = self.rfile.read(chunk_size)
            if len(chunk) != chunk_size or self.rfile.read(2) != b"\r\n":
                raise RequestError("INVALID_REQUEST", "Chunked request body is malformed.")
            body.extend(chunk)

    def _send(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: Any) -> None:
        # Avoid logging request bodies, which may contain employee information.
        super().log_message(format, *args)


def serve(host: str = "0.0.0.0", port: int = 8081) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    handler = type("ConfiguredRequestHandler", (RequestHandler,), {"evidence_service": EvidenceService.from_file()})
    server = ThreadingHTTPServer((host, port), handler)
    print(f"Python evidence service listening on http://{host}:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    import os

    serve(os.getenv("PYTHON_HOST", "0.0.0.0"), int(os.getenv("PYTHON_PORT", "8081")))
