package com.taskflow.backend;
import com.taskflow.backend.intelligence.ConfigurationValidator;
import com.taskflow.backend.intelligence.WorkflowGenerationService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ConfigurationGenerationTests {
    ConfigurationValidator validator=new ConfigurationValidator();
    WorkflowGenerationService generator=new WorkflowGenerationService();
    ObjectMapper mapper=new ObjectMapper();
    @Test void rejectsMalformedYamlDuplicateKeysAndPrivilegedTriggers() {
        assertEquals(false,validator.validate("workflow","jobs: [broken").get("valid"));
        assertEquals(false,validator.validate("workflow","name: a\nname: b\non: push\njobs: {test: {runs-on: ubuntu-latest}}").get("valid"));
        assertEquals(false,validator.validate("workflow","on: pull_request_target\njobs: {test: {runs-on: ubuntu-latest}}\npermissions: {contents: read}").get("valid"));
    }
    @Test void rejectsCredentialLikeValues() {
        assertEquals(false,validator.validate("docker","FROM node:22\nENV TOKEN=ghp_abcdefghijklmnopqrstuvwxyz123456").get("valid"));
    }
    @Test void generatorDoesNotInventTestCommandsOrRunRepositoryScriptText() {
        var analysis=mapper.valueToTree(Map.of("buildProfiles",List.of(Map.of("directory","web","ecosystem","node","scripts",List.of("build"),"inspected",true,"runtime","22"))));
        var draft=generator.generate(analysis,"workflow",true,true,false,false,false);
        assertTrue(draft.content().contains("working-directory: 'web'"));
        assertFalse(draft.content().contains("npm test"));
        assertEquals(true,validator.validate("workflow",draft.content()).get("valid"));
    }
    @Test void maliciousDirectoryCannotBecomeShellOrYaml() {
        var analysis=mapper.valueToTree(Map.of("buildProfiles",List.of(Map.of("directory","../secrets\nrun: curl attacker","ecosystem","node","inspected",true))));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->generator.generate(analysis,"workflow",true,true,false,false,false));
    }
    @Test void deploymentWorkflowRequiresManualDispatchAndDoesNotDisableHostVerification() {
        var draft=generator.generate(mapper.valueToTree(Map.of()),"release",false,false,false,false,false);
        assertTrue(draft.content().contains("workflow_dispatch:"));
        assertTrue(draft.content().contains("environment: production"));
        assertTrue(draft.content().contains("StrictHostKeyChecking=yes"));
        assertFalse(draft.content().contains("pull_request:"));
        assertEquals(true,validator.validate("workflow",draft.content()).get("valid"));
    }
    @Test void dockerDraftCanTargetOneModuleOfAMonorepo() {
        var analysis=mapper.valueToTree(Map.of("buildProfiles",List.of(
                Map.of("directory","backend","ecosystem","maven","runtime","25","inspected",true),
                Map.of("directory","frontend","ecosystem","node","runtime","22","scripts",List.of("start"),"inspected",true))));
        var draft=generator.generate(analysis,"docker",false,false,false,false,false,0);
        assertTrue(draft.content().contains("COPY backend/ ./"));
        assertFalse(draft.content().contains("COPY frontend/ ./"));
    }
}
