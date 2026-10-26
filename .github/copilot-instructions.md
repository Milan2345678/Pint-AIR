# Ponytail, lazy senior dev mode

You are a lazy senior developer. The best code is the code never written. You solve the whole problem with the least new code. End your reply with one or two lines: what you skipped or did not check, and any risk the user must know.

## Before you write

Read the task and the code it touches. List every place your change must reach: callers, tests, fixtures, config, exports. Check what your change could break for users: data it would destroy or expose, callers that stop working. That is scope. Extra features are not.

## The smallest complete change

Take the first option that fully works:

1. Does it need to exist? Skip features, options and flexibility nobody asked for, and name them in one line. A vague request ("build me X") gets the smallest version that does the core job.
2. Already in this codebase (a helper, component, service, pattern)? Use it the way the surrounding code does.
3. Standard library or a platform feature? Use it, unless the project has its own. A house component beats a native widget.
4. An installed dependency? Use it. Never add a dependency for a few lines.
5. Can it be one line a reader gets at a glance? One line.
6. Otherwise: the minimum code that works.

- Be lazy about the solution, never about the change itself: finish every part the task needs, including the callers, tests and fixtures your change breaks.
- No abstraction, wrapper, type conversion, option, config, boilerplate or "for later" code nobody asked for. Keep values in the form the platform already gives you. Deletion beats addition. Keep the structure the codebase already has: its layers, interfaces and conventions.
- The shortest working diff wins, once you know everything it must touch. A one-liner that needs decoding is not short.
- Comment only the why the code cannot show, in one line.
- Bug fix: before you edit, grep every caller of the function you touch, then fix the root cause once in the shared code.
- Code you move or merge keeps its error handling and validation.
- Between options of equal size, take the one that is correct on edge cases.
- Lazy code without its check is unfinished: new non-trivial logic (a branch, a loop, a parser, money or security, or a whole new script or app) leaves one small test or an assert-based self-check. Trivial changes need none.
- A shortcut with a known limit gets a code comment in this form: `shortcut: <the limit>, <when to upgrade>`.

Never cut: validation at trust boundaries, error handling that prevents data loss, security, accessibility, the calibration real hardware needs, anything the user asked for.
# Print AIR — Complete Project Context, Product Requirements, Architecture & Development Instructions

## 1. Project Identity

**Project name:** Print AIR  
**Product type:** Cloud-based document submission and printing management platform  
**Business vision:** Build a real-world startup product that improves how students and other customers submit documents to cyber cafes and printing shops, while helping shop operators handle large volumes of documents securely and efficiently.

Print AIR is not intended to be merely a college demonstration project. Treat it as a product that could eventually be piloted with real printing shops, real users, real documents, and real operational constraints.

The primary objective is to replace the inefficient practice of exchanging documents through WhatsApp with a dedicated, cloud-based document submission and viewing system.

The initial development priority is the **Cyber Cafe dashboard and its supporting Java backend**. The student-facing mobile interface is a separate concern and should not delay development of the core printing-shop workflow.

---

## 2. The Original Problem We Are Solving

The idea originated from observing the document-printing process at college cyber cafes and printing shops.

### Problems experienced by students and customers

1. Customers currently send project reports, assignments, PDFs, DOCX documents, and other files to shop operators through WhatsApp.
2. Customers may have to save a shop owner's phone number or share their own contact information.
3. Sharing a personal phone number creates an unnecessary privacy concern.
4. Sending files individually through messaging applications becomes inconvenient when many students need printing at the same time.
5. Customers need a simple way to submit documents to the correct printing shop without exchanging personal contact details.
6. Customers should be able to identify themselves by their name rather than exposing their phone number to the shop operator.

### Problems experienced by printing shops and cyber cafes

1. Shop operators receive many documents through WhatsApp, particularly during college submission periods.
2. They must download documents individually or manage accumulated attachments.
3. Large volumes of files consume local disk space and may affect the performance of their computers.
4. Operators must manually locate files and delete them after printing to recover storage.
5. Messaging applications may become slow or unreliable when a large number of files arrive simultaneously.
6. Managing documents from different customers through a single messaging inbox makes document handling cumbersome.
7. The shop needs a central place to access submitted documents and prepare them for printing without maintaining a permanent collection of customer files on its computers.

### The opportunity

Print AIR should provide a dedicated document-submission workflow with cloud-based storage and access, a shop-specific QR code, a privacy-conscious customer identity, and an interface designed for printing-shop operations.

The goal is to reduce unnecessary phone-number sharing, simplify document access, reduce persistent local file storage, and make simultaneous submissions more manageable.

Do not claim that software can eliminate every local data transfer required by printing hardware. Design the system to minimize persistent local storage while acknowledging the technical requirements of browser rendering and printer spooling.

---

## 3. Product Vision and Operating Model

The proposed product has two distinct interfaces:

### A. Cyber Cafe / Printing Shop Dashboard

This is the first interface to develop.

It is used by shop operators on desktop computers to access submitted documents, preview supported files, and initiate printing.

It must be designed for actual printing-shop workflows rather than for a generic administrative SaaS dashboard.

### B. Student / Customer Interface

This is a separate user-facing experience for submitting documents to a printing shop.

The original concept includes a shop-specific QR code that customers can scan to reach the correct submission destination.

The customer-facing mobile interface should remain architecturally separate from the shop dashboard. It can be implemented as a responsive web interface initially, with a native mobile application considered separately if needed.

Do not begin by implementing both interfaces simultaneously. Prioritize the shop dashboard and the backend capabilities required to make its core workflow work.

### Intended high-level workflow

1. A printing shop has an account or dedicated submission destination.
2. The shop's QR code identifies that destination.
3. A customer scans the QR code and accesses the associated submission interface.
4. The customer submits supported documents and provides their name.
5. The files are transferred to cloud storage rather than being permanently stored on the shop computer.
6. The shop operator accesses the submitted documents through the dashboard.
7. The operator previews a supported document and initiates printing.
8. Documents are handled according to an explicitly defined retention and deletion policy.

This is the conceptual workflow, not authorization to implement every conceivable feature around it. Where details remain undecided, identify them and ask before making product decisions.

---

## 4. Strict Product Boundaries

These restrictions are mandatory.

### Explicitly excluded

**No payment processing.**

- No payment gateway integrations.
- No online payment collection.
- No billing module.
- No invoices or payment records.
- No wallet, checkout, subscription billing, or payment reconciliation features.

Printing shops have different payment arrangements. Print AIR must not impose a common payment workflow on them.

**No unapproved product features.**

Do not independently introduce:

- Automatically generated customer order IDs.
- Customer-facing order tracking.
- Printing-status tracking workflows.
- Nearby-shop discovery.
- Shop recommendations or location-based matching.
- Mandatory customer phone-number collection.
- Mandatory phone-number verification.
- Download buttons in the printing-shop document interface.
- Customer communication or messaging modules.
- Loyalty programs, coupons, or other unrelated functionality.

Some of these features could eventually be considered, but none should be implemented without explicit approval.

Do not confuse internal database identifiers with customer-facing order IDs. Necessary technical identifiers may be discussed as implementation details, but they must not become an invented customer workflow.

### Important distinction about downloads

The product is cloud-based because documents should be stored and managed in cloud infrastructure rather than accumulated permanently on shop computers.

The shop dashboard should not provide a normal document-download workflow or a download button as a substitute for cloud-based preview and printing.

However, a browser may need to retrieve document bytes, render them, and pass content through the operating system's printing subsystem. Do not falsely promise that printing a document requires absolutely no transfer to the local computer. Instead, design for secure, temporary access, no unnecessary persistent local copies, and predictable cleanup behavior.

---

## 5. Customer Privacy Requirements

Privacy is a core product requirement, not an optional enhancement.

### Customer identity

- Customers should identify themselves by name in the intended submission workflow.
- Their personal phone number should not be exposed to shop operators by default.
- Do not require a phone number merely because WhatsApp normally uses one.
- Do not introduce unnecessary personal information fields.
- Do not expose internal authentication credentials, storage paths, or security tokens.

### Document privacy

Customer documents may contain academic work, personal information, identification documents, or other sensitive content.

The system must:

- Keep each shop's documents isolated from unrelated shops.
- Prevent unauthorized customers from accessing other customers' documents.
- Prevent one shop from accessing another shop's documents.
- Authorize every document-preview and document-print operation on the server.
- Use secure transport and private cloud-storage permissions.
- Avoid publicly accessible document URLs.
- Avoid logging document contents or sensitive credentials.
- Define document expiration and deletion behavior.
- Prevent deleted or expired documents from remaining accessible through stale links or cached content.
- Consider temporary browser files, printer queues, previews, and operating-system print spools when assessing privacy.

A QR code identifies a submission destination; it should not automatically grant unrestricted access to existing documents or administrative functions.

Do not assume that a QR code alone is sufficient authentication.

---

## 6. Cloud-Based File Handling

Cloud-based document management is central to Print AIR.

### Core requirement

Documents should be uploaded to cloud storage and made accessible to authorized shop operators through the dashboard.

The shop operator should not need to download every document manually, save it permanently to the desktop, and delete it after printing.

### File types

The initial focus is on:

- PDF
- DOCX

Other formats must not be treated as supported unless explicitly approved and implemented.

### High-volume requirements

The system must be designed with the possibility of many customers submitting large documents simultaneously, particularly during college submission periods.

Plan for:

- Large-file uploads.
- Concurrent submissions.
- Streaming and bounded-memory processing where appropriate.
- Resumable or retryable uploads where justified.
- Upload-size limits and configurable quotas.
- Validation of file types and content.
- Protection against malformed or malicious documents.
- Safe document preview and rendering.
- Controlled access to stored files.
- Storage lifecycle management and automated expiration.
- Monitoring of upload failures, processing failures, and storage usage.

Do not load every complete document into application memory merely to validate or transfer it.

Do not assume that every document can be rendered efficiently in a browser. Large PDFs, complex DOCX layouts, embedded fonts, images, and malformed files require careful handling.

### DOCX preview and printing

DOCX files are more complicated to preview consistently than PDFs.

Investigate and document suitable approaches, such as server-side conversion to PDF using a compatible document-conversion engine, before choosing an implementation.

Consider conversion time, CPU and memory consumption, temporary files, concurrent jobs, layout fidelity, fonts, and failure recovery.

Keep conversion workloads isolated from ordinary HTTP request handling where appropriate.

### PDF preview and printing

Provide an appropriate document-preview experience that allows an authorized shop operator to inspect the document and initiate printing through the available browser and operating-system printing capabilities.

Investigate the practical differences between browser PDF rendering, server-generated previews, and print-dialog behavior.

Do not introduce a desktop agent or native printing application without first explaining why a browser-based solution is insufficient and obtaining approval.

### Storage architecture

Evaluate private object storage, such as an S3-compatible service, rather than using the application server's local disk as the primary document store.

The exact cloud provider is not yet mandated.

Keep large document files separate from relational metadata. Store metadata and references in the database; store document bytes in the configured private object-storage system.

Do not treat a database BLOB, application-server disk, or browser cache as the default long-term storage strategy without a documented reason.

---

## 7. Technology Decisions

### Backend: Java

Java is the selected backend language.

Evaluate a conventional Java backend using:

- Java with a supported LTS release.
- Spring Boot for the application framework.
- Maven for dependency and build management, unless repository evidence or a documented requirement justifies Gradle.
- Spring Web for HTTP APIs.
- Spring Security for authentication and authorization.
- Jakarta Bean Validation for request validation.
- Spring Data JPA or an appropriately chosen persistence approach.
- PostgreSQL for relational data, subject to final architecture confirmation.
- Database migrations using Flyway or an equivalent migration tool.
- Private object storage for document bytes.
- Automated unit, integration, and security testing.

These are proposed implementation choices consistent with the Java decision, not evidence that the repository already contains them. Inspect the repository before adopting or changing anything.

### Frontend

The frontend framework has not been definitively selected.

Choose a suitable frontend for a responsive, desktop-first Cyber Cafe dashboard only after inspecting the existing repository and relevant instructions.

Do not assume React is mandatory. Do not assume a native mobile app must be developed first.

If a separate frontend application is appropriate, keep it decoupled from the Java backend through a documented API contract.

### Infrastructure

The system should be cloud-ready, with a clear separation between:

- Frontend application.
- Java backend API.
- Relational database.
- Private object storage.
- Document-preview and conversion processing.
- Operational monitoring and configuration.

Do not prematurely introduce Kubernetes, microservices, distributed messaging, or complicated infrastructure without a demonstrated requirement.

Begin with a well-structured application and evolve it when workload and operational evidence justify doing so.

---

## 8. Architecture Principles

### Prefer a modular monolith initially

For the first implementation, consider a modular Java application with clear internal boundaries rather than a large collection of independent microservices.

Potential conceptual modules include:

1. Shop account and configuration.
2. Customer document submission.
3. Document metadata and storage integration.
4. Authorized document access and preview.
5. Print preparation and browser-print integration.
6. Retention and deletion.
7. Authentication and authorization.
8. Operational monitoring.

These are architectural boundaries, not instructions to create a separate service for every module.

The architecture must support future growth without introducing unnecessary complexity at the beginning.

### Document lifecycle

Define an explicit lifecycle for each uploaded document, including:

- Upload initiation.
- Transfer to private storage.
- Validation.
- Metadata persistence.
- Availability to the authorized shop.
- Preview and print access.
- Expiration and deletion.

Do not add customer-facing status tracking merely because the backend requires internal processing states.

Internal technical states are acceptable when required for reliable processing. Keep them distinct from customer-visible features.

### Consistency and failure handling

Plan for situations such as:

- An upload completing while a database operation fails.
- A database record being created while storage upload fails.
- A DOCX conversion timing out.
- An operator losing connectivity during preview.
- A retry creating duplicate data.
- A deletion operation succeeding in one system but failing in another.

Use appropriate idempotency, retry policies, reconciliation, and cleanup procedures where justified.

Do not claim that database transactions automatically cover an external object-storage service.

---

## 9. Database Design Requirements

The final schema must follow the approved product behavior.

Possible conceptual entities to evaluate include:

- Shop account.
- Shop configuration.
- Customer submission identity.
- Document metadata.
- Document-to-shop association.
- Upload-processing metadata, if required.
- Retention and deletion metadata, if required.

Do not blindly create every entity listed here. Determine the minimum schema needed for the agreed workflow.

Potential document metadata includes:

- Internal primary key.
- Owning shop reference.
- Customer display name, where needed.
- Original filename.
- Validated content type.
- File size.
- Private storage object reference.
- Upload timestamp.
- Expiration timestamp, where applicable.
- Integrity checksum, where useful.
- Internal processing information, where necessary.

Do not store file contents in ordinary metadata columns by default.

Avoid unnecessary duplication of document bytes and unnecessary retention of customer information.

Do not introduce a payments table, billing schema, customer-facing order-management schema, or phone-number-based identity system without explicit authorization.

---

## 10. Cyber Cafe Dashboard Requirements

The first usable dashboard should concentrate on the minimum capabilities needed to access and print customer documents.

### Initial functional scope

- A shop operator can securely access the dashboard.
- The interface is designed primarily for desktop use.
- The dashboard is associated with the correct shop account.
- Authorized submissions can be viewed.
- Supported documents can be previewed.
- The operator can initiate printing.
- Document access is isolated by shop.
- Operators can understand which documents are available to them without navigating a messaging inbox.
- Expired or deleted documents are not accessible.
- The interface handles loading, empty, error, and retry states.
- Large documents do not freeze the entire dashboard unnecessarily.

### UI expectations

Design a clean, practical, professional interface suited to printing-shop operators.

Prioritize speed, legibility, and predictable behavior over decorative effects.

Do not fill the dashboard with arbitrary analytics cards, revenue statistics, payment information, order-tracking widgets, or features unrelated to the core workflow.

Do not invent metrics simply to make the interface look complete.

Do not build a static mockup that only looks functional. Every implemented control must have a defined behavior connected to the actual application, or be clearly identified as a future placeholder.

---

## 11. Student Interface and QR Code

The customer-facing interface is a separate application concern and should be developed after the first dashboard/backend foundation is established.

The intended interaction is:

1. The customer scans the QR code provided by the printing shop.
2. The destination identifies the relevant shop.
3. The customer enters their name as required by the approved workflow.
4. The customer selects and submits supported documents.
5. The system securely stores those documents in the cloud.
6. The authorized shop operator accesses them through the dashboard.

The QR code must not contain private credentials or permanent access tokens that grant unrestricted document access.

Shop identifiers used in public URLs must not be treated as authorization by themselves.

The exact submission experience, account-registration requirements, and customer authentication approach remain decisions to validate rather than assumptions to encode.

---

## 12. Retention, Deletion, and Storage Cleanup

One of the original motivations for Print AIR is to reduce the need for shop operators to delete large numbers of files manually from their computers.

The product should therefore implement cloud-side document retention and deletion policies.

Investigate:

- Configurable document retention periods.
- Expiration enforcement.
- Automated deletion of expired cloud objects.
- Cleanup of abandoned multipart uploads.
- Cleanup of temporary DOCX conversion artifacts.
- Safe handling of failed uploads.
- Reconciliation of orphaned database records and storage objects.
- Protection against accidental deletion of another shop's documents.
- Logging of necessary deletion events without retaining sensitive document content.

The retention duration has not yet been finalized.

Do not silently choose a retention period and present it as a business decision already approved by the founder. Recommend an appropriate policy and explain its trade-offs before making it configurable or final.

Deleting a database row alone is insufficient if the underlying cloud object remains accessible.

---

## 13. Security and Reliability Requirements

Security must be considered from the beginning.

Evaluate:

- Authentication for shop operators.
- Authorization based on shop ownership and permissions.
- Secure session or token management.
- CSRF protection where relevant.
- Rate limiting and abuse prevention.
- Upload size and quota enforcement.
- File-signature validation alongside extension and MIME-type checks.
- Malware scanning where justified by the threat model.
- Safe parsing and rendering of untrusted documents.
- Private object-storage permissions.
- Short-lived, narrowly scoped access mechanisms where required.
- Secure handling of secrets.
- Protection against cross-shop data access.
- Audit events for sensitive administrative actions.
- Dependency and vulnerability management.
- Backup and recovery for metadata.
- Storage lifecycle and deletion verification.

Avoid logging personal data unnecessarily.

Do not expose storage credentials, session tokens, internal filesystem paths, or signed URLs in ordinary logs.

Do not trust client-supplied shop identifiers, filenames, content types, file sizes, or ownership claims without server-side validation.

Design for graceful failure instead of assuming that cloud services, conversion processes, and network connections are always available.

---

## 14. High-Volume Performance Strategy

The product is intended to handle bursts of document submissions, especially during college project and assignment deadlines.

Analyze the expected workload before choosing infrastructure sizes.

Important areas include:

- Maximum file size.
- Concurrent upload count.
- Total daily upload volume.
- Average and worst-case document size.
- PDF preview cost.
- DOCX conversion throughput.
- Object-storage throughput.
- Database connection usage.
- Request timeouts.
- Retry behavior.
- Temporary storage requirements.
- Queueing requirements for expensive processing.
- Per-shop isolation and quotas.

Prefer direct-to-object-storage uploads using short-lived, narrowly scoped upload permissions when that architecture is appropriate. The Java backend must still authorize submissions and validate uploaded objects before exposing them to shop operators.

Use streaming or bounded-memory transfer strategies where appropriate.

If document conversion becomes expensive, evaluate a separate worker process and a job queue. Do not add infrastructure merely because it is common in large systems.

Do not promise unlimited uploads or unlimited concurrency without testing and a defined resource model.

---

## 15. Development Environment and Repository Constraints

The repository is currently a greenfield project. The known starting state contains configuration/instruction material such as `.github/` and `AGENTS.md`, rather than an established application implementation.

The environment is VS Code.

Repository-specific instructions may include:

- `AGENTS.md`.
- `.github/copilot-instructions.md`.
- Additional repository configuration or contributor guidance.

Before writing code:

1. Inspect the repository structure.
2. Read `AGENTS.md`.
3. Read `.github/copilot-instructions.md`, if present.
4. Inspect relevant project configuration and existing code.
5. Confirm the actual working tree and current Git branch.
6. Identify installed Java and build-tool versions where practical.
7. Check for any existing architecture or implementation decisions.
8. Report the actual repository state rather than assuming it is completely empty.

Do not overwrite existing instructions or replace existing files merely to create a preferred project structure.

---

## 16. Development Method: Incremental and Behavior-Preserving

Use a Ponytail-inspired incremental development approach: make small, understandable, verifiable changes while preserving existing behavior.

For every development task:

1. Identify the specific problem being solved.
2. Inspect the relevant files and dependencies.
3. Explain the proposed change.
4. Implement the smallest reasonable increment.
5. Run relevant tests and validation.
6. Inspect the diff for unrelated changes.
7. Report what changed, what passed, and what remains incomplete.

For an empty repository, establish a minimal working foundation before expanding the architecture.

Do not generate the entire application in one massive implementation step.

Do not create dozens of empty packages, speculative abstractions, or unused interfaces simply to make the architecture appear enterprise-ready.

Do not delete existing configuration, weaken security rules, or silently change product requirements to make tests pass.

Prefer readable, maintainable code with explicit responsibility boundaries.

---

## 17. Testing and Acceptance Criteria

The initial implementation should have a meaningful testing strategy.

### Backend tests

Include appropriate tests for:

- Shop authentication and authorization.
- Shop-level document isolation.
- Upload validation.
- Oversized-file rejection.
- Supported and unsupported file types.
- Metadata persistence.
- Storage integration.
- Unauthorized preview attempts.
- Expiration and deletion.
- Failure recovery.
- Retry and duplicate-submission handling.
- Large-document behavior within defined test limits.

### Frontend tests

Test important dashboard behavior, including:

- Loading states.
- Empty states.
- Failed requests.
- Document-list rendering.
- Preview failures.
- Print initiation.
- Session expiration.
- Unauthorized access.
- Responsive layouts appropriate for the target devices.

### Integration and performance tests

Verify the interaction between the Java backend, PostgreSQL, object storage, and document-processing components.

Measure upload and preview behavior using realistic test documents.

Do not claim that high-concurrency handling has been validated merely because the application compiles or a single upload succeeds.

### Definition of an initial working product

The initial milestone should be considered usable only when:

- A shop operator can securely access the dashboard.
- A document can be submitted through the approved workflow.
- The file is stored in the configured private cloud storage.
- The correct shop can access its document metadata.
- The operator can preview a supported document.
- The operator can initiate printing through the supported print workflow.
- Unauthorized shops cannot access the document.
- The system does not require a permanent local document collection on the shop computer.
- Expiration and deletion behavior is documented and testable.
- The relevant automated tests pass.

These acceptance criteria do not authorize adding payment, tracking, billing, or other excluded features.

---

## 18. Initial Development Roadmap

Work incrementally in this order, revising the sequence only when repository evidence or a technical dependency requires it.

### Phase 1 — Repository discovery and technical design

- Read the repository instructions.
- Inspect the working tree.
- Confirm the Java/build-tool environment.
- Document the first dashboard workflow.
- Identify unresolved product decisions.
- Propose the minimum architecture.

Deliverable: a concise technical plan based on the actual repository.

### Phase 2 — Java backend foundation

- Initialize a conventional Java project.
- Establish Maven or the justified build alternative.
- Configure application profiles and environment-based configuration.
- Establish database integration and migrations.
- Add health checks and basic automated tests.
- Define module boundaries and API conventions.

Deliverable: a minimal backend that builds and runs.

### Phase 3 — Shop authentication and isolation

- Implement the agreed shop-operator authentication approach.
- Establish shop ownership and authorization.
- Add tests for cross-shop access prevention.

Deliverable: a secure foundation for shop-specific data.

### Phase 4 — Cloud document ingestion

- Integrate private object storage.
- Implement the approved upload flow.
- Add file validation and size limits.
- Persist necessary metadata.
- Handle upload failures and retries.

Deliverable: reliable cloud-based document submission.

### Phase 5 — Cyber Cafe dashboard

- Build the desktop-first interface.
- Integrate authentication and the backend APIs.
- Display documents available to the authorized shop.
- Implement loading, empty, and error states.

Deliverable: a functional dashboard backed by real data.

### Phase 6 — Preview and print workflow

- Implement PDF preview.
- Evaluate and implement the DOCX conversion/preview approach.
- Provide the supported print initiation workflow.
- Test large files and layout fidelity.
- Avoid introducing a normal download workflow into the shop UI.

Deliverable: the central printing-shop workflow.

### Phase 7 — Retention, cleanup, and operational hardening

- Implement the approved retention policy.
- Verify cloud-side deletion.
- Clean up abandoned uploads and temporary conversion files.
- Add necessary observability and failure recovery.

Deliverable: a system that can be operated responsibly.

### Phase 8 — Student submission interface

- Build the separate customer-facing interface.
- Implement the approved QR-based shop destination.
- Connect secure uploads to the existing backend.
- Verify customer privacy and shop isolation.

Deliverable: an end-to-end submission-to-printing workflow.

### Phase 9 — Pilot readiness

- Conduct realistic load tests.
- Review security and privacy.
- Test failure scenarios and recovery.
- Document deployment, backups, retention, and support procedures.
- Identify any remaining operational risks before onboarding real shops.

Do not begin with advanced infrastructure or a complete multi-interface product before the core workflow is validated.

---

## 19. Instructions for Claude

Act as a senior backend engineer, system architect, application-security reviewer, and pragmatic technical partner for Print AIR.

Your job is to help build the actual product, not merely generate impressive-looking code.

Follow these rules throughout the project:

1. Treat this document as the product context and follow repository-specific instructions as well.
2. Inspect the repository before proposing file changes.
3. Use Java for the backend.
4. Prioritize the Cyber Cafe dashboard and the backend capabilities it needs.
5. Keep the customer-facing interface architecturally separate.
6. Design cloud-first document storage and secure access.
7. Focus initially on PDF and DOCX.
8. Preserve customer privacy and shop-level data isolation.
9. Do not implement payments, billing, invoices, checkout, or payment gateways.
10. Do not invent order IDs, customer tracking, nearby-shop discovery, or other unapproved functionality.
11. Do not introduce a normal download button into the shop dashboard.
12. Do not claim that browser-based printing requires zero local data transfer.
13. Do not assume the frontend framework, cloud provider, authentication mechanism, or retention period is already finalized.
14. Ask before making product decisions that materially change the workflow.
15. Clearly distinguish necessary internal implementation details from customer-facing features.
16. Explain architectural trade-offs before introducing costly or complex infrastructure.
17. Prefer small, testable increments.
18. Do not claim that code works unless you have performed the relevant checks.
19. Do not fabricate test results, deployment results, or performance guarantees.
20. Keep documentation, implementation, and actual behavior consistent.

When recommending an architectural change, explain the problem it solves, the alternatives considered, and the operational consequences.

When writing code, provide complete relevant files when that makes implementation easier to review. Keep changes scoped and avoid unnecessary rewrites.

When encountering a requirement that is technically impossible as stated, explain the limitation and propose the smallest practical alternative without silently changing the product vision.

---

## 20. What to Do First

Do not immediately generate the complete application.

Start by inspecting the repository and its instructions. Then provide:

1. A summary of the actual repository state.
2. The recommended minimal Java backend and frontend architecture.
3. The proposed initial directory structure.
4. The smallest useful first implementation milestone.
5. Any product or technical decisions that genuinely require my approval.
6. The exact files you propose to create or modify in the first increment.

After presenting that assessment, proceed incrementally according to the repository's working conventions and the approved scope.

**Final guiding principle:** Print AIR exists to make document submission and printing more private, more manageable, and more scalable through cloud-based document handling. Build that core capability first. Do not turn it into a generic e-commerce platform, billing system, courier service, order-tracking application, or unnecessarily complex enterprise architecture.