

Jarvis is a full-stack Spring Boot and React application built around a Human-in-the-Loop (HITL) Agentic Workflow. It leverages Large Language Models (LLMs) to intelligently schedule study sessions, while enforcing strict deterministic execution for external API mutations.

Core Tech Stack
Backend: Java 17+, Spring Boot, Spring Web

AI/LLM Integration: Spring AI (ChatClient)

Security & Auth: Spring Security, OAuth 2.0 (Google Login)

Frontend: React (communicating via cross-origin REST & SSE)

Integrations: Google Calendar API, Google Drive API

Key Architectural Patterns
1. Human-in-the-Loop (HITL) & Zero-LLM Execution
To ensure complete reliability, the application strictly separates non-deterministic AI reasoning from deterministic system actions.

Phase 1 (Reasoning): The AI Agent evaluates proposed schedules, resolves conflicts, and generates a validated JSON payload.

Phase 2 (Review): The user reviews the ApprovalState (persisted securely to disk) via the UI.

Phase 3 (Execution): Upon approval, a strictly deterministic loop executes the API calls to Google Calendar. Zero LLM calls are made during execution, eliminating the risk of late-stage hallucinations.

2. Custom ReAct Agent Pipeline
The AI reasoning engine uses a custom Reason-Act-Observe loop equipped with Chain of Thought (CoT). The agent is restricted to a strict JSON-only output schema and executes a 4-step tool pipeline:

check_guide_mapping: Links study sessions to actual markdown guide files on disk.

analyze_temporal_logic: Verifies chronological sanity (e.g., ensuring study sessions occur before the target exam).

check_calendar_conflicts: Queries the Google Calendar FreeBusy API to detect and auto-shift scheduling conflicts.

check_burnout_limits: Audits the schedule to flag days exceeding 6 hours of cognitive load.

3. Resilient State Management & Async Processing

Server-Sent Events (SSE): The multi-step AI validation pipeline can take 30–120 seconds. The backend streams real-time status updates back to the React frontend via SSE to prevent HTTP timeouts and improve UX.

Disk-Based Persistence: ApprovalState is managed via an ApprovalStore that writes directly to the filesystem. This ensures that pending user approvals survive server restarts and pod evictions without requiring a heavy relational database.

Strict Type Safety: All JSON payloads flowing in and out of the LLM are heavily constrained and mapped directly to immutable Java Records (e.g., CalendarEventRequest), preventing malformed data from crashing the application.

4. Secure Cross-Origin Architecture
The application implements a robust Spring Security configuration to handle the classic SPA + OAuth2 architecture.

API requests (/api/) return clean 401 Unauthorized responses (rather than 302 Redirects) to allow the React frontend to gracefully handle session timeouts and trigger Google re-authentication.

Explicit CorsConfigurationSource beans at the security-filter level ensure seamless preflight OPTIONS handling while securely passing session cookies.



*THE MODEL LOOKS THROUGH YOUR GOOGLE DRIVE AUTOMATICALLY w/ YOUR QUESTION! + PIN POINTS THE EXAM VIA YOUR CALENDAR*
<img width="1079" height="884" alt="image" src="https://github.com/user-attachments/assets/26d966e1-b30b-4ace-9efb-1df8c6cfe0b2" />
<img width="1139" height="814" alt="Screenshot 2025-11-21 120818" src="https://github.com/user-attachments/assets/e638c92d-49ae-4dbf-9eb5-788bb1afdd9b" />




