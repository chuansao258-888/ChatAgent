import { ConfigProvider, theme } from "antd";
import enUS from "antd/locale/en_US";
import { Outlet } from "react-router-dom";
import AdminSideNav from "./AdminSideNav.tsx";

export default function AdminLayout() {
  return (
    <ConfigProvider
      locale={enUS}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          borderRadius: 16,
          borderRadiusLG: 24,
          borderRadiusSM: 8,
          colorPrimary: "#2f8f63",
          colorBgContainer: "rgba(255,255,255,0.04)",
          colorBgElevated: "#2a2a2a",
          colorBgLayout: "#212121",
          colorBorder: "rgba(255,255,255,0.08)",
          colorBorderSecondary: "rgba(255,255,255,0.06)",
          colorSplit: "rgba(255,255,255,0.06)",
        },
        components: {
          Card: { borderRadiusLG: 30, colorBgContainer: "rgba(255,255,255,0.04)" },
          Button: {
            borderRadius: 9999,
            defaultBorderColor: "rgba(47, 143, 99, 0.40)",
            defaultColor: "#3da876",
            defaultBg: "transparent",
            defaultHoverBorderColor: "#45b883",
            defaultHoverColor: "#45b883",
            defaultHoverBg: "rgba(47, 143, 99, 0.08)",
            defaultActiveBorderColor: "#2f8f63",
            defaultActiveColor: "#2f8f63",
            defaultActiveBg: "rgba(47, 143, 99, 0.12)",
          },
          Input: { borderRadius: 16, borderRadiusLG: 16 },
          InputNumber: { borderRadius: 16 },
          Select: { borderRadius: 16 },
          Drawer: { colorBgElevated: "#1e1e1e" },
          Modal: { colorBgElevated: "#1e1e1e" },
        },
      }}
    >
      <div className="admin-dark flex h-screen overflow-hidden bg-[#212121] text-white">
        <aside className="h-full w-[312px] shrink-0 border-r border-white/[0.06] bg-[#171717]">
          <AdminSideNav />
        </aside>
        <main className="min-w-0 flex-1 overflow-y-auto bg-[#212121]">
          <div className="mx-auto flex min-h-full w-full max-w-6xl flex-col px-6 py-6 md:px-10 md:py-8">
            <Outlet />
          </div>
        </main>
      </div>
    </ConfigProvider>
  );
}
