import { LockOutlined, RobotOutlined, UserOutlined } from "@ant-design/icons";
import { Button, Card, Form, Input, Segmented, Typography } from "antd";
import { useState } from "react";
import type { LoginRequest, RegisterRequest } from "../../api/auth.ts";
import { useAuth } from "../../hooks/useAuth.ts";

const { Paragraph, Title, Text } = Typography;

type AuthMode = "login" | "register";

interface AuthFormValues {
  username: string;
  password: string;
  confirmPassword?: string;
}

export default function LoginPage() {
  const { login, register } = useAuth();
  const [mode, setMode] = useState<AuthMode>("login");
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<AuthFormValues>();

  const isRegisterMode = mode === "register";

  const handleFinish = async (values: AuthFormValues) => {
    setSubmitting(true);
    try {
      if (isRegisterMode) {
        const registerRequest: RegisterRequest = {
          username: values.username,
          password: values.password,
        };
        await register(registerRequest);
      } else {
        const loginRequest: LoginRequest = {
          username: values.username,
          password: values.password,
        };
        await login(loginRequest);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-[radial-gradient(circle_at_top,_#f8fafc,_#e2e8f0_55%,_#cbd5e1)] px-6 py-10">
      <div className="mx-auto flex min-h-[calc(100vh-5rem)] max-w-5xl items-center justify-center">
        <div className="grid w-full gap-8 lg:grid-cols-[1.1fr_0.9fr]">
          <div className="hidden rounded-[32px] border border-white/50 bg-slate-900/90 p-10 text-slate-100 shadow-[0_30px_80px_rgba(15,23,42,0.35)] lg:flex lg:flex-col lg:justify-between">
            <div className="space-y-6">
              <div className="inline-flex items-center gap-3 rounded-full border border-sky-400/20 bg-sky-400/10 px-4 py-2 text-sm text-sky-200">
                <RobotOutlined />
                ChatAgent Console
              </div>
              <Title level={1} style={{ color: "#f8fafc", marginBottom: 0 }}>
                管理你的智能体、知识库和会话流转
              </Title>
              <Paragraph style={{ color: "#cbd5e1", fontSize: 16, marginBottom: 0 }}>
                登录或注册后使用同一套工作台管理知识库入库、智能体配置和对话历史。refresh
                token 由浏览器以 HttpOnly Cookie 保存，前端只保留 access token。
              </Paragraph>
            </div>
            <div className="grid gap-4 text-sm text-slate-300">
              <div className="rounded-2xl border border-white/10 bg-white/5 px-5 py-4">
                统一接入 access token、自动续签和 Cookie 刷新逻辑，后续保护更多接口时不需要逐页补认证代码。
              </div>
              <div className="rounded-2xl border border-white/10 bg-white/5 px-5 py-4">
                注册成功后会直接建立会话，便于你马上验证整个登录链，而不需要手动准备加密后的密码。
              </div>
            </div>
          </div>

          <Card
            className="overflow-hidden rounded-[28px] border-0 shadow-[0_30px_80px_rgba(15,23,42,0.18)]"
            styles={{ body: { padding: 0 } }}
          >
            <div className="bg-slate-950 px-8 py-6 text-white">
              <div className="flex items-center gap-3">
                <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-sky-400/20 text-sky-200">
                  <RobotOutlined style={{ fontSize: 20 }} />
                </div>
                <div>
                  <Text style={{ color: "#cbd5e1" }}>ChatAgent</Text>
                  <Title level={3} style={{ color: "#fff", margin: 0 }}>
                    {isRegisterMode ? "创建账号" : "登录工作台"}
                  </Title>
                </div>
              </div>
            </div>

            <div className="space-y-6 bg-white px-8 py-8">
              <div className="space-y-4">
                <Segmented<AuthMode>
                  block
                  options={[
                    { label: "登录", value: "login" },
                    { label: "注册", value: "register" },
                  ]}
                  value={mode}
                  onChange={(value) => {
                    setMode(value);
                    form.resetFields();
                  }}
                />
                <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {isRegisterMode
                    ? "注册成功后会直接进入系统，并由后端写入 refresh token Cookie。"
                    : "登录成功后前端只保存 access token，请求层会自动附带 Cookie 并在 401 时尝试续签。"}
                </Paragraph>
              </div>

              <Form<AuthFormValues> form={form} layout="vertical" onFinish={handleFinish}>
                <Form.Item
                  label="用户名"
                  name="username"
                  rules={[
                    { required: true, message: "请输入用户名" },
                    { min: 3, message: "用户名至少 3 个字符" },
                  ]}
                >
                  <Input
                    size="large"
                    prefix={<UserOutlined />}
                    placeholder="请输入用户名"
                    autoComplete="username"
                  />
                </Form.Item>

                <Form.Item
                  label="密码"
                  name="password"
                  rules={[
                    { required: true, message: "请输入密码" },
                    { min: 6, message: "密码至少 6 位" },
                  ]}
                >
                  <Input.Password
                    size="large"
                    prefix={<LockOutlined />}
                    placeholder="请输入密码"
                    autoComplete={isRegisterMode ? "new-password" : "current-password"}
                  />
                </Form.Item>

                {isRegisterMode ? (
                  <Form.Item
                    label="确认密码"
                    name="confirmPassword"
                    dependencies={["password"]}
                    rules={[
                      { required: true, message: "请再次输入密码" },
                      ({ getFieldValue }) => ({
                        validator(_, value) {
                          if (!value || getFieldValue("password") === value) {
                            return Promise.resolve();
                          }
                          return Promise.reject(new Error("两次输入的密码不一致"));
                        },
                      }),
                    ]}
                  >
                    <Input.Password
                      size="large"
                      prefix={<LockOutlined />}
                      placeholder="请再次输入密码"
                      autoComplete="new-password"
                    />
                  </Form.Item>
                ) : null}

                <Button
                  htmlType="submit"
                  type="primary"
                  size="large"
                  block
                  loading={submitting}
                >
                  {isRegisterMode ? "注册并进入系统" : "登录"}
                </Button>
              </Form>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
