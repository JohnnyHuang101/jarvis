package com.jhsup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * ApprovalStore
 *
 * Atomic, file-based persistence for ApprovalState objects.
 *
 * All writes use the write-to-temp-then-rename pattern so that a server
 * crash during a write never leaves a half-written approval file.
 *
 * Directory layout:
 * user-data/{userId}/courses/{courseId}/approvals/{approvalId}.json
 *
 * Thread safety:
 * save() is synchronized on the file path string via intern().
 * This prevents two threads writing the same approval ID concurrently
 * (e.g., a double-click on the Approve button in the UI).
 */
@Component
public class ApprovalStore {

    private final ObjectMapper mapper;

    public ApprovalStore() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Atomically persist an ApprovalState to disk.
     * Safe to call from any thread at any time.
     */
    public void save(ApprovalState state) throws IOException {
        File dir = approvalDir(state.userId, state.courseId);
        dir.mkdirs();

        File target = new File(dir, state.approvalId + ".json");

        // Write to a temp file first, then rename — crash-safe
        File temp = new File(dir, state.approvalId + ".json.tmp");

        synchronized (target.getAbsolutePath().intern()) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp, state);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load a specific approval by ID.
     * Returns Optional.empty() if the file doesn't exist.
     * Automatically marks EXPIRED if the deadline has passed.
     */
    public Optional<ApprovalState> load(
            String userId, String courseId, String approvalId) throws IOException {

        File f = new File(approvalDir(userId, courseId), approvalId + ".json");
        if (!f.exists())
            return Optional.empty();

        ApprovalState state = mapper.readValue(f, ApprovalState.class);

        // Lazily expire — no background scheduler required
        if (state.status == ApprovalState.Status.PENDING
                && Instant.now().isAfter(state.expiresAt)) {
            state.status = ApprovalState.Status.EXPIRED;
            save(state); // persist the expiry
        }

        return Optional.of(state);
    }

    /**
     * List all approval states for a course, newest first.
     * Useful for the "pending approvals" inbox UI.
     */
    public List<ApprovalState> listForCourse(
            String userId, String courseId) throws IOException {

        File dir = approvalDir(userId, courseId);
        if (!dir.exists())
            return List.of();

        File[] files = dir.listFiles(
                (d, name) -> name.endsWith(".json") && !name.endsWith(".tmp"));

        if (files == null)
            return List.of();

        List<ApprovalState> results = new ArrayList<>();
        for (File f : files) {
            try {
                ApprovalState state = mapper.readValue(f, ApprovalState.class);
                // Lazily expire in bulk
                if (state.status == ApprovalState.Status.PENDING
                        && Instant.now().isAfter(state.expiresAt)) {
                    state.status = ApprovalState.Status.EXPIRED;
                    save(state);
                }
                results.add(state);
            } catch (IOException e) {
                // Skip corrupt files rather than crashing the whole list
                System.err.println("Skipping corrupt approval file: " + f.getName());
            }
        }

        // Sort newest first
        results.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transition helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Atomically transition an approval from PENDING → APPROVED.
     * Returns the updated state.
     * Throws IllegalStateException if the approval is not actionable.
     */
    public ApprovalState approve(
            String userId, String courseId, String approvalId) throws IOException {

        ApprovalState state = load(userId, courseId, approvalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Approval not found: " + approvalId));

        if (!state.isActionable()) {
            throw new IllegalStateException(
                    "Approval " + approvalId + " is " + state.status + " and cannot be approved.");
        }

        state.status = ApprovalState.Status.APPROVED;
        state.decidedAt = Instant.now();
        save(state);
        return state;
    }

    public Optional<ApprovalState> findLatestPending(
            String userId, String courseId) throws IOException {

        return listForCourse(userId, courseId).stream()
                .filter(s -> s.status == ApprovalState.Status.PENDING)
                .findFirst(); // listForCourse already sorts newest-first
    }

    /**
     * Atomically transition an approval from PENDING → REJECTED.
     */
    public ApprovalState reject(
            String userId, String courseId, String approvalId) throws IOException {

        ApprovalState state = load(userId, courseId, approvalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Approval not found: " + approvalId));

        if (!state.isActionable()) {
            throw new IllegalStateException(
                    "Approval " + approvalId + " is " + state.status + " and cannot be rejected.");
        }

        state.status = ApprovalState.Status.REJECTED;
        state.decidedAt = Instant.now();
        save(state);
        return state;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private File approvalDir(String userId, String courseId) {
        return new File("user-data/" + userId + "/courses/" + courseId + "/approvals");
    }
}
