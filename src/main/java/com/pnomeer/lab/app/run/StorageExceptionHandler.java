package com.pnomeer.lab.app.run;

import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class StorageExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        LoggerFactory.getLogger(getClass()).error("stage=storage reason=database_unavailable", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable; run evidence could not be read or written"));
    }
}
