import type { APIRequestContext, APIResponse } from "@playwright/test";

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

async function parseEnvelope<T>(response: APIResponse): Promise<ApiEnvelope<T>> {
  let envelope: ApiEnvelope<T>;
  try {
    envelope = (await response.json()) as ApiEnvelope<T>;
  } catch {
    throw new Error(`Expected JSON response from ${response.url()}.`);
  }

  if (!response.ok()) {
    throw new Error(
      `API request failed with HTTP ${response.status()} at ${response.url()}: ${envelope.message}`,
    );
  }
  if (envelope.code !== 200) {
    throw new Error(`API request failed at ${response.url()}: ${envelope.message}`);
  }
  return envelope;
}

export async function postApi<T>(
  request: APIRequestContext,
  path: string,
  data?: unknown,
  accessToken?: string,
): Promise<T> {
  const response = await request.post(path, {
    data,
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  });
  return (await parseEnvelope<T>(response)).data;
}

export async function putApi<T>(
  request: APIRequestContext,
  path: string,
  data?: unknown,
  accessToken?: string,
): Promise<T> {
  const response = await request.put(path, {
    data,
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  });
  return (await parseEnvelope<T>(response)).data;
}

export async function patchApi<T>(
  request: APIRequestContext,
  path: string,
  data?: unknown,
  accessToken?: string,
): Promise<T> {
  const response = await request.patch(path, {
    data,
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  });
  return (await parseEnvelope<T>(response)).data;
}

export async function postMultipartApi<T>(
  request: APIRequestContext,
  path: string,
  multipart: NonNullable<Parameters<APIRequestContext["post"]>[1]>["multipart"],
  accessToken?: string,
): Promise<T> {
  const response = await request.post(path, {
    multipart,
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  });
  return (await parseEnvelope<T>(response)).data;
}

export async function getApi<T>(
  request: APIRequestContext,
  path: string,
  accessToken?: string,
): Promise<T> {
  const response = await request.get(path, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  });
  return (await parseEnvelope<T>(response)).data;
}

export async function deleteApi<T>(
  request: APIRequestContext,
  path: string,
  accessToken?: string,
): Promise<T> {
  const response = await request.delete(path, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  });
  return (await parseEnvelope<T>(response)).data;
}
