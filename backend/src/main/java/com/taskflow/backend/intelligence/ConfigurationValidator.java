package com.taskflow.backend.intelligence;

import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ConfigurationValidator {
    public Map<String, Object> validate(String kind, String content) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (content == null || content.isBlank() || content.length() > 65536) errors.add("Configuration must contain 1–65536 characters.");
        else {
            if (Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}").matcher(content).find())
                errors.add("A credential-like value was detected. Remove it and use a secret reference.");
            if (!kind.equals("docker")) {
                try {
                    LoaderOptions options = new LoaderOptions();
                    options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(10); options.setCodePointLimit(65536);
                    Object loaded = new Yaml(new SafeConstructor(options)).load(content);
                    if (!(loaded instanceof Map<?, ?> map)) errors.add("Expected a YAML mapping.");
                    else if (kind.equals("workflow")) {
                        if (!map.containsKey("on") && !map.containsKey(Boolean.TRUE)) errors.add("Workflow needs trigger events.");
                        if (!(map.get("jobs") instanceof Map<?, ?> jobs) || jobs.isEmpty()) errors.add("Workflow needs at least one job.");
                        if (!map.containsKey("permissions")) warnings.add("Declare minimal workflow permissions explicitly.");
                        if (content.contains("pull_request_target")) errors.add("Privileged pull_request_target workflows are not supported.");
                        if (Pattern.compile("uses:\\s*[^\\s]+@(?:v[0-9]|main|master)").matcher(content).find()) warnings.add("Pin third-party actions to reviewed full commit SHAs before production use.");
                    } else if (kind.equals("deployment") && !map.containsKey("services")) errors.add("Compose needs a services mapping.");
                } catch (Exception exception) { errors.add("YAML syntax is invalid or exceeds parser safety limits."); }
            } else if (!content.contains("FROM ")) errors.add("Dockerfile needs a FROM instruction.");
            if (content.contains("REVIEW_REQUIRED")) warnings.add("Replace every REVIEW_REQUIRED placeholder before use.");
        }
        warnings.add("Static validation only. Commands, action versions, secrets, builds and deployments have not been executed or verified.");
        return Map.of("valid", errors.isEmpty(), "errors", errors, "warnings", warnings, "executed", false);
    }
}
