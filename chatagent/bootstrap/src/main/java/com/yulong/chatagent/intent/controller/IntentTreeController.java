package com.yulong.chatagent.intent.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.intent.application.IntentTreeFacadeService;
import com.yulong.chatagent.intent.model.request.SetIntentNodeKnowledgeBasesRequest;
import com.yulong.chatagent.intent.model.request.UpsertIntentNodeRequest;
import com.yulong.chatagent.intent.model.response.CreateIntentNodeResponse;
import com.yulong.chatagent.intent.model.response.GetIntentTreeResponse;
import com.yulong.chatagent.intent.model.vo.IntentVersionVO;
import com.yulong.chatagent.model.common.ApiResponse;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意图树后台管理入口。
 *
 * 这组接口只允许 ADMIN 访问，负责维护「内部助手」使用的意图树草稿：
 * 1. 平时创建/更新/删除的都是 draft(version = 0)；
 * 2. 点击 publish 后才会复制成一个新的 PUBLISHED 版本；
 * 3. 会话运行时不会直接读取 draft，而是读取当前激活的 published snapshot。
 */
@RestController
@RequestMapping("/api/admin/assistant/intent-tree")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class IntentTreeController {

    private final IntentTreeFacadeService intentTreeFacadeService;

    /**
     * 返回当前草稿树 + 已发布版本列表。
     *
     * 前端编辑器主要用这个接口渲染树；注意这里拿到的节点仍然是草稿节点，
     * 不代表线上 AgentRuntime 当前正在使用这棵树。
     */
    @GetMapping
    public ApiResponse<GetIntentTreeResponse> getIntentTree() {
        return ApiResponse.success(intentTreeFacadeService.getIntentTree());
    }

    /**
     * 在 draft 树中创建一个节点。
     *
     * 节点真正属于哪一层、能不能挂到指定 parent 下，会在 Facade 层校验：
     * DOMAIN 只能做根节点，CATEGORY 只能挂 DOMAIN，TOPIC 只能挂 CATEGORY。
     */
    @PostMapping("/nodes")
    public ApiResponse<CreateIntentNodeResponse> createIntentNode(@RequestBody UpsertIntentNodeRequest request) {
        return ApiResponse.success(intentTreeFacadeService.createIntentNode(request));
    }

    /**
     * 更新 draft 节点。
     *
     * 这里允许调整父节点、层级、意图类型、工具/知识库绑定等；
     * 但不能把节点移动到自己的子孙节点下面，否则会形成环。
     */
    @PatchMapping("/nodes/{nodeId}")
    public ApiResponse<Void> updateIntentNode(@PathVariable String nodeId,
                                              @RequestBody UpsertIntentNodeRequest request) {
        intentTreeFacadeService.updateIntentNode(nodeId, request);
        return ApiResponse.success();
    }

    /**
     * 删除 draft 节点及其整棵子树。
     *
     * 删除父节点时，子节点和相关知识库绑定都会一起清理，
     * 避免留下无法从根节点到达的“孤儿节点”。
     */
    @DeleteMapping("/nodes/{nodeId}")
    public ApiResponse<Void> deleteIntentNode(@PathVariable String nodeId) {
        intentTreeFacadeService.deleteIntentNode(nodeId);
        return ApiResponse.success();
    }

    /**
     * 给 KB 类型的 TOPIC 节点绑定知识库。
     *
     * 只有最终叶子节点，也就是 TOPIC + KB，才会真正收敛到知识库范围；
     * DOMAIN/CATEGORY 只是路由分类，不直接绑定检索资源。
     */
    @PutMapping("/nodes/{nodeId}/knowledge-bases")
    public ApiResponse<Void> setIntentNodeKnowledgeBases(@PathVariable String nodeId,
                                                         @RequestBody SetIntentNodeKnowledgeBasesRequest request) {
        intentTreeFacadeService.setIntentNodeKnowledgeBases(nodeId, request);
        return ApiResponse.success();
    }

    /**
     * 发布当前 draft 树。
     *
     * 发布不是把 draft 原地改成线上版本，而是复制出一份全新的 PUBLISHED snapshot，
     * 然后把 internal assistant 的 activeIntentVersion 指向新版本。
     */
    @PostMapping("/publish")
    public ApiResponse<Integer> publishIntentTreeSnapshot() {
        return ApiResponse.success(intentTreeFacadeService.publishIntentTreeSnapshot());
    }

    /**
     * 查询历史已发布版本，用于后台切换线上生效版本。
     */
    @GetMapping("/versions")
    public ApiResponse<List<IntentVersionVO>> getIntentVersions() {
        return ApiResponse.success(intentTreeFacadeService.getIntentVersions());
    }

    /**
     * 将某个已发布版本切成线上激活版本。
     *
     * 切换成功后会刷新 Redis 里的 active snapshot 缓存，
     * 后续 ConversationTurnPreparationService 会读到新的意图树。
     */
    @PutMapping("/versions/{version}/activate")
    public ApiResponse<Void> switchActiveIntentVersion(@PathVariable int version) {
        intentTreeFacadeService.switchActiveIntentVersion(version);
        return ApiResponse.success();
    }
}
