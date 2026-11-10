package com.taskflow.backend.intelligence;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DeploymentAdvisoryService {
    public Map<String, Object> plan(IntelligenceStore.Repository repository, String target) {
        JsonNode analysis = repository.analysis();
        List<Map<String, Object>> modules = new ArrayList<>();
        for (JsonNode profile : analysis.path("buildProfiles")) modules.add(Map.of("directory", profile.path("directory").asString(),
                "framework", profile.path("framework").asString(), "ecosystem", profile.path("ecosystem").asString()));
        String rationale = "For " + repository.name() + ", " + modules.size() + " inspected build modules are available. "
                + (target.equals("ubuntu-vps") ? "A VPS with reviewed containers offers direct control; you own patching, backups, networking and availability."
                : "Use a managed application/container platform and a private managed database to reduce host operations; check runtime support, cost and regional requirements.");
        List<Map<String, String>> steps = List.of(
                Map.of("title", "Review service boundaries", "instructions", "Map each inspected module to a runtime service. Existing configuration evidence: " + analysis.path("configurationEvidence") + ". Inspect persistence dependencies manually; a manifest alone does not prove a database is required.", "command", "git status --short"),
                Map.of("title", "Build immutable artifacts", "instructions", "Use Workflow Builder, run tests in a trusted checkout, publish images tagged with a commit SHA, and record their digests. Keep .env and credentials outside image build contexts.", "command", "docker image inspect YOUR_REVIEWED_IMAGE@sha256:YOUR_DIGEST"),
                Map.of("title", "Configure environment and persistence", "instructions", "Store image references and port settings in deploy/.env with restricted permissions. Put secret values in the target platform's secret manager or server-only files. Use a private database connection, migrations and tested backups.", "command", "install -m 700 -d deploy"),
                Map.of("title", "Review Compose or managed platform settings", "instructions", "For a VPS, install Docker Engine on Ubuntu using its official guide, then review the generated Compose draft. For managed cloud, translate the same image, secret, health check, volume and networking requirements into platform settings. This app does not provision either target.", "command", "docker compose --env-file deploy/.env --file deploy/compose.taskflow.yaml config --quiet"),
                Map.of("title", "Add HTTPS and operational checks", "instructions", "Point your domain to the target, place Caddy or Nginx in front of loopback-bound application ports, allow only necessary firewall ports, configure HTTPS, and define a real health endpoint. Route structured logs to your log platform; monitor errors, latency, resource use and certificate expiry.", "command", "docker compose --env-file deploy/.env --file deploy/compose.taskflow.yaml logs --tail=100"),
                Map.of("title", "Verify release and rollback", "instructions", "Deploy only after approval in your own environment. Verify HTTPS health, a critical user flow, migrations and backups. Roll back by restoring the previous image digest; use backward-compatible migrations. Marking a plan reviewed is not verification of deployment.", "command", "curl --fail https://YOUR_DOMAIN/YOUR_HEALTH_PATH")
        );
        return Map.of("title", target.equals("ubuntu-vps") ? "Ubuntu VPS delivery plan" : "Managed cloud delivery plan", "rationale", rationale,
                "modules", modules, "steps", steps, "verified", false,
                "limitations", List.of("Architecture guidance is based on saved repository evidence, not a running-system inspection.", "Ports, data stores, scaling needs, costs and cloud credentials must be confirmed manually."),
                "links", List.of("https://docs.docker.com/engine/install/ubuntu/", "https://docs.docker.com/compose/how-tos/production/", "https://caddyserver.com/docs/automatic-https"));
    }
    public WorkflowGenerationService.Draft compose() {
        return new WorkflowGenerationService.Draft("deploy/compose.taskflow.yaml", """
                # REVIEW_REQUIRED: add one service per reviewed runtime module.
                # Set these variables in a server-only deploy/.env; never commit credentials.
                services:
                  app:
                    image: ${APP_IMAGE:?Set APP_IMAGE to an immutable reviewed image digest}
                    restart: unless-stopped
                    init: true
                    ports:
                      - "127.0.0.1:${APP_HOST_PORT:?Set APP_HOST_PORT}:${APP_CONTAINER_PORT:?Set APP_CONTAINER_PORT}"
                    env_file:
                      - app.env
                    logging:
                      driver: json-file
                      options:
                        max-size: 10m
                        max-file: "3"
                """, Map.of("notes", List.of("Create deploy/app.env with mode 600 on the server. Add the application's actual environment variables there.", "Review health checks, resource limits, database, persistent volumes and reverse proxy before production use. This draft does not deploy anything."), "requiredSecrets", List.of("Application-specific environment values (server only)"), "overwrite", false));
    }
}
