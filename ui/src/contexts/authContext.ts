import { createContext } from "react";
import type {
  CurrentUserVO,
  LoginRequest,
  RegisterRequest,
} from "../api/auth.ts";

export interface AuthContextType {
  currentUser: CurrentUserVO | null;
  initializing: boolean;
  isAuthenticated: boolean;
  login: (request: LoginRequest) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextType | undefined>(
  undefined,
);
