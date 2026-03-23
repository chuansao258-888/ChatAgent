import { createContext } from "react";
import type {
  CurrentUserVO,
  LoginRequest,
  RegisterRequest,
} from "../api/auth.ts";

export type AuthMode = "login" | "register";

export interface AuthContextType {
  currentUser: CurrentUserVO | null;
  initializing: boolean;
  isAuthenticated: boolean;
  authDialogOpen: boolean;
  authDialogMode: AuthMode;
  login: (request: LoginRequest) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  openAuthDialog: (mode?: AuthMode) => void;
  closeAuthDialog: () => void;
}

export const AuthContext = createContext<AuthContextType | undefined>(
  undefined,
);
