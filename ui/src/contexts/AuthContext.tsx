import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  type AuthResponse,
  type CurrentUserVO,
  type LoginRequest,
  type RegisterRequest,
  login as loginApi,
  register as registerApi,
  logout as logoutApi,
  refreshSession,
} from "../api/auth.ts";
import { AUTH_EXPIRED_EVENT } from "../auth/events.ts";
import { clearAccessToken, setAccessToken } from "../auth/token.ts";
import { AuthContext, type AuthMode } from "./authContext.ts";

function toCurrentUser(authResponse: AuthResponse): CurrentUserVO {
  return {
    userId: authResponse.userId,
    username: authResponse.username,
    role: authResponse.role,
    avatar: authResponse.avatar,
  };
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [currentUser, setCurrentUser] = useState<CurrentUserVO | null>(null);
  const [initializing, setInitializing] = useState(true);
  const [authDialogOpen, setAuthDialogOpen] = useState(false);
  const [authDialogMode, setAuthDialogMode] = useState<AuthMode>("login");

  const applyAuthResponse = useCallback((authResponse: AuthResponse) => {
    setAccessToken(authResponse.accessToken);
    setCurrentUser(toCurrentUser(authResponse));
  }, []);

  const clearAuthState = useCallback(() => {
    clearAccessToken();
    setCurrentUser(null);
  }, []);

  const login = useCallback(
    async (request: LoginRequest) => {
      const authResponse = await loginApi(request);
      applyAuthResponse(authResponse);
      setAuthDialogOpen(false);
    },
    [applyAuthResponse],
  );

  const register = useCallback(
    async (request: RegisterRequest) => {
      const authResponse = await registerApi(request);
      applyAuthResponse(authResponse);
      setAuthDialogOpen(false);
    },
    [applyAuthResponse],
  );

  const logout = useCallback(async () => {
    try {
      await logoutApi();
    } finally {
      clearAuthState();
    }
  }, [clearAuthState]);

  const openAuthDialog = useCallback((mode: AuthMode = "login") => {
    setAuthDialogMode(mode);
    setAuthDialogOpen(true);
  }, []);

  const closeAuthDialog = useCallback(() => {
    setAuthDialogOpen(false);
  }, []);

  useEffect(() => {
    let mounted = true;

    async function initializeAuth() {
      try {
        const authResponse = await refreshSession();
        if (!mounted) {
          return;
        }
        applyAuthResponse(authResponse);
      } catch {
        if (!mounted) {
          return;
        }
        clearAuthState();
      } finally {
        if (mounted) {
          setInitializing(false);
        }
      }
    }

    void initializeAuth();

    const handleAuthExpired = () => {
      clearAuthState();
      setInitializing(false);
    };

    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);

    return () => {
      mounted = false;
      window.removeEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    };
  }, [applyAuthResponse, clearAuthState]);

  const value = useMemo(
    () => ({
      currentUser,
      initializing,
      isAuthenticated: currentUser !== null,
      authDialogOpen,
      authDialogMode,
      login,
      register,
      logout,
      openAuthDialog,
      closeAuthDialog,
    }),
    [
      authDialogMode,
      authDialogOpen,
      closeAuthDialog,
      currentUser,
      initializing,
      login,
      logout,
      openAuthDialog,
      register,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
