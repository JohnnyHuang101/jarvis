package com.jhsup;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Durable checkpoint written to disk after every stage transition.
 * Path: user-data/{userId}/courses/{courseId}/pipeline-state.json
 *
 * On any request, the controller reads this file first and resumes
 * from currentStage instead of restarting the whole pipeline.
 */
public record PipelineState(
    String userId,
    String courseId,
    PipelineStage currentStage,
    int retryCount,
    List<AgenticService.ExamSegment> examSegments,
    Map<String, List<AgenticService.CalendarEventRequest>> calendarEvents,  // examName → events
    Map<String, String> guideFilePaths,                                      // eventSummary → .md path
    List<String> approvedEventIds,
    Instant lastUpdated,
    String lastError
) {
    public enum PipelineStage {
        INIT,
        EXAMS_EXTRACTED,
        EXAMS_VALIDATED,
        CALENDAR_GENERATED,
        CALENDAR_VALIDATED,
        GUIDES_GENERATED,
        GUIDES_VALIDATED,
        AWAITING_APPROVAL,
        PUBLISHED,
        FAILED
    }

    /** Return a copy advanced to the next stage, resetting retryCount. */
    public PipelineState advance(PipelineStage next) {
        return new PipelineState(
            userId, courseId, next, 0,
            examSegments, calendarEvents, guideFilePaths,
            approvedEventIds, Instant.now(), null
        );
    }

    /** Return a copy recording a failure on the current stage. */
    public PipelineState withError(String error) {
        return new PipelineState(
            userId, courseId, PipelineStage.FAILED, retryCount + 1,
            examSegments, calendarEvents, guideFilePaths,
            approvedEventIds, Instant.now(), error
        );
    }
}
