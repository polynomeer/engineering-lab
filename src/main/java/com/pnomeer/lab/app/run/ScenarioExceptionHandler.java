package com.pnomeer.lab.app.run;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestControllerAdvice(assignableTypes = {ScenarioRunController.class, ExperimentCatalogController.class, com.pnomeer.lab.app.series.RunSeriesController.class, com.pnomeer.lab.app.series.RunSweepController.class})
public class ScenarioExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
    }
}
