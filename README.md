# Spring Boot on Fargate Spot

This project demonstrates how to handle graceful shutdowns for Spring Boot applications running on AWS Fargate Spot Instances, addressing the **quirky** way that Fargate Spot handles shutdowns.

## The challenge

Normally, when ECS stops a service task behind a load balancer, ECS **first triggers deregistration of the task from the associated load balancer's target group**. This initiates connection draining based on the load balancer's deregistration delay settings, ensuring that existing connections complete gracefully.
**After** the deregistration delay concludes, ECS sends a `SIGTERM` signal to the containers in the task, allowing the application time to shut down properly.

**This process does not apply when AWS stops a Fargate Spot Instance due to capacity needs!**

When AWS needs to reclaim capacity, it sends a `SIGTERM` signal to the Fargate Spot Instance **without** first deregistering it from the load balancer. For details, see [AWS Knowledge Center on Fargate Spot Termination Notice](https://repost.aws/knowledge-center/fargate-spot-termination-notice).

In 2023, AWS announced they improved this behavior – see [AWS announcement from February 2023](https://aws.amazon.com/about-aws/whats-new/2023/02/amazon-elastic-container-service-accuracy-service-load-balancing/):

> Additionally, Amazon ECS will now deregister your task running on Fargate Spot, if it receives a spot termination notice, before issuing a SIGTERM message to inform the task that it needs to stop.

However, this does not appear to work reliably:
- In September 2025, multiple users [reported](https://github.com/aws/containers-roadmap/issues/2673) 502 errors correlating with Fargate Spot interruptions, with logs showing the `SpotInterruption` event arriving at the same time or even *after* the SIGTERM signal – the opposite of the promised behavior.
- The official [Fargate capacity providers documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/fargate-capacity-providers.html) covers Spot interruptions in detail (SIGTERM, two-minute warning, stopTimeout) but does not mention automatic load balancer deregistration at all.
- An [AWS-authored Lambda workaround](https://gist.github.com/jicowan/ad5e13d12577b41a22f83ed91a3e61bf) (created in 2021, last updated November 2023) explicitly states: *"ECS Fargate tasks that are stopped by Spot interruptions are not deregistered from load balancers automatically."*

The graceful shutdown feature of Spring Boot (since version 2.3) is designed to handle `SIGTERM` signals by **allowing existing requests** to complete before shutting down. However, upon receiving `SIGTERM`, it immediately **rejects any new requests**. As stated in the [Spring Boot documentation](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html):

> Jetty, Reactor Netty, and Tomcat will stop accepting new requests at the network layer. Undertow will accept new connections but respond immediately with a service unavailable (503) response.

Since the task remains registered in the load balancer's target group, the load balancer continues to send new requests to it until it is marked unhealthy by health checks. Because Spring Boot rejects new requests during shutdown, this results in "Target Connection Errors" and eventually "ELB 5xx" errors returned to clients.

## The solution

To mitigate this, I implemented the following steps:

- Added a custom shutdown hook that delays the actual shutdown process for a few seconds. During this delay, the application continues serving requests, but a custom health indicator reports the application as unhealthy. This causes the load balancer to stop routing new requests to the instance once it fails health checks.
- Disabled Spring Boot’s built-in shutdown hook by calling `SpringApplication.setRegisterShutdownHook(false)` to prevent interference with the custom shutdown process.
- Configured the load balancer's health check settings to quickly mark the instance as unhealthy.
- Set the ECS task definition’s stop timeout to 120 seconds — the maximum allowed value for Fargate Spot tasks.

An alternative approach would be to programmatically deregister the task from the load balancer using the AWS SDK upon receiving the `SIGTERM` signal, as suggested in [AWS’s blog on graceful shutdowns with ECS](https://aws.amazon.com/de/blogs/containers/graceful-shutdowns-with-ecs/). This could be implemented within the custom shutdown hook, a sidecar container, or an AWS Lambda function triggered by an EventBridge rule. I chose to avoid this added complexity.

## Demonstration

This demo application exposes two endpoints:

- A root endpoint `/` that simply returns "Hello, World!".
- A standard actuator health endpoint `/actuator/health`.

When the application is running normally, both endpoints return HTTP 200 OK status:

- Request `curl http://localhost:8080` returns "Hello, World!"
- Request `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}`

When the application is stopping, this changes:

- Request `curl http://localhost:8080` still returns "Hello, World!"
- Request `curl http://localhost:8080/actuator/health` returns `{"status":"OUT_OF_SERVICE"}`
