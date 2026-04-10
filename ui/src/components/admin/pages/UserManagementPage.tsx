import {
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  UserAddOutlined,
} from "@ant-design/icons";
import {
  Avatar,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { ColumnsType, TablePaginationConfig } from "antd/es/table";
import { useEffect, useMemo, useState } from "react";
import {
  createAdminUser,
  deleteAdminUser,
  getAdminUsers,
  resetAdminUserPassword,
  updateAdminUser,
  updateAdminUserStatus,
} from "../../../api/admin.ts";
import type {
  AdminUserRole,
  AdminUserStatus,
  AdminUserVO,
} from "../../../types/admin.ts";
import { formatTimestamp } from "../adminUtils.ts";

interface UserFormValues {
  username?: string;
  role: AdminUserRole;
  avatar?: string;
}

function roleLabel(role?: string): string {
  return role?.toLowerCase() === "admin" ? "Admin" : "Member";
}

function roleColor(role?: string): string {
  return role?.toLowerCase() === "admin" ? "geekblue" : "default";
}

function statusLabel(status?: string): string {
  return status?.toUpperCase() === "DISABLED" ? "Disabled" : "Active";
}

function statusColor(status?: string): string {
  return status?.toUpperCase() === "DISABLED" ? "orange" : "green";
}

function normalizeOptionalInput(value?: string): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function isDuplicateUsernameError(message: string): boolean {
  return /username already exists|already exists/i.test(message);
}

export default function UserManagementPage() {
  const [form] = Form.useForm<UserFormValues>();
  const [messageApi, messageContextHolder] = message.useMessage();
  const [modalApi, modalContextHolder] = Modal.useModal();
  const [users, setUsers] = useState<AdminUserVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [actionUserId, setActionUserId] = useState<string | null>(null);
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<AdminUserVO | null>(null);

  const loadUsers = async () => {
    setLoading(true);
    try {
      const response = await getAdminUsers({
        page,
        size: pageSize,
        keyword: keyword || undefined,
        status: statusFilter,
      });
      setUsers(response.users);
      setTotal(response.total);
    } catch (error) {
      console.error("Failed to load users:", error);
      messageApi.error("Unable to load users right now.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadUsers();
  }, [page, pageSize, keyword, statusFilter]);

  const showPasswordModal = (title: string, password: string) => {
    modalApi.info({
      title,
      okText: "Close",
      content: (
        <div className="space-y-3 pt-3">
          <Typography.Text className="!block !text-white/70">
            This password is shown only once. Copy it now and deliver it through
            a secure channel.
          </Typography.Text>
          <Typography.Paragraph
            copyable={{ text: password }}
            className="!mb-0 !rounded-input !border !border-white/[0.08] !bg-white/[0.04] !px-4 !py-3 !font-mono !text-base !text-white"
          >
            {password}
          </Typography.Paragraph>
        </div>
      ),
    });
  };

  const openCreateModal = () => {
    setEditingUser(null);
    form.resetFields();
    form.setFieldsValue({
      role: "user",
      avatar: "",
    });
    setModalOpen(true);
  };

  const openEditModal = (user: AdminUserVO) => {
    setEditingUser(user);
    form.setFieldsValue({
      username: user.username,
      role: (user.role?.toLowerCase() === "admin" ? "admin" : "user") as AdminUserRole,
      avatar: user.avatar ?? "",
    });
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditingUser(null);
    form.resetFields();
  };

  const handleCreateOrUpdate = async (values: UserFormValues) => {
    setSubmitting(true);
    try {
      if (!editingUser) {
        form.setFields([{ name: "username", errors: [] }]);
      }
      if (editingUser) {
        await updateAdminUser(editingUser.id, {
          role: values.role,
          avatar: normalizeOptionalInput(values.avatar),
        });
        messageApi.success("User updated.");
      } else {
        const response = await createAdminUser({
          username: values.username!.trim(),
          role: values.role,
          avatar: normalizeOptionalInput(values.avatar),
        });
        messageApi.success("User created.");
        showPasswordModal(
          `Temporary password for ${response.username}`,
          response.initialPassword,
        );
      }
      closeModal();
      await loadUsers();
    } catch (error) {
      console.error("Failed to save user:", error);
      const errorMessage =
        error instanceof Error ? error.message : "Unable to save this user.";
      if (!editingUser && isDuplicateUsernameError(errorMessage)) {
        form.setFields([
          {
            name: "username",
            errors: ["This username already exists. Choose another one."],
          },
        ]);
        return;
      }
      messageApi.error(errorMessage);
    } finally {
      setSubmitting(false);
    }
  };

  const handleStatusChange = async (
    user: AdminUserVO,
    nextStatus: AdminUserStatus,
  ) => {
    setActionUserId(user.id);
    try {
      await updateAdminUserStatus(user.id, { status: nextStatus });
      messageApi.success(
        nextStatus === "DISABLED" ? "User disabled." : "User enabled.",
      );
      await loadUsers();
    } catch (error) {
      console.error("Failed to update user status:", error);
      messageApi.error("Unable to update user status.");
    } finally {
      setActionUserId(null);
    }
  };

  const handleResetPassword = async (user: AdminUserVO) => {
    setActionUserId(user.id);
    try {
      const response = await resetAdminUserPassword(user.id);
      messageApi.success("Password reset.");
      showPasswordModal(
        `New password for ${user.username}`,
        response.newPassword,
      );
    } catch (error) {
      console.error("Failed to reset password:", error);
      messageApi.error("Unable to reset password.");
    } finally {
      setActionUserId(null);
    }
  };

  const handleDeleteUser = async (user: AdminUserVO) => {
    setActionUserId(user.id);
    try {
      await deleteAdminUser(user.id);
      messageApi.success("User deleted.");
      await loadUsers();
    } catch (error) {
      console.error("Failed to delete user:", error);
      messageApi.error("Unable to delete this user.");
    } finally {
      setActionUserId(null);
    }
  };

  const columns = useMemo<ColumnsType<AdminUserVO>>(
    () => [
      {
        title: "User",
        dataIndex: "username",
        key: "username",
        width: 240,
        render: (_, record) => (
          <div className="flex items-center gap-3">
            <Avatar src={record.avatar || undefined} className="!bg-white/[0.12]">
              {record.username.charAt(0).toUpperCase()}
            </Avatar>
            <div className="text-sm font-semibold text-white">
              {record.username}
            </div>
          </div>
        ),
      },
      {
        title: "Role",
        dataIndex: "role",
        key: "role",
        width: 140,
        render: (role?: string) => <Tag color={roleColor(role)}>{roleLabel(role)}</Tag>,
      },
      {
        title: "Status",
        dataIndex: "status",
        key: "status",
        width: 220,
        render: (status: string | undefined, record) => {
          const isDisabled = status?.toUpperCase() === "DISABLED";
          const isBusy = actionUserId === record.id;
          return (
            <div className="flex flex-col gap-2">
              <Tag color={statusColor(status)} className="w-fit">
                {statusLabel(status)}
              </Tag>
              <Switch
                checked={!isDisabled}
                checkedChildren="Active"
                unCheckedChildren="Disabled"
                loading={isBusy}
                onChange={(checked) => {
                  void handleStatusChange(record, checked ? "ACTIVE" : "DISABLED");
                }}
              />
              <Typography.Text className="!text-xs !text-white/45">
                {isDisabled
                  ? "Sign-in and API access are blocked."
                  : "Account can sign in and call protected APIs."}
              </Typography.Text>
            </div>
          );
        },
      },
      {
        title: "Created",
        dataIndex: "createdAt",
        key: "createdAt",
        width: 210,
        render: (createdAt?: string) => formatTimestamp(createdAt),
      },
      {
        title: "Updated",
        dataIndex: "updatedAt",
        key: "updatedAt",
        width: 210,
        render: (updatedAt?: string) => formatTimestamp(updatedAt),
      },
      {
        title: "Action",
        key: "action",
        width: 280,
        render: (_, record) => {
          const isBusy = actionUserId === record.id;
          return (
            <Space wrap>
              <Button
                size="small"
                icon={<EditOutlined />}
                onClick={() => {
                  openEditModal(record);
                }}
              >
                Edit
              </Button>
              <Popconfirm
                title="Reset this user's password?"
                description="A new random password will be generated and shown once."
                okText="Reset"
                cancelText="Cancel"
                onConfirm={() => {
                  void handleResetPassword(record);
                }}
              >
                <Button
                  size="small"
                  icon={<KeyOutlined />}
                  loading={isBusy}
                >
                  Reset password
                </Button>
              </Popconfirm>
              <Popconfirm
                title="Delete this user?"
                description="This performs a soft delete, revokes active sessions, and hides the account from the user list."
                okText="Delete"
                cancelText="Cancel"
                okButtonProps={{ danger: true }}
                onConfirm={() => {
                  void handleDeleteUser(record);
                }}
              >
                <Button
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  loading={isBusy}
                >
                  Delete
                </Button>
              </Popconfirm>
            </Space>
          );
        },
      },
    ],
    [actionUserId],
  );

  const pagination: TablePaginationConfig = {
    current: page,
    pageSize,
    total,
    showSizeChanger: true,
    onChange: (nextPage, nextPageSize) => {
      setPage(nextPage);
      setPageSize(nextPageSize);
    },
  };

  return (
    <div className="space-y-5">
      {messageContextHolder}
      {modalContextHolder}

      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <div className="text-xs uppercase tracking-[0.28em] text-white/40">
            Admin / Users
          </div>
          <Typography.Title level={2} className="!mb-0 !mt-2 !text-white">
            User management
          </Typography.Title>
          <Typography.Text className="block !text-white/60">
            Create internal accounts, control access levels, and revoke user
            sessions when account state changes.
          </Typography.Text>
        </div>
        <Button
          type="primary"
          icon={<UserAddOutlined />}
          onClick={openCreateModal}
        >
          New user
        </Button>
      </div>

      <div className="flex flex-col gap-5">
        <Card className="shadow-admin-card">
          <div className="grid gap-4 md:grid-cols-[1fr_220px]">
            <Input.Search
              allowClear
              size="large"
              value={keywordInput}
              placeholder="Search by username"
              onChange={(event) => {
                setKeywordInput(event.target.value);
              }}
              onSearch={() => {
                setPage(1);
                setKeyword(keywordInput.trim());
              }}
            />
            <Select
              allowClear
              size="large"
              placeholder="Filter by status"
              value={statusFilter}
              options={[
                { label: "Active", value: "ACTIVE" },
                { label: "Disabled", value: "DISABLED" },
              ]}
              onChange={(value) => {
                setPage(1);
                setStatusFilter(value);
              }}
            />
          </div>
        </Card>

        <Card className="shadow-admin-card">
          <div className="mb-4 flex items-center justify-between">
            <div>
              <Typography.Title
                level={4}
                className="!mb-0 !mt-0 !text-white"
              >
                Directory
              </Typography.Title>
              <Typography.Text className="!text-white/60">
                {total} user{total === 1 ? "" : "s"} available in this workspace.
              </Typography.Text>
            </div>
          </div>

          <Table
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={users}
            pagination={pagination}
          />
        </Card>
      </div>

      <Modal
        title={editingUser ? "Edit user" : "Create user"}
        open={modalOpen}
        onCancel={closeModal}
        confirmLoading={submitting}
        okText={editingUser ? "Save" : "Create"}
        onOk={() => {
          void form.submit();
        }}
      >
        <Form<UserFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => {
            void handleCreateOrUpdate(values);
          }}
        >
          {!editingUser ? (
            <Form.Item
              label="Username"
              name="username"
              rules={[
                { required: true, message: "Username is required." },
                { min: 3, message: "Use at least 3 characters." },
              ]}
            >
              <Input
                size="large"
                placeholder="admin@example.com or admin"
                autoComplete="off"
                onChange={() => {
                  form.setFields([{ name: "username", errors: [] }]);
                }}
              />
            </Form.Item>
          ) : null}

          <Form.Item
            label="Role"
            name="role"
            rules={[{ required: true, message: "Choose a role." }]}
          >
            <Select
              size="large"
              options={[
                { label: "Member", value: "user" },
                { label: "Admin", value: "admin" },
              ]}
            />
          </Form.Item>

          <Form.Item label="Avatar URL" name="avatar">
            <Input
              size="large"
              placeholder="Optional, paste an image URL"
              autoComplete="off"
            />
          </Form.Item>

          {!editingUser ? (
            <Typography.Text className="!text-white/50">
              A random temporary password will be generated and shown once after
              creation.
            </Typography.Text>
          ) : null}
        </Form>
      </Modal>
    </div>
  );
}
