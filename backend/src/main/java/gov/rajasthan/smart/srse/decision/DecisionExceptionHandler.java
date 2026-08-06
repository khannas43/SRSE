package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.scenario.ScenarioNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions from the decision seam to HTTP status codes.
 */
@RestControllerAdvice
public class DecisionExceptionHandler {

    @ExceptionHandler(ScenarioNotFoundException.class)
    public ResponseEntity<String> notFound(ScenarioNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(FieldResolver.UnknownFieldException.class)
    public ResponseEntity<String> badRequest(FieldResolver.UnknownFieldException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }
}
