import { ApiOutlined, LockOutlined } from "@ant-design/icons";
import { Button, Drawer, Form, Input, Select, Typography, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import { createMcpServer, updateMcpServer } from "../../api/admin.ts";
import type {
  CreateMcpServerRequest,
  McpAuthType,
  McpProtocol,
  McpServerVO,
  UpdateMcpServerRequest,
} from "../../types/admin.ts";

interface McpServerCreateDrawerProps {
  open: boolean;
  server?: McpServerVO | null;
  onClose: () => void;
  onSaved: (serverId: string) => Promise<void> | void;
}

interface CreateMcpServerFormValues {
  slug: string;
  name: string;
  description?: string;
  protocol: McpProtocol;
  authType: McpAuthType;
  endpointUrl: string;
  credentials?: string;
}

const PROTOCOL_OPTIONS: Array<{ label: string; value: McpProtocol }> = [
  { label: "HTTP", value: "HTTP" },
  { label: "SSE", value: "SSE" },
];

const AUTH_OPTIONS: Array<{ label: string; value: McpAuthType }> = [
  { label: "NONE", value: "NONE" },
  { label: "API_KEY", value: "API_KEY" },
  { label: "BEARER_TOKEN", value: "BEARER_TOKEN" },
];

export default function McpServerCreateDrawer({
  open,
  server,
  onClose,
  onSaved,
}: McpServerCreateDrawerProps) {
  const [form] = Form.useForm<CreateMcpServerFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const isEditMode = server != null;

  useEffect(() => {
    if (!open) {
      form.resetFields();
      return;
    }

    form.setFieldsValue({
      slug: server?.slug ?? "",
      name: server?.name ?? "",
      description: server?.description ?? "",
      protocol: (server?.protocol as McpProtocol | undefined) ?? "HTTP",
      authType: (server?.authType as McpAuthType | undefined) ?? "NONE",
      endpointUrl: server?.endpointUrl ?? "",
      credentials: "",
    });
  }, [form, open, server]);

  const authType = Form.useWatch("authType", form) ?? "NONE";
  const credentialsRequired =
    authType !== "NONE" && (!isEditMode || authType !== server?.authType);
  const credentialLabel = useMemo(() => {
    if (authType === "API_KEY") {
      return "API key";
    }
    if (authType === "BEARER_TOKEN") {
      return "Bearer token";
    }
    return "Credentials";
  }, [authType]);

  const handleSubmit = async (values: CreateMcpServerFormValues) => {
    setSubmitting(true);
    try {
      if (isEditMode && server) {
        const payload: UpdateMcpServerRequest = {
          slug: values.slug.trim(),
          name: values.name.trim(),
          description: values.description?.trim() || undefined,
          protocol: values.protocol,
          authType: values.authType,
          endpointUrl: values.endpointUrl.trim(),
          credentials: values.credentials?.trim() ? values.credentials.trim() : undefined,
        };
        await updateMcpServer(server.id, payload);
        message.success("MCP server updated.");
        await onSaved(server.id);
      } else {
        const payload: CreateMcpServerRequest = {
          slug: values.slug.trim(),
          name: values.name.trim(),
          description: values.description?.trim() || undefined,
          protocol: values.protocol,
          authType: values.authType,
          endpointUrl: values.endpointUrl.trim(),
          credentials: credentialsRequired ? values.credentials?.trim() || "" : undefined,
        };
        const serverId = await createMcpServer(payload);
        message.success("MCP server created. Run Test first, then Sync to expose tools.");
        await onSaved(serverId);
      }
      onClose();
      form.resetFields();
    } catch (error) {
      console.error(`Failed to ${isEditMode ? "update" : "create"} MCP server:`, error);
      message.error(isEditMode ? "Unable to update the MCP server." : "Unable to create the MCP server.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={520}
      title={isEditMode ? "Edit MCP server" : "Add MCP server"}
      className="admin-dark-drawer"
      destroyOnClose
    >
      <div className="space-y-5">
        <div className="rounded-section border border-white/[0.06] bg-white/[0.04] px-5 py-4">
          <div className="flex items-start gap-3">
            <span className="mt-0.5 rounded-xl bg-white/[0.06] p-2 text-white/55">
              <ApiOutlined />
            </span>
            <div>
              <Typography.Text className="block text-sm font-medium !text-white">
                {isEditMode ? "Update one remote MCP endpoint" : "Register one remote MCP endpoint"}
              </Typography.Text>
              <Typography.Text className="mt-2 block text-sm leading-6 !text-white/60">
                {isEditMode
                  ? "Edits to endpoint, auth, or credentials move the server back through validation and may require a fresh Test and Sync."
                  : "This creates the server catalog entry only. After saving, use Test to validate connectivity and Sync to pull remote tools into the runtime catalog."}
              </Typography.Text>
            </div>
          </div>
        </div>

        <Form<CreateMcpServerFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => {
            void handleSubmit(values);
          }}
        >
          <div className="grid gap-4 md:grid-cols-2">
            <Form.Item
              label="Slug"
              name="slug"
              rules={[
                { required: true, message: "Slug is required." },
                {
                  pattern: /^[a-z0-9_]+$/,
                  message: "Use lowercase letters, numbers, and underscores only.",
                },
              ]}
            >
              <Input size="large" placeholder="google_search" />
            </Form.Item>

            <Form.Item
              label="Display name"
              name="name"
              rules={[{ required: true, message: "Name is required." }]}
            >
              <Input size="large" placeholder="Google Search MCP" />
            </Form.Item>
          </div>

          <Form.Item label="Description" name="description">
            <Input.TextArea
              autoSize={{ minRows: 2, maxRows: 4 }}
              placeholder="What this MCP server is used for"
            />
          </Form.Item>

          <div className="grid gap-4 md:grid-cols-2">
            <Form.Item
              label="Protocol"
              name="protocol"
              rules={[{ required: true, message: "Protocol is required." }]}
            >
              <Select size="large" options={PROTOCOL_OPTIONS} />
            </Form.Item>

            <Form.Item
              label="Auth type"
              name="authType"
              rules={[{ required: true, message: "Auth type is required." }]}
            >
              <Select size="large" options={AUTH_OPTIONS} />
            </Form.Item>
          </div>

          <Form.Item
            label="Endpoint URL"
            name="endpointUrl"
            rules={[
              { required: true, message: "Endpoint URL is required." },
              { type: "url", message: "Enter a valid absolute URL." },
            ]}
          >
            <Input size="large" placeholder="https://example.com/mcp" />
          </Form.Item>

          <Form.Item
            label={credentialLabel}
            name="credentials"
            rules={
              credentialsRequired
                ? [{ required: true, message: `${credentialLabel} is required.` }]
                : undefined
            }
            extra={
              credentialsRequired
                ? authType === "API_KEY"
                  ? "Stored encrypted and sent as X-API-Key."
                  : "Stored encrypted and sent as Authorization: Bearer ..."
                : isEditMode && authType !== "NONE"
                  ? "Leave blank to keep the existing secret unchanged."
                  : "No credentials will be attached to outbound requests."
            }
          >
            <Input.Password
              size="large"
              disabled={!credentialsRequired}
              placeholder={
                credentialsRequired
                  ? `Paste ${credentialLabel.toLowerCase()}`
                  : isEditMode && authType !== "NONE"
                    ? "Leave blank to keep existing secret"
                    : "Disabled for NONE"
              }
              prefix={<LockOutlined />}
            />
          </Form.Item>

          <div className="flex items-center justify-end gap-3 pt-2">
            <Button onClick={onClose}>Cancel</Button>
            <Button type="primary" htmlType="submit" loading={submitting}>
              {isEditMode ? "Save changes" : "Add server"}
            </Button>
          </div>
        </Form>
      </div>
    </Drawer>
  );
}
