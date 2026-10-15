package com.portfolio.mdslab.messaging;

import com.portfolio.mdslab.repository.ProcessedMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdempotencyDatabaseFailureTest {
    @Test void onlyMysqlDuplicateKeyCanReachRetryDecision(){
        var repository=mock(ProcessedMessageRepository.class);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate",new SQLException("duplicate","23000",1062)));
        when(repository.tryRetryFailed(eq("id"),any())).thenReturn(1);
        assertTrue(new IdempotentMessageProcessor(repository).tryBegin("id","type"));
        verify(repository).tryRetryFailed(eq("id"),any());
    }
    @Test void connectivityDeadlockAndOtherConstraintsAreNotAcknowledgedAsDuplicates(){
        for(var failure:new DataAccessException[]{new DataAccessResourceFailureException("offline"),new CannotAcquireLockException("deadlock"),
                new DataIntegrityViolationException("null",new SQLException("null","23000",1048))}){
            var repository=mock(ProcessedMessageRepository.class);
            when(repository.saveAndFlush(any())).thenThrow(failure);
            assertSame(failure,assertThrows(DataAccessException.class,()->new IdempotentMessageProcessor(repository).tryBegin("id","type")));
            verify(repository,never()).tryRetryFailed(any(),any());
        }
    }
}
