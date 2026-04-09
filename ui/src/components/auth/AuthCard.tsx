import { CloseOutlined } from "@ant-design/icons";
import { Button, Form, Input, Typography } from "antd";
import { useEffect, useMemo, useState } from "react";
import type { LoginRequest, RegisterRequest } from "../../api/auth.ts";
import type { AuthMode } from "../../contexts/authContext.ts";
import { useAuth } from "../../hooks/useAuth.ts";

const { Text, Title } = Typography;

interface AuthFormValues {
  username: string;
  password: string;
  confirmPassword?: string;
}

interface AuthCardProps {
  initialMode?: AuthMode;
  onSuccess?: () => void;
  onClose?: () => void;
  compact?: boolean;
}

interface FeedbackState {
  type: "error" | "info";
  message: string;
}

function isExistingEmailError(message: string): boolean {
  return /username already exists|email already exists|already exists/i.test(
    message,
  );
}

export default function AuthCard({
  initialMode = "login",
  onSuccess,
  onClose,
  compact = false,
}: AuthCardProps) {
  const { login, register } = useAuth();
  const [mode, setMode] = useState<AuthMode>(initialMode);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<FeedbackState | null>(null);
  const [form] = Form.useForm<AuthFormValues>();

  useEffect(() => {
    setMode(initialMode);
    setFeedback(null);
    form.resetFields();
  }, [form, initialMode]);

  const isRegisterMode = mode === "register";

  const subtitle = useMemo(() => {
    if (isRegisterMode) {
      return "Create an account to save chats, assistants, and files.";
    }
    return "Continue when you're ready to save chats and unlock uploads.";
  }, [isRegisterMode]);

  const switchMode = (nextMode: AuthMode, username?: string) => {
    setMode(nextMode);
    setFeedback(null);

    if (username) {
      form.setFieldsValue({
        username,
        password: "",
        confirmPassword: "",
      });
      return;
    }

    form.resetFields();
  };

  const handleFinish = async (values: AuthFormValues) => {
    setSubmitting(true);
    setFeedback(null);

    try {
      if (isRegisterMode) {
        const registerRequest: RegisterRequest = {
          username: values.username.trim(),
          password: values.password,
        };
        await register(registerRequest);
      } else {
        const loginRequest: LoginRequest = {
          username: values.username.trim(),
          password: values.password,
        };
        await login(loginRequest);
      }

      onSuccess?.();
    } catch (error) {
      const errorMessage =
        error instanceof Error
          ? error.message
          : "Authentication failed. Please try again.";

      if (isRegisterMode && isExistingEmailError(errorMessage)) {
        switchMode("login", values.username.trim());
        setFeedback({
          type: "info",
          message:
            "This email is already registered. Enter your password to log in.",
        });
      } else {
        setFeedback({
          type: "error",
          message: errorMessage,
        });
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className={`relative w-full overflow-hidden rounded-card border border-white/8 bg-[#202020] text-white shadow-chat-overlay ${
        compact ? "p-8" : "p-10"
      }`}
    >
      <div className="pointer-events-none absolute inset-x-0 top-0 h-28 bg-[radial-gradient(circle_at_top,rgba(255,255,255,0.06),transparent_65%)]" />

      {onClose ? (
        <button
          type="button"
          onClick={onClose}
          className="absolute right-5 top-5 z-10 flex h-10 w-10 items-center justify-center rounded-full text-white/58 transition hover:bg-white/8 hover:text-white"
          aria-label="Close authentication dialog"
        >
          <CloseOutlined className="text-lg" />
        </button>
      ) : null}

      <div className="relative mb-8 flex flex-col items-center text-center">
        <Title
          level={compact ? 2 : 1}
          className="!mb-3 !font-medium !tracking-tight !text-white"
        >
          Log in or sign up
        </Title>
        <Text className="!mx-auto !block !max-w-[420px] !text-center !text-base !leading-7 !text-white/60">
          {subtitle}
        </Text>
      </div>

      <div className="mb-6 rounded-full border border-white/6 bg-[#2b2b2b] p-1">
        <div className="grid grid-cols-2 gap-1">
          <button
            type="button"
            onClick={() => {
              switchMode("login");
            }}
            className={`h-11 rounded-full text-sm font-medium transition ${
              !isRegisterMode
                ? "bg-[#f5f5f5] text-[#171717] shadow-[0_4px_18px_rgba(255,255,255,0.12)]"
                : "text-white/66 hover:bg-white/5"
            }`}
          >
            Log in
          </button>
          <button
            type="button"
            onClick={() => {
              switchMode("register");
            }}
            className={`h-11 rounded-full text-sm font-medium transition ${
              isRegisterMode
                ? "bg-[#f5f5f5] text-[#171717] shadow-[0_4px_18px_rgba(255,255,255,0.12)]"
                : "text-white/66 hover:bg-white/5"
            }`}
          >
            Sign up
          </button>
        </div>
      </div>

      <Form<AuthFormValues>
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        requiredMark={false}
      >
        <Form.Item
          name="username"
          rules={[
            { required: true, message: "Enter your email address." },
            { type: "email", message: "Enter a valid email address." },
          ]}
        >
          <Input
            size="large"
            placeholder="Email address"
            autoComplete="email"
            className="auth-input !h-14 !rounded-full !px-5 !text-base"
          />
        </Form.Item>

        <Form.Item
          name="password"
          rules={[
            { required: true, message: "Enter your password." },
            { min: 6, message: "Use at least 6 characters." },
          ]}
        >
          <Input.Password
            size="large"
            placeholder={isRegisterMode ? "Create a password" : "Password"}
            autoComplete={isRegisterMode ? "new-password" : "current-password"}
            className="auth-input !h-14 !rounded-full !px-5 !text-base"
          />
        </Form.Item>

        {isRegisterMode ? (
          <Form.Item
            name="confirmPassword"
            dependencies={["password"]}
            rules={[
              { required: true, message: "Confirm your password." },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue("password") === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error("Passwords do not match."));
                },
              }),
            ]}
          >
            <Input.Password
              size="large"
              placeholder="Confirm password"
              autoComplete="new-password"
              className="auth-input !h-14 !rounded-full !px-5 !text-base"
            />
          </Form.Item>
        ) : null}

        {feedback ? (
          <div
            className={`mb-4 rounded-inset px-4 py-3 text-sm ${
              feedback.type === "info"
                ? "border border-sky-400/18 bg-sky-400/10 text-sky-100"
                : "border border-red-500/20 bg-red-500/9 text-red-200"
            }`}
          >
            {feedback.message}
          </div>
        ) : null}

        <Button
          htmlType="submit"
          size="large"
          loading={submitting}
          className="!mt-1 !h-14 !w-full !rounded-full !border-0 !bg-[#f5f5f5] !text-lg !font-medium !text-[#171717] shadow-[0_8px_30px_rgba(255,255,255,0.08)] hover:!bg-white"
        >
          Continue
        </Button>
      </Form>
    </div>
  );
}
