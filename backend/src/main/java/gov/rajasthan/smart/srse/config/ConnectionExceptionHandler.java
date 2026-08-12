package gov.rajasthan.smart.srse.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps admin connection-editing exceptions to HTTP status codes. */
@RestControllerAdvice
public class ConnectionExceptionHandler {

    @ExceptionHandler(ConnectionTestFailedException.class)
    public ResponseEntity<String> badRequest(ConnectionTestFailedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}
