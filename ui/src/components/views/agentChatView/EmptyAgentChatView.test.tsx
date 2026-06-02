import type { ReactNode } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EmptyAgentChatView from "./EmptyAgentChatView.tsx";
import { createChatMessage, createChatSession } from "../../../api/api.ts";

const hoisted = vi.hoisted(() => ({
  navigateMock: vi.fn(),
  refreshChatSessionsMock: vi.fn(),
  openAuthDialogMock: vi.fn(),
  antdMessage: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
  },
}));

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return {
    ...actual,
    useNavigate: () => hoisted.navigateMock,
  };
});

vi.mock("antd", () => ({
  Button: ({
    children,
    onClick,
    disabled,
  }: {
    children?: ReactNode;
    onClick?: () => void;
    disabled?: boolean;
  }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
  Typography: {
    Title: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
    Text: ({ children }: { children?: ReactNode }) => <span>{children}</span>,
  },
  message: hoisted.antdMessage,
}));

vi.mock("@ant-design/x", () => ({
  Sender: ({
    value,
    onChange,
    onSubmit,
    disabled,
    loading,
    placeholder,
  }: {
    value: string;
    onChange: (value: string) => void;
    onSubmit: () => void;
    disabled?: boolean;
    loading?: boolean;
    placeholder?: string;
  }) => (
    <div>
      <input
        aria-label="empty-chat-input"
        value={value}
        placeholder={placeholder}
        onChange={(event) => {
          onChange(event.target.value);
        }}
      />
      <button
        type="button"
        onClick={onSubmit}
        disabled={disabled || loading}
      >
        submit
      </button>
    </div>
  ),
}));

vi.mock("../../../hooks/useAuth.ts", () => ({
  useAuth: () => ({
    isAuthenticated: true,
    openAuthDialog: hoisted.openAuthDialogMock,
  }),
}));

vi.mock("../../../hooks/useChatSessions.ts", () => ({
  useChatSessions: () => ({
    chatSessions: [{ id: "session-1", agentId: "agent-1", title: "Test" }],
    refreshChatSessions: hoisted.refreshChatSessionsMock,
  }),
}));

vi.mock("../../../api/api.ts", async () => {
  const actual = await vi.importActual<typeof import("../../../api/api.ts")>("../../../api/api.ts");
  return {
    ...actual,
    createChatSession: vi.fn(),
    createChatMessage: vi.fn(),
    uploadChatSessionFile: vi.fn(),
  };
});

describe("EmptyAgentChatView", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.spyOn(globalThis.crypto, "randomUUID").mockReturnValue("turn-client");
    vi.mocked(createChatSession).mockResolvedValue("session-1");
    vi.mocked(createChatMessage)
      .mockRejectedValueOnce(new Error("send failed"))
      .mockResolvedValueOnce({
        chatMessageId: "user-1",
        turnId: "turn-server",
      });
  });

  it("reuses the created session when the first message fails and the user retries", async () => {
    render(
      <EmptyAgentChatView
        loading={false}
        executionMode="REACT"
        onExecutionModeChange={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("empty-chat-input"), {
      target: { value: "Hello from first turn" },
    });

    fireEvent.click(screen.getByRole("button", { name: "submit" }));

    await waitFor(() => {
      expect(createChatSession).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(createChatMessage).toHaveBeenCalledTimes(1);
    });

    expect((screen.getByLabelText("empty-chat-input") as HTMLInputElement).value).toBe(
      "Hello from first turn",
    );
    expect(sessionStorage.getItem("chatagent:pending-turn:session-1")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "submit" }));

    await waitFor(() => {
      expect(createChatMessage).toHaveBeenCalledTimes(2);
    });

    expect(createChatSession).toHaveBeenCalledTimes(1);
    expect(createChatMessage).toHaveBeenLastCalledWith({
      sessionId: "session-1",
      turnId: "turn-client",
      content: "Hello from first turn",
      role: "user",
      executionMode: "REACT",
    });
    expect(hoisted.refreshChatSessionsMock).toHaveBeenCalledTimes(1);
    expect(hoisted.navigateMock).toHaveBeenCalledWith("/chat/session-1");
    expect(sessionStorage.getItem("chatagent:pending-turn:session-1")).toBe("turn-server");
    expect(hoisted.antdMessage.error).toHaveBeenCalledTimes(1);
  });

  it("stores and sends DEEPTHINK mode for the first message in a new chat", async () => {
    vi.mocked(createChatMessage).mockReset();
    vi.mocked(createChatMessage).mockResolvedValue({
      chatMessageId: "user-1",
      turnId: "turn-server",
    });

    render(
      <EmptyAgentChatView
        loading={false}
        executionMode="DEEPTHINK"
        onExecutionModeChange={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("empty-chat-input"), {
      target: { value: "Plan this carefully" },
    });
    fireEvent.click(screen.getByRole("button", { name: "submit" }));

    await waitFor(() => {
      expect(createChatMessage).toHaveBeenCalledWith({
        sessionId: "session-1",
        turnId: "turn-client",
        content: "Plan this carefully",
        role: "user",
        executionMode: "DEEPTHINK",
      });
    });
    expect(localStorage.getItem("chatagent:execution-mode:session-1")).toBe(
      "DEEPTHINK",
    );
  });
});
