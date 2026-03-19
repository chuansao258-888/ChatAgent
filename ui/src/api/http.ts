import { message } from "antd";
import { AUTH_EXPIRED_EVENT } from "../auth/events.ts";
import {
  clearAccessToken,
  getAccessToken,
  setAccessToken,
} from "../auth/token.ts";

export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
}

export interface RequestOptions extends RequestInit {
  params?: Record<string, string | number | boolean | null | undefined>;
  skipAuthRefresh?: boolean;
  retry?: boolean;
  silent?: boolean;
}

export const BASE_URL = "http://localhost:8080/api";

function buildUrl(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
): string {
  const fullUrl = `${BASE_URL}${url}`;

  if (!params || Object.keys(params).length === 0) {
    return fullUrl;
  }

  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined) {
      searchParams.append(key, String(value));
    }
  });

  const queryString = searchParams.toString();
  return queryString ? `${fullUrl}?${queryString}` : fullUrl;
}

async function handleResponse<T>(
  response: Response,
  silent = false,
): Promise<ApiResponse<T>> {
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`);
  }

  const data: ApiResponse<T> = await response.json();
  if (data.code !== 200) {
    if (!silent) {
      message.error(data.message || "请求失败");
    }
    throw new Error(data.message || "请求失败");
  }

  return data;
}

let refreshPromise: Promise<string | null> | null = null;

function notifyAuthExpired() {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
  }
}

async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) {
    return refreshPromise;
  }

  refreshPromise = (async () => {
    try {
      const response = await fetch(`${BASE_URL}/auth/refresh`, {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
        },
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const apiResponse = (await response.json()) as ApiResponse<{
        accessToken: string;
      }>;

      if (apiResponse.code !== 200 || !apiResponse.data?.accessToken) {
        throw new Error(apiResponse.message || "刷新登录态失败");
      }

      setAccessToken(apiResponse.data.accessToken);
      return apiResponse.data.accessToken;
    } catch {
      clearAccessToken();
      notifyAuthExpired();
      return null;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

async function request<T = unknown>(
  url: string,
  options: RequestOptions = {},
): Promise<T> {
  const { params, headers, skipAuthRefresh, retry, silent, ...restOptions } =
    options;
  const fullUrl = buildUrl(url, params);

  const defaultHeaders = new Headers(headers);
  if (
    !(restOptions.body instanceof FormData) &&
    !defaultHeaders.has("Content-Type")
  ) {
    defaultHeaders.set("Content-Type", "application/json");
  }

  const accessToken = getAccessToken();
  if (accessToken && !defaultHeaders.has("Authorization")) {
    defaultHeaders.set("Authorization", `Bearer ${accessToken}`);
  }

  try {
    const response = await fetch(fullUrl, {
      ...restOptions,
      credentials: "include",
      headers: defaultHeaders,
    });

    if (response.status === 401 && !skipAuthRefresh && !retry) {
      const refreshedAccessToken = await refreshAccessToken();
      if (refreshedAccessToken) {
        return request<T>(url, {
          ...options,
          retry: true,
        });
      }
    }

    const apiResponse = await handleResponse<T>(response, silent);
    return apiResponse.data;
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error("网络请求失败");
  }
}

function toRequestBody(data: unknown): BodyInit | undefined {
  if (data === undefined) {
    return undefined;
  }

  if (
    data instanceof FormData ||
    data instanceof Blob ||
    data instanceof URLSearchParams ||
    typeof data === "string"
  ) {
    return data;
  }

  return JSON.stringify(data);
}

export function get<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "GET",
    params,
  });
}

export function post<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "POST",
    body: toRequestBody(data),
  });
}

export function put<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PUT",
    body: toRequestBody(data),
  });
}

export function patch<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PATCH",
    body: toRequestBody(data),
  });
}

export function del<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "DELETE",
    params,
  });
}

export default {
  get,
  post,
  put,
  patch,
  delete: del,
};
