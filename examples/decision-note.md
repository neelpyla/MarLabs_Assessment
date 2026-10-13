# Design decision

**Consequential choice:** the Python service filters approved policy records by trusted tenant, role, and half-open effective date before retrieval, then uses a deterministic offline answer generator. It returns only exact source passages as answers and builds citations itself.

**Alternative rejected:** sending all policy text to a hosted language model with tenant/date rules in the prompt. That approach would add credential, privacy, prompt-injection, retry, and unsupported-answer risks, and a model could receive ineligible passages. An external model is not required to demonstrate this assessment.

**Main limitation:** retrieval is a small deterministic benefit classifier over the supplied policy schema, and TXT/PDF extraction is text-only. It is deliberately not a general semantic search engine and cannot establish claim eligibility, remaining balances, or payment amounts.

**Time spent:** approximately 12 minutes in this implementation session. **Unfinished work:** none required for the local/offline assessment scope; optional hosted-model integration, OCR, and deployment are intentionally out of scope. **AI assistance:** AI was used to draft and validate implementation and tests; the author should review and be able to explain every change before submission.
