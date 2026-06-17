# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

基于 RAG 的个人课程知识库与智能学习助手系统 (RAG-based personal course knowledge base and intelligent learning assistant).

A collaborative student project split across four team members:
- **A** (基础后端): Spring Boot backend foundation, course CRUD, unified response format, global exception handling
- **B**: Document upload, parsing, and knowledge base construction (dependent on course IDs from A)
- **C**: RAG retrieval, AI Q&A, and auto quiz generation (dependent on course IDs from A)
- **D**: Frontend UI (dependent on A's course APIs for integration)

## Tech Stack

- **Backend**: Spring Boot + Maven (Java)
- **ORM**: MyBatis-Plus
- **Database**: MySQL
- **Frontend**: (implied by `node_modules/` and `dist/` in .gitignore — likely a Node/SPA frontend)

## Build & Run Commands

```bash
# Start the backend (Spring Boot via Maven)
mvn spring-boot:run

# Build the backend
mvn clean package

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=CourseControllerTest
```

## Architecture & Conventions

### Package structure (based on A1 planning)

```
src/main/java/com/example/ragstudyassistant/
├── controller/     # REST controllers (e.g., CourseController)
├── service/        # Business logic layer (e.g., CourseService)
├── mapper/         # MyBatis-Plus mappers (e.g., CourseMapper)
├── entity/         # Domain entities (e.g., Course)
├── common/         # Shared utilities: unified response (Result), exception handler
└── config/         # Spring configuration
```

```
src/main/resources/
├── application.yml
└── db/             # Database initialization SQL scripts (A3)
```

### API conventions (from A9)

All endpoints return a unified `Result` wrapper:
- Success: `{ "code": 200, "data": ..., "message": "success" }`
- Error: `{ "code": xxx, "data": null, "message": "error description" }`

### Course API endpoints (A5–A8)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/courses` | Create a course |
| GET | `/api/courses` | List courses (optional `?name=` search param) |
| PUT | `/api/courses/{id}` | Update a course |
| DELETE | `/api/courses/{id}` | Delete a course (physical delete in v1) |

### Course entity fields (A3)

`id`, `name`, `description`, `term`, `created_at`, `updated_at`

### Cross-cutting concerns

- **Global exception handler** (A10): Catches parameter validation errors, business errors, and system errors — no raw stack traces exposed to the frontend
- **CORS** (A11): Must be configured for frontend integration

## Design Decisions

- **MyBatis-Plus** over JPA: chosen in the planning phase for easier CRUD and mapper-based queries
- **Physical delete for courses in v1**: soft-delete deferred; cascade handling with documents is a future concern
- **Priority**: course CRUD (A5–A8) must ship first since B, C, and D all depend on course IDs
