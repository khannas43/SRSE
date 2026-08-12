package gov.rajasthan.smart.srse.web;

import gov.rajasthan.smart.srse.config.AnalyticalConnectionService;
import gov.rajasthan.smart.srse.config.OperationalConnectionService;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin write API for the two data-plane connections. Analytical (Presto)
 * takes effect immediately — see {@link AnalyticalConnectionService}.
 * Operational (DB2) is test-and-persist only — see
 * {@link OperationalConnectionService} for why it can't be hot-swapped.
 * Password is write-only: never echoed back in any response here, same
 * discipline as the read-only {@link ConnectionInfoController}.
 */
@RestController
@RequestMapping("/api/admin/connections")
public class ConnectionUpdateController {

    private final AnalyticalConnectionService analyticalConnectionService;
    private final OperationalConnectionService operationalConnectionService;

    public ConnectionUpdateController(AnalyticalConnectionService analyticalConnectionService,
                                      OperationalConnectionService operationalConnectionService) {
        this.analyticalConnectionService = analyticalConnectionService;
        this.operationalConnectionService = operationalConnectionService;
    }

    @PutMapping("/analytical")
    public UpdateConnectionResponse updateAnalytical(@RequestBody UpdateConnectionRequest req) {
        analyticalConnectionService.update(req.jdbcUrl(), req.username(), req.password(), req.driverClassName());
        // The service only swaps in the new pool after a successful test-connect,
        // so by the time we get here the live connection is exactly this.
        ConnectionInfoController.PlaneInfo plane = new ConnectionInfoController.PlaneInfo(
                req.jdbcUrl(), req.username(), req.driverClassName(), "up");
        return new UpdateConnectionResponse(plane, false);
    }

    @PutMapping("/operational")
    public UpdateConnectionResponse updateOperational(@RequestBody UpdateConnectionRequest req) {
        operationalConnectionService.update(req.jdbcUrl(), req.username(), req.password(), req.driverClassName());
        // Nothing changed live — the JPA EntityManagerFactory keeps using the
        // connection it booted with until the backend is restarted.
        return new UpdateConnectionResponse(null, true);
    }

    public record UpdateConnectionRequest(
            String jdbcUrl, String username, String password, String driverClassName) {
    }

    public record UpdateConnectionResponse(
            ConnectionInfoController.PlaneInfo plane, boolean restartRequired) {
    }
}
