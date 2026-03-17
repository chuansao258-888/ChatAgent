import { jsx as _jsx } from "react/jsx-runtime";
import React from "react";
const Sidebar = ({ children }) => {
    return (_jsx("div", { className: "h-full bg-slate-50", style: {
            width: "320px",
        }, children: children }));
};
export default Sidebar;
