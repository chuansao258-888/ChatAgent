import { get, post } from "./http.ts";

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
}

export interface AuthResponse {
  accessToken: string;
  userId: string;
  username: string;
  role: string;
  avatar?: string;
}

export interface CurrentUserVO {
  userId: string;
  username: string;
  role: string;
  avatar?: string;
}

export async function login(request: LoginRequest): Promise<AuthResponse> {
  return post<AuthResponse>("/auth/login", request, {
    skipAuthRefresh: true,
    silent: true,
  });
}

export async function register(request: RegisterRequest): Promise<AuthResponse> {
  return post<AuthResponse>("/auth/register", request, {
    skipAuthRefresh: true,
    silent: true,
  });
}

export async function refreshSession(): Promise<AuthResponse> {
  return post<AuthResponse>("/auth/refresh", undefined, {
    skipAuthRefresh: true,
    silent: true,
  });
}

export async function logout(): Promise<void> {
  return post<void>("/auth/logout", undefined, {
    skipAuthRefresh: true,
    silent: true,
  });
}

export async function getCurrentUser(): Promise<CurrentUserVO> {
  return get<CurrentUserVO>("/user/me", undefined, {
    silent: true,
  });
}
