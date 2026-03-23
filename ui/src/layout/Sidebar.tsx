import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div className="h-full w-[304px] shrink-0 overflow-hidden bg-[#171717]">
      <div className="h-full w-[304px]">{children}</div>
    </div>
  );
};

export default Sidebar;
