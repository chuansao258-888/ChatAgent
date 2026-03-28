export function isAdminRole(role?: string | null): boolean {
  if (!role) {
    return false;
  }
  return role.trim().toLowerCase() === "admin";
}
