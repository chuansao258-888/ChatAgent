package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.model.request.InitializeAssistantFromTemplateRequest;
import com.yulong.chatagent.admin.model.response.GetAssistantTemplatesResponse;
import com.yulong.chatagent.admin.model.response.InitializeAssistantFromTemplateResponse;
import com.yulong.chatagent.admin.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.admin.port.AgentRepository;
import com.yulong.chatagent.admin.port.AssistantTemplateRepository;
import com.yulong.chatagent.agent.application.InternalAssistantService;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.intent.application.IntentTreeFacadeService;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.intent.model.request.CreateIntentNodeRequest;
import com.yulong.chatagent.intent.model.request.SetIntentNodeKnowledgeBasesRequest;
import com.yulong.chatagent.intent.model.response.CreateIntentNodeResponse;
import com.yulong.chatagent.intent.model.response.PublishIntentTreeResponse;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.AssistantTemplateDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantTemplateFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AssistantTemplateRepository assistantTemplateRepository;

    @Mock
    private InternalAssistantService internalAssistantService;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;

    @Mock
    private IntentNodeRepository intentNodeRepository;

    @Mock
    private IntentTreeFacadeService intentTreeFacadeService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ResourceAccessGuard resourceAccessGuard;

    private AssistantTemplateFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new AssistantTemplateFacadeServiceImpl(
                adminAccessService,
                assistantTemplateRepository,
                internalAssistantService,
                agentRepository,
                agentKnowledgeBaseRepository,
                intentNodeRepository,
                intentTreeFacadeService,
                knowledgeBaseRepository,
                resourceAccessGuard
        );
    }

    @Test
    void shouldListTemplatesForAdmin() {
        when(adminAccessService.requireAdmin()).thenReturn(LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build());
        when(assistantTemplateRepository.findAll()).thenReturn(List.of(
                AssistantTemplateDTO.builder()
                        .id("tpl-1")
                        .code("hr")
                        .name("HR Assistant")
                        .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                        .allowedTools(List.of("emailTool"))
                        .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                        .intentTree(List.of())
                        .builtIn(true)
                        .build()
        ));

        GetAssistantTemplatesResponse response = facadeService.getTemplates();

        assertThat(response.getTemplates()).hasSize(1);
        assertThat(response.getTemplates().get(0).getCode()).isEqualTo("hr");
    }

    @Test
    void shouldInitializeAssistantFromTemplateAndPublishSnapshot() {
        LoginUser admin = LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build();
        AgentDTO assistant = AgentDTO.builder()
                .id(InternalAssistantService.SYSTEM_ASSISTANT_ID)
                .name("ChatAgent")
                .description("old")
                .systemPrompt("old prompt")
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .allowedTools(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .activeIntentVersion(1)
                .build();
        AssistantTemplateDTO template = AssistantTemplateDTO.builder()
                .id("tpl-1")
                .code("it-ops")
                .name("IT Ops Assistant")
                .description("IT help")
                .systemPrompt("template prompt")
                .model(AgentDTO.ModelType.GLM_4_6)
                .allowedTools(List.of("emailTool", "dataBaseTool"))
                .chatOptions(AgentDTO.ChatOptions.builder()
                        .temperature(0.2)
                        .topP(0.9)
                        .messageLength(12)
                        .tokenBudget(4200)
                        .build())
                .intentTree(List.of(
                        AssistantTemplateDTO.IntentTreeNodeTemplateDTO.builder()
                                .code("domain")
                                .nodeLevel(IntentNodeLevel.DOMAIN)
                                .name("IT")
                                .enabled(true)
                                .sortOrder(0)
                                .build(),
                        AssistantTemplateDTO.IntentTreeNodeTemplateDTO.builder()
                                .code("topic")
                                .parentCode("domain")
                                .nodeLevel(IntentNodeLevel.TOPIC)
                                .name("Access")
                                .intentKind(IntentKind.KB)
                                .scopePolicy(ScopePolicy.FALLBACK_ALLOWED)
                                .bindSelectedKnowledgeBases(true)
                                .enabled(true)
                                .sortOrder(1)
                                .build()
                ))
                .build();

        when(adminAccessService.requireAdmin()).thenReturn(admin);
        when(assistantTemplateRepository.findById("tpl-1")).thenReturn(template);
        when(internalAssistantService.getRequiredAssistant()).thenReturn(assistant);
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-1", "kb-2"))).thenReturn(List.of("kb-1", "kb-2"));
        when(resourceAccessGuard.assertCanManageKnowledgeBase(admin, "kb-1")).thenReturn(null);
        when(resourceAccessGuard.assertCanManageKnowledgeBase(admin, "kb-2")).thenReturn(null);
        when(agentRepository.update(assistant)).thenReturn(true);
        when(intentNodeRepository.findByAgentIdAndVersion(InternalAssistantService.SYSTEM_ASSISTANT_ID, 0)).thenReturn(List.of(
                IntentNodeDTO.builder().id("draft-1").build()
        ));
        when(intentNodeRepository.deleteByIds(List.of("draft-1"))).thenReturn(true);
        when(intentTreeFacadeService.createIntentNode(any(CreateIntentNodeRequest.class)))
                .thenReturn(new CreateIntentNodeResponse("node-1"))
                .thenReturn(new CreateIntentNodeResponse("node-2"));
        when(intentTreeFacadeService.publishIntentTreeSnapshot()).thenReturn(new PublishIntentTreeResponse(3));

        InitializeAssistantFromTemplateRequest request = new InitializeAssistantFromTemplateRequest();
        request.setKnowledgeBaseIds(List.of("kb-1", "kb-2"));

        InitializeAssistantFromTemplateResponse response =
                facadeService.initializeAssistantFromTemplate("tpl-1", request);

        assertThat(response.getTemplateId()).isEqualTo("tpl-1");
        assertThat(response.getActiveIntentVersion()).isEqualTo(3);
        assertThat(assistant.getName()).isEqualTo("IT Ops Assistant");
        assertThat(assistant.getModel()).isEqualTo(AgentDTO.ModelType.GLM_4_6);
        verify(agentKnowledgeBaseRepository).replaceBindings(
                InternalAssistantService.SYSTEM_ASSISTANT_ID,
                List.of("kb-1", "kb-2")
        );

        ArgumentCaptor<CreateIntentNodeRequest> createCaptor = ArgumentCaptor.forClass(CreateIntentNodeRequest.class);
        verify(intentTreeFacadeService, org.mockito.Mockito.times(2)).createIntentNode(createCaptor.capture());
        List<CreateIntentNodeRequest> createRequests = createCaptor.getAllValues();
        assertThat(createRequests.get(0).getName()).isEqualTo("IT");
        assertThat(createRequests.get(0).getParentId()).isNull();
        assertThat(createRequests.get(1).getParentId()).isEqualTo("node-1");

        ArgumentCaptor<SetIntentNodeKnowledgeBasesRequest> bindingCaptor =
                ArgumentCaptor.forClass(SetIntentNodeKnowledgeBasesRequest.class);
        verify(intentTreeFacadeService).setIntentNodeKnowledgeBases(org.mockito.Mockito.eq("node-2"), bindingCaptor.capture());
        assertThat(bindingCaptor.getValue().getKnowledgeBaseIds()).containsExactly("kb-1", "kb-2");
    }
}
