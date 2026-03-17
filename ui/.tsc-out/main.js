import { jsx as _jsx } from "react/jsx-runtime";
import { createRoot } from "react-dom/client";
import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import App from "./App.tsx";
import "./index.css";
createRoot(document.getElementById("root")).render(_jsx(ConfigProvider, { locale: zhCN, children: _jsx(App, {}) }));
