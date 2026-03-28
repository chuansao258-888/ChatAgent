import { createRoot } from "react-dom/client";
import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import App from "./App.tsx";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: {
        borderRadius: 16,
        borderRadiusLG: 24,
        borderRadiusSM: 8,
      },
      components: {
        Card: { borderRadiusLG: 30 },
        Button: { borderRadius: 9999 },
        Input: { borderRadius: 16, borderRadiusLG: 16 },
        InputNumber: { borderRadius: 16 },
        Select: { borderRadius: 16 },
      },
    }}
  >
    <App />
  </ConfigProvider>
);