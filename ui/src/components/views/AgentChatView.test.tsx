import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AgentChatView from "./AgentChatView.tsx";
import {
  createChatMessage,
  getChatMessagesBySessionId,
  getChatSession,
  getChatSessionFiles,
} from "../../api/api.ts";

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

class MockEventSource {
  static instances: MockEventSource[] = [];

  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;

  private listeners = new Map<string, Array<(event: MessageEvent<string>) => void>>();

  constructor(public readonly url: string) {
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    const existing = this.listeners.get(type) ?? [];
    existing.push(listener);
    this.listeners.set(type, existing);
  }

  close() {}

  emit(type: string, payload: unknown) {
    const event = { data: JSON.stringify(payload) } as MessageEvent<string>;
    if (type === "message" && this.onmessage) {
      this.onmessage(event);
    }
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event);
    }
  }
}

async function flushMicrotasks() {
  await Promise.resolve();
  await Promise.resolve();
}

vi.mock("antd", () => ({
  message: hoisted.antdMessage,
}));

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return {
    ...actual,
    useParams: () => ({ chatSessionId: "session-1" }),
    useNavigate: () => hoisted.navigateMock,
    useLocation: () => ({ state: null }),
  };
});

vi.mock("../../auth/token.ts", () => ({
  getAccessToken: () => "token-1",
}));

vi.mock("../../api/http.ts", () => ({
  BASE_URL: "http://test.local",
}));

vi.mock("../../hooks/useAuth.ts", () => ({
  useAuth: () => ({
    initializing: false,
    isAuthenticated: true,
    openAuthDialog: hoisted.openAuthDialogMock,
  }),
}));

vi.mock("../../hooks/useChatSessions.ts", () => ({
  useChatSessions: () => ({
    refreshChatSessions: hoisted.refreshChatSessionsMock,
  }),
}));

vi.mock("./agentChatView/AgentChatHistory.tsx", () => ({
  default: ({
    displayAgentStatus,
    agentStatusText,
    messages,
    persistentErrorText,
    onRetryLastMessage,
    onDismissError,
  }: {
    displayAgentStatus?: boolean;
    agentStatusText?: string;
    messages: Array<{ id: string; role: string; content: string }>;
    persistentErrorText?: string;
    onRetryLastMessage?: () => void;
    onDismissError?: () => void;
  }) => (
    <div>
      <div data-testid="history-status">{displayAgentStatus ? agentStatusText : "idle"}</div>
      <div data-testid="history-count">{messages.length}</div>
      <div data-testid="history-messages">
        {messages.map((message) => `${message.id}:${message.content}`).join("|")}
      </div>
      {persistentErrorText ? (
        <div data-testid="persistent-error">
          <span>{persistentErrorText}</span>
          {onRetryLastMessage ? (
            <button type="button" onClick={onRetryLastMessage}>
              Retry
            </button>
          ) : null}
          {onDismissError ? (
            <button type="button" onClick={onDismissError}>
              Dismiss
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  ),
}));

vi.mock("./agentChatView/AgentChatInput.tsx", () => ({
  default: ({ onSend }: { onSend: (message: string) => Promise<void> | void }) => (
    <button
      type="button"
      onClick={() => {
        void onSend("hello");
      }}
    >
      send
    </button>
  ),
}));

vi.mock("./agentChatView/EmptyAgentChatView.tsx", () => ({
  default: () => <div data-testid="empty-chat">empty</div>,
}));

vi.mock("../../api/api.ts", async () => {
  const actual = await vi.importActual<typeof import("../../api/api.ts")>("../../api/api.ts");
  return {
    ...actual,
    createChatMessage: vi.fn(),
    getChatMessagesBySessionId: vi.fn(),
    getChatSession: vi.fn(),
    getChatSessionFiles: vi.fn(),
    createChatSession: vi.fn(),
    uploadChatSessionFile: vi.fn(),
    detachChatSessionFile: vi.fn(),
  };
});

describe("AgentChatView", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    MockEventSource.instances = [];
    Object.defineProperty(globalThis, "EventSource", {
      configurable: true,
      writable: true,
      value: MockEventSource,
    });

    vi.spyOn(globalThis.crypto, "randomUUID").mockReturnValue("turn-client");

    vi.mocked(getChatSession).mockResolvedValue({
      chatSession: {
        id: "session-1",
        agentId: "agent-1",
        title: "Test",
      },
    });
    vi.mocked(getChatSessionFiles).mockResolvedValue({ files: [] });
    vi.mocked(getChatMessagesBySessionId)
      .mockResolvedValueOnce({ chatMessages: [] })
      .mockResolvedValueOnce({ chatMessages: [] })
      .mockResolvedValueOnce({
        chatMessages: [
          {
            id: "tool-1",
            sessionId: "session-1",
            turnId: "turn-server",
            role: "tool",
            content: "Tool output",
          },
        ],
      })
      .mockResolvedValue({
        chatMessages: [
          {
            id: "tool-1",
            sessionId: "session-1",
            turnId: "turn-server",
            role: "tool",
            content: "Tool output",
          },
        ],
      });
    vi.mocked(createChatMessage).mockResolvedValue({
      chatMessageId: "user-1",
      turnId: "turn-server",
    });
  });

  it("keeps pending alive across tool-only compensation progress and clears on AI_DONE", async () => {
    render(<AgentChatView />);

    await act(async () => {
      await flushMicrotasks();
    });
    expect(getChatMessagesBySessionId).toHaveBeenCalledTimes(1);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "send" }));
      await flushMicrotasks();
    });

    expect(createChatMessage).toHaveBeenCalledWith({
        sessionId: "session-1",
        turnId: "turn-client",
        role: "user",
        content: "hello",
    });

    expect(sessionStorage.getItem("chatagent:pending-turn:session-1")).toBe("turn-server");
    expect(screen.getByTestId("history-status").textContent).toBe("Queuing...");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });

    expect(hoisted.antdMessage.error).not.toHaveBeenCalled();
    expect(screen.getByTestId("history-status").textContent).toBe("Queuing...");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_000);
    });

    expect(hoisted.antdMessage.error).not.toHaveBeenCalled();
    expect(screen.getByTestId("history-status").textContent).not.toBe("idle");

    const eventSource = MockEventSource.instances[0];
    expect(eventSource).toBeDefined();

    act(() => {
      eventSource.emit("message", {
        type: "AI_DONE",
        payload: {
          done: true,
        },
        metadata: {
          chatMessageId: "assistant-1",
        },
      });
    });

    expect(screen.getByTestId("history-status").textContent).toBe("idle");
    expect(sessionStorage.getItem("chatagent:pending-turn:session-1")).toBeNull();
  });

  it("updates an existing streamed assistant message by id", async () => {
    render(<AgentChatView />);

    await act(async () => {
      await flushMicrotasks();
    });

    const eventSource = MockEventSource.instances[0];
    expect(eventSource).toBeDefined();

    act(() => {
      eventSource.emit("message", {
        type: "AI_GENERATED_CONTENT",
        payload: {
          message: {
            id: "assistant-stream-1",
            sessionId: "session-1",
            turnId: "turn-server",
            role: "assistant",
            content: "Hel",
          },
        },
        metadata: {
          chatMessageId: "assistant-stream-1",
        },
      });
    });

    expect(screen.getByTestId("history-messages").textContent).toBe(
      "assistant-stream-1:Hel",
    );

    act(() => {
      eventSource.emit("message", {
        type: "AI_GENERATED_CONTENT",
        payload: {
          message: {
            id: "assistant-stream-1",
            sessionId: "session-1",
            turnId: "turn-server",
            role: "assistant",
            content: "Hello from stream",
          },
        },
        metadata: {
          chatMessageId: "assistant-stream-1",
        },
      });
    });

    expect(screen.getByTestId("history-count").textContent).toBe("1");
    expect(screen.getByTestId("history-messages").textContent).toBe(
      "assistant-stream-1:Hello from stream",
    );

    act(() => {
      eventSource.emit("message", {
        type: "AI_GENERATED_CONTENT",
        payload: {
          message: {
            id: "assistant-stream-1",
            sessionId: "session-1",
            turnId: "turn-server",
            role: "assistant",
            content: "Hello",
          },
        },
        metadata: {
          chatMessageId: "assistant-stream-1",
        },
      });
    });

    expect(screen.getByTestId("history-count").textContent).toBe("1");
    expect(screen.getByTestId("history-messages").textContent).toBe(
      "assistant-stream-1:Hello from stream",
    );
  });

  it("shows AI_ERROR as a persistent retryable error and can dismiss it", async () => {
    render(<AgentChatView />);

    await act(async () => {
      await flushMicrotasks();
    });

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "send" }));
      await flushMicrotasks();
    });

    const eventSource = MockEventSource.instances[0];
    expect(eventSource).toBeDefined();

    act(() => {
      eventSource.emit("message", {
        type: "AI_ERROR",
        payload: {
          statusText: "Model stream interrupted",
        },
        metadata: {
          chatMessageId: "assistant-error-1",
        },
      });
    });

    expect(screen.getByTestId("history-status").textContent).toBe(
      "Model stream interrupted",
    );
    expect(screen.getByTestId("persistent-error").textContent).toContain(
      "Model stream interrupted",
    );

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Retry" }));
      await flushMicrotasks();
    });

    expect(createChatMessage).toHaveBeenLastCalledWith({
      sessionId: "session-1",
      turnId: "turn-client",
      role: "user",
      content: "hello",
    });

    act(() => {
      eventSource.emit("message", {
        type: "AI_ERROR",
        payload: {
          statusText: "Model stream interrupted again",
        },
        metadata: {
          chatMessageId: "assistant-error-2",
        },
      });
    });

    expect(screen.getByTestId("persistent-error").textContent).toContain(
      "Model stream interrupted again",
    );

    act(() => {
      fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    });

    expect(screen.queryByTestId("persistent-error")).toBeNull();
  });

  it("rolls back provisional assistant messages for a tool-call turn", async () => {
    render(<AgentChatView />);

    await act(async () => {
      await flushMicrotasks();
    });

    const eventSource = MockEventSource.instances[0];
    expect(eventSource).toBeDefined();

    act(() => {
      eventSource.emit("message", {
        type: "AI_GENERATED_CONTENT",
        payload: {
          message: {
            id: "assistant-provisional-1",
            sessionId: "session-1",
            turnId: "turn-with-tool",
            role: "assistant",
            content: "I will call a tool",
          },
        },
        metadata: {
          chatMessageId: "assistant-provisional-1",
        },
      });
    });

    expect(screen.getByTestId("history-messages").textContent).toContain(
      "assistant-provisional-1:I will call a tool",
    );

    act(() => {
      eventSource.emit("message", {
        type: "TURN_ROLLBACK",
        payload: {
          turnId: "turn-with-tool",
        },
        metadata: {
          chatMessageId: "assistant-provisional-1",
        },
      });
    });

    expect(screen.getByTestId("history-messages").textContent).not.toContain(
      "assistant-provisional-1",
    );
  });
});
