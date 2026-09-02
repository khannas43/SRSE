package gov.rajasthan.smart.srse.config;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Turns data-access failures into something an officer can act on.
 *
 * <p><b>Why this exists.</b> Nothing mapped {@link DataAccessException}, so any
 * failure reaching Presto or DB2 fell through to Spring's default handler and
 * surfaced as a bare {@code {"status":500,"error":"Internal Server Error"}}.
 * Observed for real: the Presto container OOMed, and every rule Preview then
 * returned that message. It names no cause, distinguishes a dead lakehouse from
 * a genuine bug in no way at all, and sent the reader looking for a fault in
 * the rule they had just built.
 *
 * <p><b>The distinction that matters.</b> A query that could not run because the
 * lakehouse is unreachable is an INFRASTRUCTURE outage — retryable, nobody's
 * rule is wrong, and {@code 503} says so. A query that ran and failed is a
 * different thing and stays {@code 500}, but now carries the driver's message
 * so it can actually be diagnosed instead of being anonymised away.
 *
 * <p>The connectivity test walks the cause chain rather than matching on the
 * Spring exception type, because the PrestoDB driver reports a dropped
 * connection as a generic {@code UncategorizedSQLException} wrapping a
 * {@link SocketException} — it does not map to Spring's
 * {@code DataAccessResourceFailureException} the way a well-behaved driver
 * would. Matching on the type alone would have missed the exact case this was
 * written for.
 *
 * <p>Only affects SYNCHRONOUS failures. The Analysis tab's streaming match
 * handles its own errors mid-stream (see {@code RecordMatchService}), because
 * by then the response body is already being written and the status line is long gone.
 */
@RestControllerAdvice
public class DataAccessExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> dataAccessFailure(DataAccessException ex) {
        if (isConnectivityFailure(ex)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Lakehouse connection unavailable — the query could not be sent. "
                            + "Check the analytical (Presto) connection on the Admin page, then retry. "
                            + "Underlying error: " + rootMessage(ex));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Query failed: " + rootMessage(ex));
    }

    /**
     * Hard bound on cause-chain traversal. {@code Throwable.initCause} rejects
     * self-causation but NOT a cycle (a causes b, b causes a), so an identity
     * check alone is not enough to guarantee termination. A depth cap makes
     * both walks below total regardless of what the chain looks like.
     */
    private static final int MAX_CAUSE_DEPTH = 50;

    /** True when the failure is a lost/refused connection rather than a rejected query. */
    private static boolean isConnectivityFailure(Throwable ex) {
        Throwable t = ex;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof SocketException
                    || t instanceof SocketTimeoutException
                    || t instanceof ConnectException
                    || t instanceof UnknownHostException) {
                return true;
            }
            // Presto's driver wraps transport failures in a plain IOException;
            // checked last so the more specific types above win.
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        for (int depth = 0; root.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (root.getCause() == root) {
                break;
            }
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null && !message.isBlank()
                ? message
                : root.getClass().getSimpleName();
    }
}
