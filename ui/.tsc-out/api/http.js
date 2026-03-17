import { message } from "antd";
// API 基础路径（可以根据环境变量配置）
export const BASE_URL = "http://localhost:8080/api";
/**
 * 构建完整的 URL（包含查询参数）
 */
function buildUrl(url, params) {
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
/**
 * 处理响应
 */
async function handleResponse(response) {
    if (!response.ok) {
        // HTTP 状态码错误
        throw new Error(`HTTP error! status: ${response.status}`);
    }
    const data = await response.json();
    // 检查业务状态码
    if (data.code !== 200) {
        message.error(data.message || "请求失败");
        throw new Error(data.message || "请求失败");
    }
    return data;
}
/**
 * 封装的 fetch 请求函数
 */
async function request(url, options = {}) {
    const { params, headers, ...restOptions } = options;
    // 构建完整 URL
    const fullUrl = buildUrl(url, params);
    // 设置默认请求头
    const defaultHeaders = {
        "Content-Type": "application/json",
        ...headers,
    };
    try {
        const response = await fetch(fullUrl, {
            ...restOptions,
            headers: defaultHeaders,
        });
        const apiResponse = await handleResponse(response);
        return apiResponse.data;
    }
    catch (error) {
        // 统一错误处理
        if (error instanceof Error) {
            throw error;
        }
        throw new Error("网络请求失败");
    }
}
/**
 * GET 请求
 */
export function get(url, params, options) {
    return request(url, {
        ...options,
        method: "GET",
        params,
    });
}
/**
 * POST 请求
 */
export function post(url, data, options) {
    return request(url, {
        ...options,
        method: "POST",
        body: data ? JSON.stringify(data) : undefined,
    });
}
/**
 * PUT 请求
 */
export function put(url, data, options) {
    return request(url, {
        ...options,
        method: "PUT",
        body: data ? JSON.stringify(data) : undefined,
    });
}
/**
 * PATCH 请求
 */
export function patch(url, data, options) {
    return request(url, {
        ...options,
        method: "PATCH",
        body: data ? JSON.stringify(data) : undefined,
    });
}
/**
 * DELETE 请求
 */
export function del(url, params, options) {
    return request(url, {
        ...options,
        method: "DELETE",
        params,
    });
}
// 导出默认对象，方便使用
export default {
    get,
    post,
    put,
    patch,
    delete: del,
};
