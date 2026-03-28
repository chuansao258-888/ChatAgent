import { Spin } from "antd";
import { useEffect } from "react";
import { Navigate } from "react-router-dom";
import { isAdminRole } from "../../auth/roles.ts";
import { useAuth } from "../../hooks/useAuth.ts";

export default function AdminRouteGuard({
  children,
}: {
  children: React.ReactNode;
}) {
  const { initializing, isAuthenticated, currentUser, openAuthDialog } =
    useAuth();

  useEffect(() => {
    if (!initializing && !isAuthenticated) {
      openAuthDialog("login");
    }
  }, [initializing, isAuthenticated, openAuthDialog]);

  if (initializing) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#212121]">
        <Spin size="large" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/chat" replace />;
  }

  if (!isAdminRole(currentUser?.role)) {
    return <Navigate to="/chat" replace />;
  }

  return <>{children}</>;
}
