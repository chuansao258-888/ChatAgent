import React, { useEffect, useMemo, useState } from "react";
import {
  EditOutlined,
  LockOutlined,
  LogoutOutlined,
  MessageOutlined,
  SearchOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Button, Tabs, Tooltip, Typography, type TabsProps } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth.ts";
import ChatTabContent from "./tabs/ChatTabContent.tsx";

const CHAT_TAB_KEY = "chat";

function SideMenuHeader() {
  return (
    <div className="flex items-center gap-3 px-1 pb-5 pt-1">
      <div className="flex h-11 w-11 items-center justify-center rounded-2xl border border-white/10 bg-white/[0.04] text-white shadow-[0_10px_30px_rgba(0,0,0,0.18)]">
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
          className="flex w-full items-center gap-3 rounded-2xl border border-white/6 bg-white/[0.03] px-4 py-3 text-left text-white transition hover:border-white/10 hover:bg-white/[0.06]"
        >
          <EditOutlined />
          <span className="font-medium">New chat</span>
        </button>
        <button
          type="button"
          onClick={() => {
            openAuthDialog("login");
          }}
          className="flex w-full items-center gap-3 rounded-2xl px-4 py-3 text-left text-white/72 transition hover:bg-white/[0.05] hover:text-white"
        >
          <SearchOutlined />
          <span className="font-medium">Search chats</span>
        </button>
      </div>

      <div className="mt-auto pb-1">
        <div className="rounded-[28px] border border-white/8 bg-white/[0.035] p-5">
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

  const [activeKey, setActiveKey] = useState<string>(CHAT_TAB_KEY);

  useEffect(() => {
    if (location.pathname.startsWith("/chat")) {
      setActiveKey(CHAT_TAB_KEY);
    }
  }, [location.pathname]);

  const handleTabChange = (key: string) => {
    setActiveKey(key);

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
          activeKey={activeKey}
          onChange={handleTabChange}
          items={items}
        />
      </div>

      <div className="mt-4 rounded-[24px] border border-white/8 bg-white/[0.045] px-4 py-4 shadow-[0_14px_36px_rgba(0,0,0,0.18)]">
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-[#0f172a] text-white shadow-[0_8px_24px_rgba(0,0,0,0.25)]">
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
