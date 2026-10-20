package dev.pulseops.worker;

import com.sun.net.httpserver.HttpServer;
import dev.pulseops.config.WebClientConfig;
import dev.pulseops.entity.Monitor;
import dev.pulseops.service.AlertService;
import dev.pulseops.service.CheckResultService;
import dev.pulseops.service.IncidentService;
import dev.pulseops.service.MonitorService;
import dev.pulseops.service.MonitoringMetricsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorWorkerStatusHandlingTest {

    private static HttpServer server;
    private static String baseUrl;

    private MonitorService monitorService;
    private CheckResultService checkResultService;
    private IncidentService incidentService;
    private AlertService alertService;
    private MonitorWorker worker;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // port 0 = any free port
        // /status/{code} -> responds with that status and an empty body (like httpbin)
        server.createContext("/status/", ex -> {
            int code = Integer.parseInt(ex.getRequestURI().getPath().substring("/status/".length()));
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        // /slow -> waits 3s before answering, to exercise the timeout path
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        monitorService = mock(MonitorService.class);
        checkResultService = mock(CheckResultService.class);
        incidentService = mock(IncidentService.class);
        alertService = mock(AlertService.class);
        // Constructor order = field order in MonitorWorker (@RequiredArgsConstructor)
        worker = new MonitorWorker(
                mock(RedisTemplate.class),
                monitorService,
                checkResultService,
                incidentService,
                alertService,
                new WebClientConfig().webClientBuilder(),
                mock(MonitoringMetricsService.class));
    }

    private Monitor givenMonitor(String url, int expected, int timeoutSeconds) {
        Monitor m = Monitor.builder()
                .id(1L).name("test").url(url)
                .expectedStatusCode(expected)
                .timeoutSeconds(timeoutSeconds)
                .build();
        when(monitorService.getEntityById(1L)).thenReturn(m);
        return m;
    }

    @ParameterizedTest(name = "GET /status/{0}, expected {1} -> up={2}")
    @CsvSource({
            "200, 200, true",
            "200, 500, false",
            "500, 500, true", // the original bug: was reported DOWN
            "500, 200, false",
            "404, 404, true",
    })
    void comparesActualStatusWithExpectedStatus(int actual, int expected, boolean up) {
        Monitor m = givenMonitor(baseUrl + "/status/" + actual, expected, 5);

        worker.processMonitor(1L);

        String err = up ? null : "Expected " + expected + " but got " + actual;
        // statusCode must be recorded even for 5xx (it was null before the fix)
        verify(checkResultService).save(eq(m), eq(actual), anyLong(), eq(up),
                up ? ArgumentMatchers.<String>isNull() : eq(err));
        verify(monitorService).updateLastCheckedAt(1L);

        if (up) {
            verify(incidentService).resolveIfOpen(m);
            verify(incidentService, never()).openOrUpdate(any(), any());
        } else {
            verify(incidentService).openOrUpdate(m, err);
            verify(incidentService, never()).resolveIfOpen(any());
        }
    }

    @Test
    void recoveryFiresRecoveredAlertWhenIncidentWasOpen() {
        Monitor m = givenMonitor(baseUrl + "/status/500", 500, 5);
        when(incidentService.hasOpenIncident(1L)).thenReturn(true);

        worker.processMonitor(1L);

        verify(incidentService).resolveIfOpen(m);
        verify(alertService).fireAlerts(eq(m), contains("RECOVERED"));
    }

    @Test
    void timeoutIsAFailureWithReadableReason() {
        Monitor m = givenMonitor(baseUrl + "/slow", 200, 1);

        worker.processMonitor(1L);

        verify(checkResultService).save(eq(m), ArgumentMatchers.<Integer>isNull(), anyLong(),
                eq(false), eq("Timed out after 1s"));
        verify(incidentService).openOrUpdate(m, "Timed out after 1s");
    }

    @Test
    void connectionRefusedIsAFailure() {
        // Nothing listens on port 1, so the connection is refused immediately.
        Monitor m = givenMonitor("http://127.0.0.1:1/", 200, 5);

        worker.processMonitor(1L);

        verify(checkResultService).save(eq(m), ArgumentMatchers.<Integer>isNull(), anyLong(),
                eq(false), startsWith("Connection failed"));
        verify(incidentService).openOrUpdate(eq(m), startsWith("Connection failed"));
    }
}