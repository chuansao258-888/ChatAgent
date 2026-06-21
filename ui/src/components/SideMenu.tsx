import React, { useMemo } from "react";
import {
  ControlOutlined,
  EditOutlined,
  LockOutlined,
  LogoutOutlined,
  MessageOutlined,
  SearchOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Button, Tabs, Tooltip, Typography, type TabsProps } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import { isAdminRole } from "../auth/roles.ts";
import { useAuth } from "../hooks/useAuth.ts";
import ChatTabContent from "./tabs/ChatTabContent.tsx";

const CHAT_TAB_KEY = "chat";

function SideMenuHeader() {
  return (
    <div className="flex items-center gap-3 px-1 pb-5 pt-1">
      <div className="flex h-11 w-11 items-center justify-center rounded-input border border-white/10 bg-white/[0.04] text-white shadow-chat-panel">
        <MessageOutlined className="text-lg" />
      </div>
      <div>
        <div className="select-none text-[1.15rem] font-semibold tracking-tight text-white">
          ChatAgent
        </div>
        <div className="text-xs text-white/38">Chat-first workspace</div>
      </div>
    </div>
  );
}

function GuestSideMenu() {
  const navigate = useNavigate();
  const { openAuthDialog } = useAuth();

  return (
    <div className="flex h-full flex-col bg-[#171717] px-5 pb-5 pt-5 text-white">
      <SideMenuHeader />

      <div className="space-y-2">
        <button
          type="button"
          onClick={() => {
            navigate("/chat");
          }}
          className="flex w-full items-center gap-3 rounded-input border border-white/12 bg-white/[0.07] px-4 py-3 text-left text-white/90 transition hover:border-white/18 hover:bg-white/[0.10]"
        >
          <EditOutlined />
          <span className="font-medium">New chat</span>
        </button>
        <button
          type="button"
          onClick={() => {
            openAuthDialog("login");
          }}
          className="flex w-full items-center gap-3 rounded-input border border-transparent px-4 py-3 text-left text-white/65 transition hover:border-white/8 hover:bg-white/[0.05] hover:text-white/90"
        >
          <SearchOutlined />
          <span className="font-medium">Search chats</span>
        </button>
      </div>

      <div className="mt-auto pb-1">
        <div className="rounded-panel border border-white/8 bg-white/[0.035] p-5">
          <Typography.Text className="block text-base font-semibold !text-white">
            Save chats and unlock uploads
          </Typography.Text>
          <Typography.Text className="mt-2 block text-sm leading-6 !text-white/60">
            Sign in when you want history and file-aware chats.
          </Typography.Text>
          <Button
            block
            size="large"
            icon={<LockOutlined />}
            className="!mt-5 !h-12 !rounded-full !border-0 !bg-white !text-[#171717] hover:!bg-white/95"
            onClick={() => {
              openAuthDialog("login");
            }}
          >
            Log in
          </Button>
        </div>
      </div>
    </div>
  );
}

function AuthenticatedSideMenu() {
  const navigate = useNavigate();
  const location = useLocation();
  const { currentUser, logout } = useAuth();

  const handleTabChange = (key: string) => {
    if (key === CHAT_TAB_KEY && !location.pathname.startsWith("/chat")) {
      navigate("/chat");
    }
  };

  const items = useMemo<TabsProps["items"]>(() => {
    return [
      {
        key: CHAT_TAB_KEY,
        label: (
          <span className="select-none inline-flex items-center gap-2">
            <MessageOutlined />
            Chats
          </span>
        ),
        children: <ChatTabContent />,
      },
    ];
  }, []);

  return (
    <div className="flex h-full flex-col bg-[#171717] px-5 pb-5 pt-5 text-white">
      <SideMenuHeader />

      <div className="flex-1 min-h-0 flex flex-col">
        <Tabs
          className="side-menu-tabs"
          activeKey={CHAT_TAB_KEY}
          onChange={handleTabChange}
          items={items}
        />
      </div>

      {isAdminRole(currentUser?.role) ? (
        <button
          type="button"
          onClick={() => {
            navigate("/admin");
          }}
          className="mt-3 flex items-center justify-center gap-3 rounded-input border border-white/12 bg-transparent px-4 py-3 text-center text-white/65 transition hover:border-white/18 hover:bg-white/[0.06] hover:text-white/90"
        >
          <ControlOutlined />
          <span className="font-medium">Admin Console</span>
        </button>
      ) : null}

      <div className="mt-4 rounded-section border border-white/8 bg-white/[0.045] px-4 py-4 shadow-chat-panel">
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-input bg-[#0f172a] text-white shadow-chat-bubble">
            <UserOutlined />
          </div>
          <div className="min-w-0 flex-1">
            <Typography.Text className="block truncate text-sm font-medium !text-white">
              {currentUser?.username ?? "Guest"}
            </Typography.Text>
            <Typography.Text className="block truncate text-xs !text-white/45">
              Signed in as {currentUser?.role ?? "guest"}
            </Typography.Text>
          </div>
          <Tooltip title="Logout">
            <Button
              type="text"
              icon={<LogoutOutlined />}
              className="!flex !h-10 !w-10 !items-center !justify-center !rounded-full !text-white/70 hover:!bg-white/[0.06] hover:!text-white"
              onClick={() => {
                void logout();
              }}
            />
          </Tooltip>
        </div>
      </div>
    </div>
  );
}

const SideMenu: React.FC = () => {
  const { isAuthenticated } = useAuth();

  if (!isAuthenticated) {
    return <GuestSideMenu />;
  }

  return <AuthenticatedSideMenu />;
};

export default SideMenu;
