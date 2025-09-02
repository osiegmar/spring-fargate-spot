package de.siegmar.springfargatespot;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.web.context.support.ServletRequestHandledEvent;

/// Example Spring Boot application that implements a delayed shutdown phase.
///
/// During this phase, the application continues to serve requests but reports
/// an 'out-of-service' status via a custom HealthIndicator.
///
/// This approach prevents 5XX errors when running on AWS Fargate Spot instances,
/// allowing the load balancer health check to detect unhealthiness during shutdown
/// and stop sending new requests to the instance in time.
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    /// Delay between receiving the shutdown signal and actually shutting down
    /// the Spring context.
    ///
    /// During this delay, the application will continue processing requests,
    /// while reporting an out-of-service health status.
    /// This gives the load balancer time to detect the instance as unhealthy
    /// and reroute traffic.
    ///
    /// The delay must be *long enough* to cover load balancer health checks.
    /// For example, if the health check interval is 10 seconds and
    /// 2 consecutive failures are required to mark the instance as unhealthy,
    /// set the delay to at least 20 seconds plus some buffer.
    ///
    /// After this delay, the application context is closed.
    /// Spring Boot’s graceful shutdown will then wait for active
    /// (long-running) requests to finish but reject new ones.
    ///
    /// **Note:** The total shutdown duration (this delay +
    /// Spring's graceful shutdown) must be within the ECS stop timeout,
    /// which defaults to 30 seconds but can be increased up to 120 seconds.
    /// Otherwise, the container might be forcefully terminated.
    private static final Duration SHUTDOWN_DELAY = Duration.ofSeconds(25);

    private static volatile boolean SHUTTING_DOWN;

    public static void main(final String[] args) {
        final var app = new SpringApplication(Application.class);

        // Disable Spring Boot's built-in shutdown hook
        app.setRegisterShutdownHook(false);

        final ConfigurableApplicationContext context = app.run(args);

        // Add a custom JVM shutdown hook to delay actual shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SHUTTING_DOWN = true;

            LOG.info("Shutdown signal received, pausing {} before shutdown",
                SHUTDOWN_DELAY);

            try {
                // Pause to allow load balancer deregistration
                Thread.sleep(SHUTDOWN_DELAY);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            LOG.info("Shutting down application context");
            context.close();
        }));
    }

    /// Custom HealthIndicator which reports service status based on shutdown
    /// state.
    ///
    /// When shutting down, reports `OUT_OF_SERVICE` so load balancer marks
    /// the instance unhealthy. Otherwise, reports `UP`.
    @Bean
    public HealthIndicator livenessHealthIndicator() {
        return () -> SHUTTING_DOWN
            ? Health.outOfService().build()
            : Health.up().build();
    }

    /// Event listener for handled HTTP requests.
    ///
    /// Logs requests processed during the shutdown phase.
    /// Normally, these would be rejected when using Spring Boot's default
    /// graceful shutdown, but here they are still served.
    @EventListener
    public void requestEvent(final ServletRequestHandledEvent event) {
        if (SHUTTING_DOWN) {
            LOG.info("Request handled during shutdown phase: url={}, status={}",
                event.getRequestUrl(), event.getStatusCode());
        }
    }

}
