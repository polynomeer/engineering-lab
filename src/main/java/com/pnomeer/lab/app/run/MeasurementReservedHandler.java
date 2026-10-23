package com.pnomeer.lab.app.run;

import com.pnomeer.lab.runtime.DurableExperimentRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every submission path reports a series-held runner the same way, whichever controller received it. */
@RestControllerAdvice
public class MeasurementReservedHandler {
    @ExceptionHandler(DurableExperimentRunner.MeasurementReserved.class)
    public ResponseEntity<ProblemDetail> reserved(DurableExperimentRunner.MeasurementReserved exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
    }
}
