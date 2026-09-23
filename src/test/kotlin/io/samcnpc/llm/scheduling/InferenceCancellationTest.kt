package io.samcnpc.llm.scheduling

import io.samcnpc.llm.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class InferenceCancellationTest {
    @Test fun cancellationBeforeProviderEntryProvesNoSubmissionAndBlocksEntry() {
        val cancellation = InferenceCancellation()
        cancellation.cancel()
        assertFalse(cancellation.beginInvocation())
        assertEquals(LlmSubmission.NOT_SENT, cancellation.submission())
    }

    @Test fun cancellationDuringProviderEntryStaysUncertainUntilTheCallIsPublished() {
        val cancellation = InferenceCancellation()
        assertTrue(cancellation.beginInvocation())
        cancellation.cancel()
        assertEquals(LlmSubmission.UNKNOWN, cancellation.submission())
        var cancelled = 0
        val future = CompletableFuture.completedFuture<LlmResponse>(LlmResponse.Failed(UUID.randomUUID(), LlmFailure.BUSY))
        cancellation.attach(LlmCall(future, LlmSubmission.NOT_SENT) { cancelled++; true })
        assertEquals(1, cancelled)
        assertEquals(LlmSubmission.NOT_SENT, cancellation.submission())
        assertFalse(cancellation.beginInvocation())
    }

    @Test fun customProvidersDefaultToUnknownAndCannotInventARefundOnCancel() {
        val cancellation = InferenceCancellation()
        assertTrue(cancellation.beginInvocation())
        cancellation.attach(LlmCall(CompletableFuture()) { true })
        cancellation.cancel()
        assertEquals(LlmSubmission.UNKNOWN, cancellation.submission())
    }
}
