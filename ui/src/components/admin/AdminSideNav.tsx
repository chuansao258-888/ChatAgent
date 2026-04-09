import {
  ApartmentOutlined,
  AppstoreOutlined,
  ArrowLeftOutlined,
  DatabaseOutlined,
  PartitionOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { Button, Typography } from "antd";
import { NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../../hooks/useAuth.ts";

const navItems = [
  { to: "/admin", label: "Overview", icon: <ApartmentOutlined /> },
  {
    to: "/admin/knowledge-bases",
    label: "Knowledge Bases",
    icon: <DatabaseOutlined />,
  },
  {
    to: "/admin/intent-tree",
    label: "Intent Tree",
    icon: <PartitionOutlined />,
  },
  {
    to: "/admin/templates",
    label: "Templates",
    icon: <AppstoreOutlined />,
  },
  { to: "/admin/assistant", label: "Assistant", icon: <SettingOutlined /> },
];

function navClassName(isActive: boolean): string {
  return [
    "flex items-center gap-3 rounded-input px-4 py-3 text-sm font-medium transition",
    isActive
      ? "bg-white/[0.08] text-white/95 shadow-admin-nav"
      : "text-white/60 hover:bg-white/[0.06] hover:text-white/90",
  ].join(" ");
}

export default function AdminSideNav() {
  const navigate = useNavigate();
  const { currentUser } = useAuth();

  return (
    <div className="flex h-full flex-col bg-[#171717] p-6 text-white">
      <div className="rounded-panel border border-white/[0.08] bg-white/[0.06] px-5 py-5 text-white shadow-admin-hero">
        <div className="text-xs uppercase tracking-[0.28em] text-white/40">
          Admin
        </div>
        <div className="mt-3 text-2xl font-semibold tracking-tight">
          Knowledge console
        </div>
        <Typography.Text className="mt-3 block text-sm leading-6 !text-white/60">
          Curate enterprise knowledge, document ingestion, and the internal
          assistant binding from one place.
        </Typography.Text>
      </div>

      <div className="mt-8 space-y-2">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === "/admin"}
            className={({ isActive }) => navClassName(isActive)}
          >
            <span className="text-base">{item.icon}</span>
            <span>{item.label}</span>
          </NavLink>
        ))}
      </div>

      <div className="mt-auto rounded-section border border-white/[0.08] bg-white/[0.04] p-4 shadow-admin-card">
        <div className="text-xs uppercase tracking-[0.22em] text-white/40">
          Signed in
        </div>
        <div className="mt-2 text-base font-semibold text-white">
          {currentUser?.username}
        </div>
        <div className="text-sm text-white/40">
          {currentUser?.role ?? "admin"}
        </div>
        <Button
          type="default"
          icon={<ArrowLeftOutlined />}
          className="!mt-4 !h-11 !w-full !rounded-input !border-white/14 !bg-transparent !text-white/80 hover:!border-white/22 hover:!bg-white/[0.06]"
          onClick={() => {
            navigate("/chat");
          }}
        >
          Back to chat
        </Button>
      </div>
    </div>
  );
}
